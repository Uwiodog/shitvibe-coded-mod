package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.entity.VolcanicProjectile;
import com.volcanodisaster.network.ModNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * A volcanic cluster: a wide area that keeps a number of vents going. Vents open at different
 * times, erupt for a while, cool down, and new ones open somewhere else. Sites are surveyed first
 * so every vent sits close to the same ground plane; water sites become geysers. When enough of the
 * cluster is erupting at once, a great pyroclastic flow can sweep out hundreds of blocks.
 */
public class VolcanoCluster {
    private static final TicketType<Long> PROBE_TICKET = TicketType.create("volcanodisaster:site_probe", Long::compare);
    private static final int MAX_PROBES = 3;
    private static final int PROBE_TIMEOUT = 20 * 20;
    private static final int GREAT_FLOW_COOLDOWN = 20 * 60 * 8;
    /** How much each step of /volcano cluster intensity (1-5) multiplies the cluster's activity. */
    private static final double[] ACTIVITY_FACTORS = {0.35, 0.65, 1.0, 1.5, 2.2};

    final int id;
    final int cx;
    final int cz;
    final int baseY;
    final int width;
    final int ventTarget;
    /** How long new vents keep opening, 0 = until stopped. */
    final int durationTicks;
    final float intensity;
    final long seed;

    int age;
    boolean stopped;
    /** 1-5, set with /volcano cluster intensity. */
    int activity;
    int showerTicksLeft;
    int spawnCooldown;
    int greatFlowCooldown;
    /** Vents opened so far; the first few erupt right away, the rest of the first batch wait their turn. */
    int opened;
    final List<Integer> vents = new ArrayList<>();
    final List<Integer> geysers = new ArrayList<>();

    // Not saved: sites being looked at (their chunk is loading), and meteors in the air.
    private final List<long[]> probes = new ArrayList<>();
    private int failedProbes;
    private final List<VolcanicProjectile> inFlight = new ArrayList<>();
    private double meteorBudget;

    VolcanoCluster(int id, BlockPos center, int width, int ventTarget, int durationTicks, float intensity, long seed) {
        this.id = id;
        this.cx = center.getX();
        this.cz = center.getZ();
        this.baseY = center.getY();
        this.width = width;
        this.ventTarget = ventTarget;
        this.durationTicks = durationTicks;
        this.intensity = intensity;
        this.seed = seed;
        this.activity = Mth.clamp(VolcanoConfig.CLUSTER_ACTIVITY.get(), 1, 5);
    }

    public int getActivity() {
        return activity;
    }

    public void setActivity(int value) {
        activity = Mth.clamp(value, 1, 5);
    }

    /** Multiplier for vent fire rates, flow odds, meteor showers and how fast new vents open. */
    double factor() {
        return ACTIVITY_FACTORS[Mth.clamp(activity, 1, 5) - 1];
    }

    public boolean isShowering() {
        return showerTicksLeft > 0;
    }

    public int getId() {
        return id;
    }

    public BlockPos getCenter() {
        return new BlockPos(cx, baseY, cz);
    }

    public int getWidth() {
        return width;
    }

    public int getVentTarget() {
        return ventTarget;
    }

    public boolean isStopped() {
        return stopped;
    }

    public String timeLeft() {
        if (stopped) {
            return "winding down";
        }
        return Volcano.describe(durationTicks <= 0 ? -1 : Math.max(0, durationTicks - age));
    }

    static int autoVentCount(int width) {
        return Mth.clamp(Math.round(width / 150.0F), 5, 20);
    }

    private boolean openingNewVents() {
        return !stopped && (durationTicks <= 0 || age < durationTicks);
    }

    boolean isFinished() {
        return !openingNewVents() && vents.isEmpty() && geysers.isEmpty() && probes.isEmpty();
    }

    /** Stops opening new vents and ends every eruption (the lava hardens as usual). */
    void stop(ServerLevel level, VolcanoData data) {
        stopped = true;
        releaseProbes(level);
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null) {
                v.stop();
            }
        }
        data.removeGeysers(id);
        geysers.clear();
    }

    // ------------------------------------------------------------------ ticking

    void tick(ServerLevel level, VolcanoData data) {
        age++;
        RandomSource rand = level.getRandom();
        vents.removeIf(ventId -> data.byId(ventId) == null);
        geysers.removeIf(gid -> data.geyserById(gid) == null);

        if (openingNewVents()) {
            tickProbes(level, data, rand);
            int alive = vents.size() + geysers.size() + probes.size();
            if (alive < ventTarget && probes.size() < MAX_PROBES) {
                if (spawnCooldown > 0) {
                    spawnCooldown--;
                } else {
                    startProbe(level, data, rand);
                    // The first batch opens quickly; later replacements take 30-120 s to appear (faster when more active).
                    spawnCooldown = opened < ventTarget ? 10 : (int) ((600 + rand.nextInt(1800)) / factor());
                }
            }
        } else if (!probes.isEmpty()) {
            releaseProbes(level);
        }

        tickGreatFlow(level, data, rand);
        tickMeteors(level, data, rand);
    }

    // ------------------------------------------------------------------ meteor showers

    /**
     * Meteors across the whole cluster: a steady trickle while any vent erupts, and every so often a
     * shower of them for half a minute to a minute and a half.
     */
    private void tickMeteors(ServerLevel level, VolcanoData data, RandomSource rand) {
        if (age % 10 == 0) {
            Meteors.checkInFlight(level, inFlight);
        }
        boolean anyErupting = false;
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null && v.getStage() == Volcano.Stage.ERUPTING) {
                anyErupting = true;
                break;
            }
        }
        double f = factor();
        double perTick = 0;
        if (showerTicksLeft > 0) {
            showerTicksLeft--;
            perTick += VolcanoConfig.SHOWER_METEORS_PER_SECOND.get() * f / 20.0;
        } else if (anyErupting && rand.nextDouble() < VolcanoConfig.SHOWERS_PER_DAY.get() * f / Volcano.TICKS_PER_DAY) {
            startShower(level);
        }
        if (anyErupting) {
            perTick += VolcanoConfig.CLUSTER_METEORS_PER_MINUTE.get() * f / 1200.0;
        }
        meteorBudget += perTick;
        while (meteorBudget >= 1.0) {
            meteorBudget -= 1.0;
            double a = rand.nextDouble() * Math.PI * 2;
            double d = (width / 2.0 + 100) * Math.sqrt(rand.nextDouble());
            double x = cx + 0.5 + Math.cos(a) * d;
            double z = cz + 0.5 + Math.sin(a) * d;
            if (level.getWorldBorder().isWithinBounds(BlockPos.containing(x, baseY, z))) {
                Meteors.drop(level, x, z, Meteors.randomSize(rand, showerTicksLeft > 0 ? 0.10 : 0.12), rand, inFlight);
            }
        }
    }

    /** Starts a meteor shower now (also used by /volcano cluster shower). */
    void startShower(ServerLevel level) {
        RandomSource rand = level.getRandom();
        showerTicksLeft = 600 + rand.nextInt(1200);
        double range = width / 2.0 + 300;
        for (ServerPlayer player : Volcano.playersNear(level, cx, baseY, cz, range)) {
            player.displayClientMessage(Component.literal("\u00a76\u00a7lMeteor shower!"), true);
        }
        level.playSound(null, cx + 0.5, baseY, cz + 0.5, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 40.0F, 0.4F);
    }

    private void tickGreatFlow(ServerLevel level, VolcanoData data, RandomSource rand) {
        if (greatFlowCooldown > 0) {
            greatFlowCooldown--;
            return;
        }
        double perDay = VolcanoConfig.GREAT_FLOWS_PER_DAY.get();
        if (perDay <= 0 || ventTarget <= 0 || data.hasActiveGreatFlow(id)) {
            return;
        }
        int erupting = 0;
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null && v.getStage() == Volcano.Stage.ERUPTING) {
                erupting++;
            }
        }
        if (erupting < Math.max(1, Mth.ceil(ventTarget * VolcanoConfig.GREAT_FLOW_ACTIVE_FRACTION.get()))) {
            return;
        }
        if (rand.nextDouble() < perDay / Volcano.TICKS_PER_DAY * factor()) {
            triggerGreatFlow(level, data);
        }
    }

    /** Starts a great pyroclastic flow from the most violent erupting vent (or the cluster centre). */
    boolean triggerGreatFlow(ServerLevel level, VolcanoData data) {
        if (data.hasActiveGreatFlow(id)) {
            return false;
        }
        RandomSource rand = level.getRandom();
        Volcano source = null;
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null && v.getStage() == Volcano.Stage.ERUPTING && (source == null || v.getIntensity() > source.getIntensity())) {
                source = v;
            }
        }
        if (source == null) {
            for (int ventId : vents) {
                Volcano v = data.byId(ventId);
                if (v != null) {
                    source = v;
                    break;
                }
            }
        }
        BlockPos c = source != null ? source.getCenter() : getCenter();
        double rim = source != null ? source.rimRadius() : 20;
        int minReach = VolcanoConfig.GREAT_FLOW_MIN_REACH.get();
        int maxReach = Math.max(minReach, VolcanoConfig.GREAT_FLOW_MAX_REACH.get());
        double reach = minReach + rand.nextDouble() * (maxReach - minReach);
        int buildup = Math.max(VolcanoConfig.FLOW_BUILDUP_SECONDS.get(), 10) * 20 + 200;
        PyroclasticFlow flow = new PyroclasticFlow(source != null ? source.getId() : -id, c.getX(), c.getY(), c.getZ(),
                rim, reach, VolcanoConfig.FLOW_SPEED.get() * 1.2, VolcanoConfig.GREAT_FLOW_HEIGHT.get(), buildup,
                rand.nextLong(), VolcanoConfig.GREAT_FLOW_DAMAGE.get().floatValue(), id);
        data.addFlow(flow);
        greatFlowCooldown = GREAT_FLOW_COOLDOWN;

        // Every vent in the cluster roars at once.
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null) {
                BlockPos vc = v.getCenter();
                level.playSound(null, vc.getX() + 0.5, vc.getY(), vc.getZ() + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 30.0F, 0.25F);
            }
        }
        ModNetwork.shakeAround(level, cx, baseY, cz, width / 2.0 + 400, 1.2F);
        return true;
    }

    // ------------------------------------------------------------------ finding sites

    /** Picks a random spot in the cluster and starts loading it so its ground can be surveyed. */
    private void startProbe(ServerLevel level, VolcanoData data, RandomSource rand) {
        double a = rand.nextDouble() * Math.PI * 2;
        double d = width / 2.0 * Math.sqrt(rand.nextDouble());
        int x = cx + (int) Math.round(Math.cos(a) * d);
        int z = cz + (int) Math.round(Math.sin(a) * d);
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, baseY, z)) || tooClose(data, x, z)) {
            failedProbes++;
            return;
        }
        ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
        level.getChunkSource().addRegionTicket(PROBE_TICKET, cp, 1, cp.toLong());
        probes.add(new long[]{x, z, 0});
    }

    private void tickProbes(ServerLevel level, VolcanoData data, RandomSource rand) {
        Iterator<long[]> it = probes.iterator();
        while (it.hasNext()) {
            long[] probe = it.next();
            int x = (int) probe[0];
            int z = (int) probe[1];
            probe[2]++;
            if (!surroundingsLoaded(level, x, z)) {
                if (probe[2] > PROBE_TIMEOUT) {
                    release(level, x, z);
                    it.remove();
                    failedProbes++;
                }
                continue;
            }
            SiteSurvey survey = SiteSurvey.survey(level, x, z);
            release(level, x, z);
            it.remove();
            if (!placeSite(level, data, rand, x, z, survey)) {
                failedProbes++;
            }
        }
        // If the area is mostly unusable (sea, void, mountains), shrink the target rather than spin forever.
        if (failedProbes > ventTarget * 25) {
            failedProbes = 0;
            spawnCooldown = 1200;
        }
    }

    private boolean placeSite(ServerLevel level, VolcanoData data, RandomSource rand, int x, int z, SiteSurvey survey) {
        int tolerance = VolcanoConfig.PLANE_TOLERANCE.get();
        // Vents are randomly weaker or stronger than the cluster's average; more active clusters have stronger vents.
        float strength = Mth.clamp(intensity + (activity - 3) * 2.5F + (rand.nextFloat() - 0.5F) * 10.0F, 1.0F, 20.0F);
        double minDays = VolcanoConfig.VENT_MIN_DAYS.get();
        double maxDays = Math.max(minDays, VolcanoConfig.VENT_MAX_DAYS.get());
        int life = (int) ((minDays + rand.nextDouble() * (maxDays - minDays)) * Volcano.TICKS_PER_DAY);

        switch (survey.kind) {
            case WATER -> {
                if (!VolcanoConfig.GEYSERS.get() || Math.abs(survey.y - baseY) > tolerance * 3) {
                    return false;
                }
                Geyser g = data.addGeyser(id, x, survey.y, z, strength, life, rand);
                geysers.add(g.getId());
                opened++;
                return true;
            }
            case LAND -> {
                Integer y = SiteSurvey.planeY(survey.y, baseY, tolerance);
                if (y == null) {
                    return false;
                }
                // The first two vents erupt right away; the rest of the first batch wake up over the next few minutes.
                int dormant = opened < 2 ? 0 : opened < ventTarget ? rand.nextInt(20 * 60 * 6) : 0;
                Volcano v = data.spawnVent(level, new BlockPos(x, y, z), strength, life, id, dormant);
                vents.add(v.getId());
                opened++;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Vents keep a sensible distance from each other so they spread over the whole cluster. */
    private boolean tooClose(VolcanoData data, int x, int z) {
        double spacing = Mth.clamp(width / (Math.sqrt(ventTarget) * 1.8), 48, 400);
        double sq = spacing * spacing;
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null && distSq(v.getCenter(), x, z) < sq) {
                return true;
            }
        }
        for (int gid : geysers) {
            Geyser g = data.geyserById(gid);
            if (g != null && distSq(g.getCenter(), x, z) < sq) {
                return true;
            }
        }
        for (long[] p : probes) {
            double dx = p[0] - x;
            double dz = p[1] - z;
            if (dx * dx + dz * dz < sq) {
                return true;
            }
        }
        return false;
    }

    private static double distSq(BlockPos p, int x, int z) {
        double dx = p.getX() - x;
        double dz = p.getZ() - z;
        return dx * dx + dz * dz;
    }

    private static boolean surroundingsLoaded(ServerLevel level, int x, int z) {
        int r = SiteSurvey.REACH;
        for (int chunkX = (x - r) >> 4; chunkX <= (x + r) >> 4; chunkX++) {
            for (int chunkZ = (z - r) >> 4; chunkZ <= (z + r) >> 4; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void release(ServerLevel level, int x, int z) {
        ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
        level.getChunkSource().removeRegionTicket(PROBE_TICKET, cp, 1, cp.toLong());
    }

    private void releaseProbes(ServerLevel level) {
        for (long[] p : probes) {
            release(level, (int) p[0], (int) p[1]);
        }
        probes.clear();
    }

    // ------------------------------------------------------------------ saving

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putInt("X", cx);
        tag.putInt("Y", baseY);
        tag.putInt("Z", cz);
        tag.putInt("Width", width);
        tag.putInt("Vents", ventTarget);
        tag.putInt("Duration", durationTicks);
        tag.putFloat("Intensity", intensity);
        tag.putLong("Seed", seed);
        tag.putInt("Age", age);
        tag.putBoolean("Stopped", stopped);
        tag.putInt("SpawnCooldown", spawnCooldown);
        tag.putInt("GreatCooldown", greatFlowCooldown);
        tag.putInt("Opened", opened);
        tag.putInt("Activity", activity);
        tag.putInt("Shower", showerTicksLeft);
        tag.putIntArray("VentIds", vents.stream().mapToInt(Integer::intValue).toArray());
        tag.putIntArray("GeyserIds", geysers.stream().mapToInt(Integer::intValue).toArray());
        return tag;
    }

    static VolcanoCluster load(CompoundTag tag) {
        VolcanoCluster c = new VolcanoCluster(tag.getInt("Id"), new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                tag.getInt("Width"), tag.getInt("Vents"), tag.getInt("Duration"), tag.getFloat("Intensity"), tag.getLong("Seed"));
        c.age = tag.getInt("Age");
        c.stopped = tag.getBoolean("Stopped");
        c.spawnCooldown = tag.getInt("SpawnCooldown");
        c.greatFlowCooldown = tag.getInt("GreatCooldown");
        c.opened = tag.getInt("Opened");
        if (tag.contains("Activity")) {
            c.activity = Mth.clamp(tag.getInt("Activity"), 1, 5);
        }
        c.showerTicksLeft = tag.getInt("Shower");
        for (int v : tag.getIntArray("VentIds")) {
            c.vents.add(v);
        }
        for (int g : tag.getIntArray("GeyserIds")) {
            c.geysers.add(g);
        }
        return c;
    }

    /** For /volcano list: "6 erupting, 2 waiting, 1 geyser". */
    String summary(VolcanoData data) {
        int erupting = 0;
        int waiting = 0;
        int cooling = 0;
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v == null) {
                continue;
            }
            switch (v.getStage()) {
                case DORMANT, FORMING -> waiting++;
                case ERUPTING -> erupting++;
                case COOLING, DONE -> cooling++;
            }
        }
        return "intensity " + activity + "/5, " + erupting + " erupting, " + waiting + " waking up, " + cooling + " cooling, "
                + geysers.size() + " geyser" + (geysers.size() == 1 ? "" : "s") + (showerTicksLeft > 0 ? ", METEOR SHOWER" : "");
    }

    /** Vents ordered nearest-first, for the info command. */
    List<Volcano> ventsByDistance(VolcanoData data, BlockPos from) {
        List<Volcano> list = new ArrayList<>();
        for (int ventId : vents) {
            Volcano v = data.byId(ventId);
            if (v != null) {
                list.add(v);
            }
        }
        list.sort(Comparator.comparingDouble(v -> v.getCenter().distSqr(from)));
        return list;
    }
}
