package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.entity.FireBombEntity;
import com.volcanodisaster.entity.MeteorEntity;
import com.volcanodisaster.entity.ModEntities;
import com.volcanodisaster.entity.VolcanicProjectile;
import com.volcanodisaster.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * One volcano: a lava caldera whose surface sits at the spawn Y. It opens up (FORMING), erupts for
 * days in shifting phases - fire bombs, meteors, lava spills, a spreading scorched zone, ashfall and
 * now and then a pyroclastic flow (ERUPTING) - then hardens into obsidian (COOLING) and is removed (DONE).
 */
public class Volcano {
    public enum Stage { FORMING, ERUPTING, COOLING, DONE }

    /** How hard the volcano is going right now. */
    public enum Activity {
        SIMMERING(0.15, 0.3), ACTIVE(1.0, 0.8), FRENZY(2.5, 2.5);

        final double rate;
        final double flowFactor;

        Activity(double rate, double flowFactor) {
            this.rate = rate;
            this.flowFactor = flowFactor;
        }
    }

    public static final int TICKS_PER_DAY = 24000;
    private static final TicketType<Integer> TICKET = TicketType.create("volcanodisaster:volcano", Integer::compare);
    private static final int COOLING_SETTLE_TICKS = 100;
    private static final int FLOW_COOLDOWN_TICKS = 20 * 60 * 3;

    final int id;
    final int cx;
    final int cy;
    final int cz;
    final long seed;
    final float intensity;
    /** Eruption length in ticks, 0 = until stopped. */
    final int eruptionTicks;
    final int radius;
    final int depth;
    final int clearHeight;
    final int maxSpill;

    Stage stage = Stage.FORMING;
    int stageTicks;
    int carveIndex;
    int coolIndex;
    Activity activity = Activity.ACTIVE;
    int activityTicksLeft;
    /** Stage tick of the guaranteed first flow, -1 once it happened (or if flows are off). */
    int firstFlowAt = -1;
    int flowCooldown;

    // Not saved
    private double bombBudget;
    private double meteorBudget;
    private boolean ticketActive;
    private int ticketDistance;
    private List<int[]> carveColumns;
    private final List<VolcanicProjectile> inFlight = new ArrayList<>();
    private final double phase1;
    private final double phase2;
    private final double[] breaches;

    Volcano(int id, BlockPos center, long seed, float intensity, int eruptionTicks,
            int radius, int depth, int clearHeight, int maxSpill) {
        this.id = id;
        this.cx = center.getX();
        this.cy = center.getY();
        this.cz = center.getZ();
        this.seed = seed;
        this.intensity = intensity;
        this.eruptionTicks = eruptionTicks;
        this.radius = radius;
        this.depth = depth;
        this.clearHeight = clearHeight;
        this.maxSpill = maxSpill;

        RandomSource r = RandomSource.create(seed);
        this.phase1 = r.nextDouble() * Math.PI * 2;
        this.phase2 = r.nextDouble() * Math.PI * 2;
        int n = 3 + r.nextInt(3);
        this.breaches = new double[n];
        for (int i = 0; i < n; i++) {
            breaches[i] = r.nextDouble() * Math.PI * 2;
        }
    }

    // ------------------------------------------------------------------ public info

    public int getId() {
        return id;
    }

    public Stage getStage() {
        return stage;
    }

    public Activity getActivity() {
        return activity;
    }

    public BlockPos getCenter() {
        return new BlockPos(cx, cy, cz);
    }

    /** Distance from the centre to just outside the caldera rim. */
    double rimRadius() {
        return radius * 1.16 + 3;
    }

    /** "2.5 days", "14 min", "until stopped"... */
    public String timeLeft() {
        int ticks = switch (stage) {
            case FORMING -> Math.max(0, VolcanoConfig.FORMING_SECONDS.get() * 20 - stageTicks);
            case ERUPTING -> eruptionTicks <= 0 ? -1 : Math.max(0, eruptionTicks - stageTicks);
            case COOLING -> Math.max(0, COOLING_SETTLE_TICKS + coolingTicks() - stageTicks);
            case DONE -> 0;
        };
        return describe(ticks);
    }

    public static String describe(int ticks) {
        if (ticks < 0) {
            return "until stopped";
        }
        if (ticks >= TICKS_PER_DAY) {
            return String.format("%.1f in-game days", ticks / (double) TICKS_PER_DAY);
        }
        if (ticks >= 20 * 60) {
            return (ticks / (20 * 60)) + " min";
        }
        return (ticks / 20) + " s";
    }

    public String eruptionLength() {
        return describe(eruptionTicks <= 0 ? -1 : eruptionTicks);
    }

    /** Moves straight on to the next stage. FORMING finishes the caldera instantly. */
    public void skipStage(ServerLevel level) {
        switch (stage) {
            case FORMING -> {
                List<int[]> cols = columns();
                RandomSource rand = level.getRandom();
                while (carveIndex < cols.size()) {
                    int[] c = cols.get(carveIndex++);
                    carveColumn(level, c[0], c[1], rand);
                }
                startEruption(level.getRandom());
            }
            case ERUPTING -> setStage(Stage.COOLING);
            case COOLING -> setStage(Stage.DONE);
            case DONE -> { }
        }
    }

    /** Ends the eruption and lets the lava harden. */
    public void stop() {
        if (stage == Stage.FORMING || stage == Stage.ERUPTING) {
            setStage(Stage.COOLING);
        }
    }

    /** Starts a pyroclastic flow now (with its build-up). Returns false if the volcano isn't erupting or one is already going. */
    public boolean triggerFlow(ServerLevel level, VolcanoData data) {
        if (stage != Stage.ERUPTING || data.hasActiveFlow(id)) {
            return false;
        }
        int buildup = VolcanoConfig.FLOW_BUILDUP_SECONDS.get() * 20;
        data.addFlow(new PyroclasticFlow(id, cx, cy, cz, rimRadius(), VolcanoConfig.FLOW_RADIUS.get(),
                VolcanoConfig.FLOW_SPEED.get(), VolcanoConfig.FLOW_HEIGHT.get(), buildup, level.getRandom().nextLong()));
        flowCooldown = FLOW_COOLDOWN_TICKS;
        firstFlowAt = -1;
        return true;
    }

    private void setStage(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private void startEruption(RandomSource rand) {
        setStage(Stage.ERUPTING);
        activity = Activity.ACTIVE;
        activityTicksLeft = 2400 + rand.nextInt(2400);
        firstFlowAt = VolcanoConfig.FLOWS_PER_DAY.get() > 0 ? 3600 + rand.nextInt(3600) : -1;
    }

    // ------------------------------------------------------------------ ticking

    void tick(ServerLevel level, VolcanoData data) {
        RandomSource rand = level.getRandom();
        updateTicket(level);
        stageTicks++;
        switch (stage) {
            case FORMING -> tickForming(level, rand);
            case ERUPTING -> tickErupting(level, data, rand);
            case COOLING -> tickCooling(level, rand);
            case DONE -> { }
        }
        if (stageTicks % 10 == 0) {
            checkInFlight(level);
        }
    }

    private void tickForming(ServerLevel level, RandomSource rand) {
        if (!calderaLoaded(level)) {
            return; // wait for the chunks to load so no part of the caldera gets skipped
        }
        List<int[]> cols = columns();
        int formingTicks = Math.max(1, VolcanoConfig.FORMING_SECONDS.get() * 20);
        int perTick = Math.max(1, Mth.ceil(cols.size() / (double) formingTicks));
        for (int i = 0; i < perTick && carveIndex < cols.size(); i++) {
            int[] c = cols.get(carveIndex++);
            carveColumn(level, c[0], c[1], rand);
        }

        if (stageTicks % 12 == 0) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                    6.0F, 0.35F + rand.nextFloat() * 0.2F);
            ModNetwork.shakeAround(level, cx, cy, cz, 120, 0.35F);
        }
        if (stageTicks % 3 == 0) {
            for (ServerPlayer player : playersNear(level, cx, cy, cz, 400)) {
                Fx.far(player, ParticleTypes.LARGE_SMOKE, cx + 0.5, cy + 1.0, cz + 0.5, 12,
                        radius * 0.5, 1.0, radius * 0.5, 0.05);
            }
        }
        if (carveIndex >= cols.size()) {
            startEruption(rand);
        }
    }

    private void tickErupting(ServerLevel level, VolcanoData data, RandomSource rand) {
        tickActivity(level, rand);
        double rate = intensity * activity.rate;

        bombBudget += VolcanoConfig.FIRE_BOMBS_PER_SECOND.get() * rate / 20.0;
        while (bombBudget >= 1.0) {
            bombBudget -= 1.0;
            launchFireBomb(level, rand);
        }
        meteorBudget += VolcanoConfig.METEORS_PER_SECOND.get() * rate / 20.0;
        while (meteorBudget >= 1.0) {
            meteorBudget -= 1.0;
            dropMeteor(level, rand);
        }

        if (VolcanoConfig.LAVA_SPILL.get() && stageTicks % VolcanoConfig.SPILL_INTERVAL_TICKS.get() == 0) {
            int spills = activity == Activity.FRENZY ? 2 : rand.nextDouble() < activity.rate ? 1 : 0;
            for (int i = 0; i < spills; i++) {
                spillLava(level, rand);
            }
        }

        scorchSurroundings(level, rand);
        ashfall(level, data, rand);
        eruptionEffects(level, rand);

        if (VolcanoConfig.VOLCANIC_LIGHTNING.get() && activity == Activity.FRENZY && rand.nextInt(50) == 0) {
            lightningNear(level, cx, cy, cz, rimRadius() * 2.5, rand);
        }

        // Pyroclastic flows: one guaranteed early on, then at random (far likelier during a frenzy).
        if (flowCooldown > 0) {
            flowCooldown--;
        } else if (!data.hasActiveFlow(id)) {
            boolean due;
            if (firstFlowAt >= 0) {
                due = stageTicks >= firstFlowAt;
            } else {
                double chance = VolcanoConfig.FLOWS_PER_DAY.get() / TICKS_PER_DAY * activity.flowFactor;
                due = rand.nextDouble() < chance;
            }
            if (due) {
                triggerFlow(level, data);
            }
        }

        if (eruptionTicks > 0 && stageTicks >= eruptionTicks) {
            setStage(Stage.COOLING);
        }
    }

    private void tickActivity(ServerLevel level, RandomSource rand) {
        if (!VolcanoConfig.ACTIVITY_PHASES.get()) {
            activity = Activity.ACTIVE;
            return;
        }
        if (--activityTicksLeft > 0) {
            return;
        }
        double roll = rand.nextDouble();
        Activity next = roll < 0.30 ? Activity.SIMMERING : roll < 0.75 ? Activity.ACTIVE : Activity.FRENZY;
        activityTicksLeft = switch (next) {
            case SIMMERING -> 2400 + rand.nextInt(3600);   // 2-5 min of relative calm
            case ACTIVE -> 2400 + rand.nextInt(4800);      // 2-6 min
            case FRENZY -> 1200 + rand.nextInt(2400);      // 1-3 min of chaos
        };
        if (next == Activity.FRENZY && activity != Activity.FRENZY) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 20.0F, 0.3F);
            ModNetwork.shakeAround(level, cx, cy, cz, 300, 0.8F);
        }
        activity = next;
    }

    private void tickCooling(ServerLevel level, RandomSource rand) {
        if (stageTicks % 4 == 0) {
            for (ServerPlayer player : playersNear(level, cx, cy, cz, 400)) {
                Fx.far(player, ParticleTypes.CAMPFIRE_COSY_SMOKE, cx + 0.5 + (rand.nextDouble() - 0.5) * radius,
                        cy + 1.0, cz + 0.5 + (rand.nextDouble() - 0.5) * radius, 0, 0.0, 0.08, 0.0, 1.0);
            }
        }
        // Let the last spills and bombs settle before hardening everything.
        if (stageTicks < COOLING_SETTLE_TICKS) {
            return;
        }
        if (!VolcanoConfig.COOL_TO_OBSIDIAN.get()) {
            setStage(Stage.DONE);
            return;
        }
        int s = coolRadius();
        int side = 2 * s + 1;
        int total = side * side;
        int perTick = Math.max(1, Mth.ceil(total / (double) coolingTicks()));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < perTick && coolIndex < total; i++, coolIndex++) {
            int dx = coolIndex % side - s;
            int dz = coolIndex / side - s;
            if (dx * dx + dz * dz > s * s) {
                continue;
            }
            coolColumn(level, cx + dx, cz + dz, p);
        }
        if (coolIndex >= total) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 6.0F, 0.6F);
            setStage(Stage.DONE);
        }
    }

    private int coolingTicks() {
        return Math.max(1, VolcanoConfig.COOLING_SECONDS.get() * 20);
    }

    private int coolRadius() {
        return Mth.ceil(rimRadius() + maxSpill + 8);
    }

    // ------------------------------------------------------------------ scorched zone and ash

    /** The ground around the caldera slowly burns and gets buried, spreading outwards over the first day. */
    private void scorchSurroundings(ServerLevel level, RandomSource rand) {
        double rim = rimRadius();
        double full = Math.max(rim + 1, VolcanoConfig.SCORCH_RADIUS.get());
        double now = rim + (full - rim) * Math.min(1.0, 0.15 + stageTicks / (double) TICKS_PER_DAY);
        int columns = (int) Math.round(VolcanoConfig.SCORCH_COLUMNS_PER_TICK.get() * Math.max(0.3, Math.min(1.5, activity.rate)));
        for (int i = 0; i < columns; i++) {
            double a = rand.nextDouble() * Math.PI * 2;
            double d = rim + (now - rim) * Math.sqrt(rand.nextDouble());
            double s = 0.15 + 0.35 * (1.0 - (d - rim) / (full - rim));
            int x = cx + (int) Math.round(Math.cos(a) * d);
            int z = cz + (int) Math.round(Math.sin(a) * d);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
                Scorch.column(level, x, z, cy, s, 8, false, rand.nextInt(4) == 0 ? 1 : 0, rand);
            }
        }
    }

    /** Ash settling over the Ashfall biome, thicker closer to the volcano. */
    private void ashfall(ServerLevel level, VolcanoData data, RandomSource rand) {
        int ashRadius = data.ashRadiusFor(cx, cz);
        if (ashRadius <= 0) {
            return;
        }
        int columns = (int) Math.round(VolcanoConfig.ASHFALL_COLUMNS_PER_TICK.get() * activity.rate);
        for (int i = 0; i < columns; i++) {
            double a = rand.nextDouble() * Math.PI * 2;
            double d = ashRadius * rand.nextDouble();
            int x = cx + (int) Math.round(Math.cos(a) * d);
            int z = cz + (int) Math.round(Math.sin(a) * d);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (top > level.getMinBuildHeight()) {
                Scorch.addAsh(level, x, top, z, 1, rand);
            }
        }
    }

    // ------------------------------------------------------------------ caldera

    /** Caldera edge radius at a given angle, slightly wobbly so it doesn't look like a perfect circle. */
    private double edge(double angle) {
        return radius * (1.0 + 0.10 * Math.sin(3 * angle + phase1) + 0.06 * Math.sin(7 * angle + phase2));
    }

    private boolean calderaLoaded(ServerLevel level) {
        int r = Mth.ceil(rimRadius());
        for (int x = (cx - r) >> 4; x <= (cx + r) >> 4; x++) {
            for (int z = (cz - r) >> 4; z <= (cz + r) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<int[]> columns() {
        if (carveColumns == null) {
            int r = Mth.ceil(rimRadius());
            List<int[]> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= edge(Math.atan2(dz, dx)) + 2.2) {
                        list.add(new int[]{dx, dz});
                    }
                }
            }
            // Rim first, then inwards, so the lava is walled in before it's poured.
            list.sort(Comparator.comparingDouble((int[] c) -> -(c[0] * c[0] + c[1] * c[1])));
            carveColumns = list;
        }
        return carveColumns;
    }

    private void carveColumn(ServerLevel level, int dx, int dz, RandomSource rand) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        double e = edge(Math.atan2(dz, dx));
        int x = cx + dx;
        int z = cz + dz;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        if (dist <= e) {
            double t = dist / e;
            int d = Math.max(1, (int) Math.round(depth * Math.sqrt(Math.max(0.0, 1.0 - t * t)) + (rand.nextDouble() - 0.5)));
            for (int y = cy + 1; y <= cy + clearHeight; y++) {
                Impacts.destroy(level, p.set(x, y, z), Impacts.QUIET);
            }
            BlockState lava = Blocks.LAVA.defaultBlockState();
            for (int y = cy - d + 1; y <= cy; y++) {
                Impacts.replace(level, p.set(x, y, z), lava, Impacts.QUIET);
            }
            BlockState floor = rand.nextBoolean() ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState();
            Impacts.replace(level, p.set(x, cy - d, z), floor, Impacts.QUIET);
        } else {
            // Low rim: one block above the lava next to the edge, flush with it further out.
            int top = dist <= e + 1.2 ? cy + 1 : cy;
            for (int y = top + 1; y <= cy + clearHeight; y++) {
                Impacts.destroy(level, p.set(x, y, z), Impacts.QUIET);
            }
            for (int y = cy - 1; y <= top; y++) {
                Impacts.replace(level, p.set(x, y, z), rimBlock(rand), Impacts.QUIET);
            }
        }
    }

    private static BlockState rimBlock(RandomSource rand) {
        float f = rand.nextFloat();
        if (f < 0.30F) return Blocks.BLACKSTONE.defaultBlockState();
        if (f < 0.55F) return Blocks.BASALT.defaultBlockState();
        if (f < 0.75F) return Blocks.MAGMA_BLOCK.defaultBlockState();
        return Blocks.OBSIDIAN.defaultBlockState();
    }

    // ------------------------------------------------------------------ eruption

    private void launchFireBomb(ServerLevel level, RandomSource rand) {
        double[] t = pickTarget(level, rand, radius + 4);
        if (t == null) {
            return;
        }
        double ox = cx + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.8;
        double oz = cz + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.8;
        double oy = cy + 1.5;

        if (inFlight.size() < VolcanoConfig.MAX_ACTIVE_PROJECTILES.get()
                && RemoteImpacts.isLive(level, ox, oz) && RemoteImpacts.isLive(level, t[0], t[1])) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(t[0]), Mth.floor(t[1]));
            double dx = t[0] - ox;
            double dz = t[1] - oz;
            // Longer flights for farther targets give high, rain-like arcs (about 30-65 blocks up).
            int ticks = Mth.clamp((int) (Math.sqrt(Math.sqrt(dx * dx + dz * dz)) * 8.0), 40, 140);
            FireBombEntity bomb = new FireBombEntity(ModEntities.FIRE_BOMB.get(), level);
            bomb.launch(ox, oy, oz, dx, ty - oy, dz, ticks);
            level.addFreshEntity(bomb);
            inFlight.add(bomb);
            if (rand.nextInt(4) == 0) {
                level.playSound(null, ox, oy, oz, SoundEvents.BLAZE_SHOOT, SoundSource.BLOCKS, 4.0F, 0.5F + rand.nextFloat() * 0.3F);
            }
        } else if (VolcanoConfig.IMPACT_UNLOADED_CHUNKS.get()) {
            RemoteImpacts.add(level, t[0], t[1], false);
        }
    }

    private void dropMeteor(ServerLevel level, RandomSource rand) {
        double[] t = pickTarget(level, rand, radius * 0.5);
        if (t == null) {
            return;
        }
        if (inFlight.size() < VolcanoConfig.MAX_ACTIVE_PROJECTILES.get() && RemoteImpacts.isLive(level, t[0], t[1])) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(t[0]), Mth.floor(t[1]));
            double a = rand.nextDouble() * Math.PI * 2;
            double off = 15 + rand.nextDouble() * 45;
            double sx = t[0] + Math.cos(a) * off;
            double sz = t[1] + Math.sin(a) * off;
            if (!RemoteImpacts.isLive(level, sx, sz)) {
                sx = t[0];
                sz = t[1];
            }
            double sy = Math.min(ty + 90 + rand.nextDouble() * 60, level.getMaxBuildHeight() + 48);
            double dx = t[0] - sx;
            double dy = ty - sy;
            double dz = t[1] - sz;
            int ticks = Mth.clamp((int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2.2), 15, 120);
            MeteorEntity meteor = new MeteorEntity(ModEntities.METEOR.get(), level);
            meteor.launch(sx, sy, sz, dx, dy, dz, ticks);
            level.addFreshEntity(meteor);
            inFlight.add(meteor);
        } else if (VolcanoConfig.IMPACT_UNLOADED_CHUNKS.get()) {
            RemoteImpacts.add(level, t[0], t[1], true);
        }
    }

    /** Random spot in a ring around the volcano, evenly spread by area, inside the world border. */
    private double[] pickTarget(ServerLevel level, RandomSource rand, double minDist) {
        double maxDist = Math.max(minDist + 1, VolcanoConfig.RAIN_RADIUS.get());
        for (int tries = 0; tries < 6; tries++) {
            double a = rand.nextDouble() * Math.PI * 2;
            double minSq = minDist * minDist;
            double d = Math.sqrt(minSq + (maxDist * maxDist - minSq) * rand.nextDouble());
            double x = cx + 0.5 + Math.cos(a) * d;
            double z = cz + 0.5 + Math.sin(a) * d;
            if (level.getWorldBorder().isWithinBounds(BlockPos.containing(x, cy, z))) {
                return new double[]{x, z};
            }
        }
        return null;
    }

    /** Places one new lava source somewhere along a spill channel; the reach grows over the eruption. */
    private void spillLava(ServerLevel level, RandomSource rand) {
        double progress = Math.min(1.0, stageTicks / (VolcanoConfig.SPILL_SPREAD_SECONDS.get() * 20.0));
        double reach = maxSpill * progress;
        double angle = breaches[rand.nextInt(breaches.length)] + (rand.nextDouble() - 0.5) * 0.6;
        double dist = edge(angle) + 2.5 + reach * (0.15 + 0.85 * rand.nextDouble());
        int x = cx + (int) Math.round(Math.cos(angle) * dist);
        int z = cz + (int) Math.round(Math.sin(angle) * dist);
        BlockPos spot = findSpillSpot(level, x, z);
        if (spot != null) {
            level.setBlock(spot, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** First open spot standing on something solid, searching down from a few blocks above the lava level. */
    private BlockPos findSpillSpot(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, cy + 4, z);
        if (!level.hasChunkAt(p)) {
            return null;
        }
        for (int y = cy + 4; y >= cy - 24; y--) {
            p.setY(y);
            if (level.isOutsideBuildHeight(p)) {
                return null;
            }
            BlockState s = level.getBlockState(p);
            FluidState f = s.getFluidState();
            if (f.is(FluidTags.LAVA) && f.isSource()) {
                return null; // already flooded here
            }
            boolean open = s.isAir() || s.canBeReplaced();
            if (!open) {
                continue;
            }
            BlockState below = level.getBlockState(p.below());
            if (below.blocksMotion()) {
                return p.immutable();
            }
        }
        return null;
    }

    private void eruptionEffects(ServerLevel level, RandomSource rand) {
        if (stageTicks % 2 == 0) {
            int plumes = activity == Activity.FRENZY ? 4 : activity == Activity.ACTIVE ? 2 : 1;
            double rise = activity == Activity.FRENZY ? 0.45 : 0.25;
            for (ServerPlayer player : playersNear(level, cx, cy, cz, 640)) {
                for (int i = 0; i < plumes; i++) {
                    Fx.far(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                            cx + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.7, cy + 1.0,
                            cz + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.7,
                            0, 0.0, rise + rand.nextDouble() * 0.15, 0.0, 1.0);
                }
                if (activity != Activity.SIMMERING) {
                    Fx.far(player, ParticleTypes.LAVA, cx + 0.5, cy + 1.0, cz + 0.5, 6 * plumes,
                            radius * 0.4, 0.3, radius * 0.4, 0.0);
                    Fx.far(player, ParticleTypes.FLAME, cx + 0.5, cy + 1.5, cz + 0.5, 3 * plumes,
                            radius * 0.3, 1.0, radius * 0.3, 0.08);
                }
            }
        }
        int rumbleOdds = activity == Activity.FRENZY ? 15 : activity == Activity.ACTIVE ? 45 : 160;
        if (rand.nextInt(rumbleOdds) == 0) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                    12.0F, 0.3F + rand.nextFloat() * 0.15F);
            if (activity == Activity.FRENZY) {
                ModNetwork.shakeAround(level, cx, cy, cz, 160, 0.4F);
            }
        }
        if (rand.nextInt(5) == 0) {
            level.playSound(null, cx + 0.5 + (rand.nextDouble() - 0.5) * radius, cy + 1, cz + 0.5 + (rand.nextDouble() - 0.5) * radius,
                    SoundEvents.LAVA_POP, SoundSource.BLOCKS, 2.0F, 0.6F + rand.nextFloat() * 0.4F);
        }
    }

    private void coolColumn(ServerLevel level, int x, int z, BlockPos.MutableBlockPos p) {
        p.set(x, cy, z);
        if (!level.hasChunkAt(p)) {
            return;
        }
        int top = cy + 8;
        int bottom = cy - depth - 24;
        for (int y = top; y >= bottom; y--) {
            p.setY(y);
            if (level.isOutsideBuildHeight(p)) {
                continue;
            }
            FluidState f = level.getFluidState(p);
            // Pools and thick flows harden; thin trickles just drain away once their sources are gone.
            if (f.is(FluidTags.LAVA) && (f.isSource() || f.getAmount() >= 6)) {
                level.setBlock(p.immutable(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    // ------------------------------------------------------------------ helpers shared with flows

    static List<ServerPlayer> playersNear(ServerLevel level, double x, double y, double z, double range) {
        List<ServerPlayer> out = new ArrayList<>();
        double rSq = range * range;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x + 0.5, y, z + 0.5) < rSq) {
                out.add(player);
            }
        }
        return out;
    }

    /** A harmless lightning flash near the volcano (no fire, no damage). */
    static void lightningNear(ServerLevel level, int cx, int cy, int cz, double spread, RandomSource rand) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        double x = cx + 0.5 + (rand.nextDouble() - 0.5) * 2 * spread;
        double z = cz + 0.5 + (rand.nextDouble() - 0.5) * 2 * spread;
        int gx = Mth.floor(x);
        int gz = Mth.floor(z);
        int y = level.getChunkSource().getChunkNow(gx >> 4, gz >> 4) != null
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, gx, gz) : cy;
        bolt.moveTo(x, y, z, 0.0F, 0.0F);
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    // ------------------------------------------------------------------ housekeeping

    /** Turns rocks that flew into chunks nobody is near (where they'd freeze in mid-air) into far-away hits. */
    private void checkInFlight(ServerLevel level) {
        Iterator<VolcanicProjectile> it = inFlight.iterator();
        while (it.hasNext()) {
            VolcanicProjectile e = it.next();
            if (e.isRemoved()) {
                it.remove();
            } else if (!level.isPositionEntityTicking(e.blockPosition())) {
                if (VolcanoConfig.IMPACT_UNLOADED_CHUNKS.get()) {
                    RemoteImpacts.add(level, e.getTargetX(), e.getTargetZ(), e.isMeteor());
                }
                e.discard();
                it.remove();
            }
        }
    }

    /** Keeps the caldera and spill area loaded and ticking so it keeps erupting while you're elsewhere. */
    private void updateTicket(ServerLevel level) {
        boolean want = stage != Stage.DONE && VolcanoConfig.KEEP_VOLCANO_LOADED.get();
        if (want && !ticketActive) {
            int reach = Mth.ceil(Math.max(rimRadius() + maxSpill, VolcanoConfig.SCORCH_RADIUS.get()));
            ticketDistance = Mth.clamp(Mth.ceil(reach / 16.0) + 2, 2, 10);
            level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(getCenter()), ticketDistance, id, true);
            ticketActive = true;
        } else if (!want && ticketActive) {
            releaseTicket(level);
        }
    }

    void releaseTicket(ServerLevel level) {
        if (ticketActive) {
            level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(getCenter()), ticketDistance, id, true);
            ticketActive = false;
        }
    }

    // ------------------------------------------------------------------ saving

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putInt("X", cx);
        tag.putInt("Y", cy);
        tag.putInt("Z", cz);
        tag.putLong("Seed", seed);
        tag.putFloat("Intensity", intensity);
        tag.putInt("EruptionTicks", eruptionTicks);
        tag.putInt("Radius", radius);
        tag.putInt("Depth", depth);
        tag.putInt("ClearHeight", clearHeight);
        tag.putInt("MaxSpill", maxSpill);
        tag.putString("Stage", stage.name());
        tag.putInt("StageTicks", stageTicks);
        tag.putInt("CarveIndex", carveIndex);
        tag.putInt("CoolIndex", coolIndex);
        tag.putString("Activity", activity.name());
        tag.putInt("ActivityTicks", activityTicksLeft);
        tag.putInt("FirstFlowAt", firstFlowAt);
        tag.putInt("FlowCooldown", flowCooldown);
        return tag;
    }

    static Volcano load(CompoundTag tag) {
        Volcano v = new Volcano(tag.getInt("Id"),
                new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                tag.getLong("Seed"), tag.getFloat("Intensity"), tag.getInt("EruptionTicks"),
                tag.getInt("Radius"), tag.getInt("Depth"), tag.getInt("ClearHeight"), tag.getInt("MaxSpill"));
        try {
            v.stage = Stage.valueOf(tag.getString("Stage"));
        } catch (IllegalArgumentException ex) {
            v.stage = Stage.DONE;
        }
        try {
            v.activity = Activity.valueOf(tag.getString("Activity"));
        } catch (IllegalArgumentException ex) {
            v.activity = Activity.ACTIVE;
        }
        v.stageTicks = tag.getInt("StageTicks");
        v.carveIndex = tag.getInt("CarveIndex");
        v.coolIndex = tag.getInt("CoolIndex");
        v.activityTicksLeft = tag.getInt("ActivityTicks");
        v.firstFlowAt = tag.contains("FirstFlowAt") ? tag.getInt("FirstFlowAt") : -1;
        v.flowCooldown = tag.getInt("FlowCooldown");
        return v;
    }
}
