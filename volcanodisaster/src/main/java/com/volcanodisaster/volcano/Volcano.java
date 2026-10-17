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
 * days with an intensity (1-20) that rises and falls like a real volcano: a fire-bomb barrage close to the
 * caldera, a slow bombardment of random-sized meteors across the city, lava spills, a spreading scorched
 * zone, ashfall and now and then a pyroclastic flow (ERUPTING). Then it hardens into obsidian (COOLING)
 * and is removed (DONE).
 */
public class Volcano {
    public enum Stage { FORMING, ERUPTING, COOLING, DONE }

    /** Rough description of the current intensity. */
    public enum Activity { QUIET, ACTIVE, VIOLENT, PAROXYSM }

    public static final int TICKS_PER_DAY = 24000;
    private static final TicketType<Integer> TICKET = TicketType.create("volcanodisaster:volcano", Integer::compare);
    private static final int COOLING_SETTLE_TICKS = 100;
    private static final int FLOW_COOLDOWN_TICKS = 20 * 60 * 3;

    final int id;
    final int cx;
    final int cy;
    final int cz;
    final long seed;
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
    /** The volcano's usual strength, 1-20. */
    float strength;
    /** Current intensity, 1-20; drifts towards {@link #target}. */
    float intensity;
    float target;
    int episodeTicksLeft;
    /** Stage tick of the guaranteed first flow, -1 once it happened (or if flows are off). */
    int firstFlowAt = -1;
    int flowCooldown;

    // Not saved
    private double bombBudget;
    private double meteorBudget;
    private double bombardmentBudget;
    private boolean ticketActive;
    private int ticketDistance;
    private List<int[]> carveColumns;
    private final List<VolcanicProjectile> inFlight = new ArrayList<>();
    private final double phase1;
    private final double phase2;
    private final double[] breaches;

    Volcano(int id, BlockPos center, long seed, float strength, int eruptionTicks,
            int radius, int depth, int clearHeight, int maxSpill) {
        this.id = id;
        this.cx = center.getX();
        this.cy = center.getY();
        this.cz = center.getZ();
        this.seed = seed;
        this.strength = Mth.clamp(strength, 1.0F, 20.0F);
        this.intensity = this.strength;
        this.target = this.strength;
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
        if (intensity >= 17.0F) return Activity.PAROXYSM;
        if (intensity >= 12.0F) return Activity.VIOLENT;
        if (intensity >= 5.0F) return Activity.ACTIVE;
        return Activity.QUIET;
    }

    /** Current intensity on the 1-20 scale. */
    public float getIntensity() {
        return intensity;
    }

    public float getStrength() {
        return strength;
    }

    /** Whether the intensity is currently heading up or down (for /volcano list). */
    public String trend() {
        if (target > intensity + 0.75F) return "rising";
        if (target < intensity - 0.75F) return "falling";
        return "steady";
    }

    /** Sets the volcano's usual strength and moves the current intensity towards it. */
    public void setStrength(float value) {
        strength = Mth.clamp(value, 1.0F, 20.0F);
        target = strength;
        episodeTicksLeft = 2400 + (int) (Math.random() * 2400);
    }

    /** Rates are tuned for intensity 10; this is the multiplier for the current intensity. */
    private double rate() {
        return intensity / 10.0;
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
        // Opens up a bit below its usual strength and builds up to it.
        intensity = Math.max(1.0F, strength * 0.6F);
        target = strength;
        episodeTicksLeft = 2400 + rand.nextInt(2400);
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
        tickIntensity(level, rand);
        double rate = rate();

        // Close in: a dense barrage of fire bombs (and a few small meteors).
        bombBudget += VolcanoConfig.BARRAGE_BOMBS_PER_SECOND.get() * rate / 20.0;
        while (bombBudget >= 1.0) {
            bombBudget -= 1.0;
            double[] t = barrageTarget(level, rand);
            if (t != null) {
                launchFireBomb(level, rand, t);
            }
        }
        meteorBudget += VolcanoConfig.BARRAGE_METEORS_PER_SECOND.get() * rate / 20.0;
        while (meteorBudget >= 1.0) {
            meteorBudget -= 1.0;
            double[] t = barrageTarget(level, rand);
            if (t != null) {
                dropMeteor(level, rand, t, rand.nextInt(5) == 0 ? 1 : 0);
            }
        }
        // Further out: every now and then a meteor of random size somewhere across the city.
        bombardmentBudget += VolcanoConfig.BOMBARDMENT_HITS_PER_MINUTE.get() * rate / 1200.0;
        while (bombardmentBudget >= 1.0) {
            bombardmentBudget -= 1.0;
            double[] t = bombardmentTarget(level, rand);
            if (t != null) {
                double roll = rand.nextDouble();
                dropMeteor(level, rand, t, roll < 0.55 ? 0 : roll < 0.88 ? 1 : 2);
            }
        }

        if (VolcanoConfig.LAVA_SPILL.get() && stageTicks % VolcanoConfig.SPILL_INTERVAL_TICKS.get() == 0) {
            double spills = rate;
            while (spills >= 1.0 || (spills > 0 && rand.nextDouble() < spills)) {
                spillLava(level, rand);
                spills -= 1.0;
            }
        }

        scorchSurroundings(level, rand);
        ashfall(level, data, rand);
        eruptionEffects(level, rand);

        if (VolcanoConfig.VOLCANIC_LIGHTNING.get() && intensity >= 12.0F && rand.nextInt(Math.max(8, 110 - (int) (intensity * 5))) == 0) {
            lightningNear(level, cx, cy, cz, rimRadius() * 2.5, rand);
        }

        // Pyroclastic flows: one guaranteed early on, then at random - much likelier at high intensity.
        if (flowCooldown > 0) {
            flowCooldown--;
        } else if (!data.hasActiveFlow(id)) {
            boolean due;
            if (firstFlowAt >= 0) {
                due = stageTicks >= firstFlowAt;
            } else {
                double chance = VolcanoConfig.FLOWS_PER_DAY.get() / TICKS_PER_DAY * Math.pow(rate, 1.5);
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

    /**
     * Like a real volcano the intensity wanders: every few minutes it picks a new direction - a quiet
     * lull, a steady stretch around its usual strength, a surge, or (rarely) a paroxysm far above it -
     * and eases there over half a minute or so.
     */
    private void tickIntensity(ServerLevel level, RandomSource rand) {
        if (!VolcanoConfig.INTENSITY_VARIES.get()) {
            target = strength;
        } else if (--episodeTicksLeft <= 0) {
            double roll = rand.nextDouble();
            if (roll < 0.22) {
                target = strength * (0.15F + 0.25F * rand.nextFloat());               // lull
                episodeTicksLeft = 2400 + rand.nextInt(3600);
            } else if (roll < 0.72) {
                target = strength * (0.75F + 0.5F * rand.nextFloat());                // steady
                episodeTicksLeft = 2400 + rand.nextInt(4800);
            } else if (roll < 0.94) {
                target = strength * 1.35F + 1.0F + 3.0F * rand.nextFloat();           // surge
                episodeTicksLeft = 1200 + rand.nextInt(2400);
            } else {
                target = strength + 6.0F + 6.0F * rand.nextFloat();                   // paroxysm
                episodeTicksLeft = 600 + rand.nextInt(1200);
            }
            target = Mth.clamp(target, 1.0F, 20.0F);
        }
        float before = intensity;
        intensity = Mth.clamp(intensity + (target - intensity) * 0.003F, 1.0F, 20.0F);
        // Announce big surges with a roar everyone nearby feels.
        if (before < 14.0F && intensity >= 14.0F) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 25.0F, 0.3F);
            ModNetwork.shakeAround(level, cx, cy, cz, 350, 1.0F);
        }
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
        int columns = (int) Math.round(VolcanoConfig.SCORCH_COLUMNS_PER_TICK.get() * Mth.clamp(rate(), 0.3, 1.5));
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
        int columns = (int) Math.round(VolcanoConfig.ASHFALL_COLUMNS_PER_TICK.get() * rate());
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

    private void launchFireBomb(ServerLevel level, RandomSource rand, double[] t) {
        double ox = cx + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.8;
        double oz = cz + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.8;
        double oy = cy + 1.5;
        float power = 0.6F + rand.nextFloat() * 0.8F; // small to big bangs

        if (inFlight.size() < VolcanoConfig.MAX_ACTIVE_PROJECTILES.get()
                && RemoteImpacts.isLive(level, ox, oz) && RemoteImpacts.isLive(level, t[0], t[1])) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(t[0]), Mth.floor(t[1]));
            double dx = t[0] - ox;
            double dz = t[1] - oz;
            // Longer flights for farther targets give high, rain-like arcs.
            int ticks = Mth.clamp((int) (Math.sqrt(Math.sqrt(dx * dx + dz * dz)) * 8.0), 40, 140);
            FireBombEntity bomb = new FireBombEntity(ModEntities.FIRE_BOMB.get(), level);
            bomb.launch(ox, oy, oz, dx, ty - oy, dz, ticks);
            bomb.setPower(power);
            level.addFreshEntity(bomb);
            inFlight.add(bomb);
            if (rand.nextInt(4) == 0) {
                level.playSound(null, ox, oy, oz, SoundEvents.BLAZE_SHOOT, SoundSource.BLOCKS, 4.0F, 0.5F + rand.nextFloat() * 0.3F);
            }
        } else if (VolcanoConfig.IMPACT_UNLOADED_CHUNKS.get()) {
            RemoteImpacts.add(level, t[0], t[1], false, power);
        }
    }

    /** @param size 0 = small, 1 = medium, 2 = huge */
    private void dropMeteor(ServerLevel level, RandomSource rand, double[] t, int size) {
        float power = switch (size) {
            case 0 -> 0.45F + rand.nextFloat() * 0.35F;
            case 1 -> 0.9F + rand.nextFloat() * 0.4F;
            default -> 1.6F + rand.nextFloat() * 0.8F;
        };
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
            // Big ones come in a little slower, so you see them coming.
            double speed = size == 2 ? 1.6 : 2.2;
            int ticks = Mth.clamp((int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / speed), 15, 140);
            MeteorEntity meteor = new MeteorEntity(switch (size) {
                case 0 -> ModEntities.METEOR_SMALL.get();
                case 1 -> ModEntities.METEOR.get();
                default -> ModEntities.METEOR_LARGE.get();
            }, level);
            meteor.launch(sx, sy, sz, dx, dy, dz, ticks);
            meteor.setPower(power);
            level.addFreshEntity(meteor);
            inFlight.add(meteor);
        } else if (VolcanoConfig.IMPACT_UNLOADED_CHUNKS.get()) {
            RemoteImpacts.add(level, t[0], t[1], true, power);
        }
    }

    /**
     * Barrage targets: most land just outside the rim, getting steadily rarer with distance (an
     * exponential falloff) so there's no visible edge where the destruction stops.
     */
    private double[] barrageTarget(ServerLevel level, RandomSource rand) {
        double rim = rimRadius();
        double zone = Math.max(rim + 10, VolcanoConfig.BARRAGE_RADIUS.get());
        double mean = (zone - rim) / 3.0;
        for (int tries = 0; tries < 6; tries++) {
            double d = rim + 3 + -Math.log(1.0 - rand.nextDouble()) * mean;
            if (d > zone * 1.8) {
                continue;
            }
            double[] t = at(level, d, rand);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** Bombardment targets: anywhere from inside the barrage edge out to the bombardment radius, rarer further out. */
    private double[] bombardmentTarget(ServerLevel level, RandomSource rand) {
        double zone = VolcanoConfig.BARRAGE_RADIUS.get();
        double far = Math.max(zone + 20, VolcanoConfig.BOMBARDMENT_RADIUS.get());
        double from = zone * 0.6;
        for (int tries = 0; tries < 6; tries++) {
            double[] t = at(level, from + (far - from) * rand.nextDouble(), rand);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** A random spot at distance {@code d} from the centre, or null if it's outside the world border. */
    private double[] at(ServerLevel level, double d, RandomSource rand) {
        double a = rand.nextDouble() * Math.PI * 2;
        double x = cx + 0.5 + Math.cos(a) * d;
        double z = cz + 0.5 + Math.sin(a) * d;
        return level.getWorldBorder().isWithinBounds(BlockPos.containing(x, cy, z)) ? new double[]{x, z} : null;
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
            int plumes = 1 + (int) (intensity / 6.0F);
            double rise = 0.2 + intensity * 0.015;
            for (ServerPlayer player : playersNear(level, cx, cy, cz, 640)) {
                for (int i = 0; i < plumes; i++) {
                    Fx.far(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                            cx + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.7, cy + 1.0,
                            cz + 0.5 + (rand.nextDouble() - 0.5) * radius * 0.7,
                            0, 0.0, rise + rand.nextDouble() * 0.15, 0.0, 1.0);
                }
                if (intensity >= 3.0F) {
                    Fx.far(player, ParticleTypes.LAVA, cx + 0.5, cy + 1.0, cz + 0.5, 3 * plumes,
                            radius * 0.4, 0.3, radius * 0.4, 0.0);
                    Fx.far(player, ParticleTypes.FLAME, cx + 0.5, cy + 1.5, cz + 0.5, 2 * plumes,
                            radius * 0.3, 1.0, radius * 0.3, 0.08);
                }
            }
        }
        int rumbleOdds = Math.max(10, 200 - (int) (intensity * 9));
        if (rand.nextInt(rumbleOdds) == 0) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                    8.0F + intensity * 0.5F, 0.3F + rand.nextFloat() * 0.15F);
            if (intensity >= 12.0F) {
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
                    RemoteImpacts.add(level, e.getTargetX(), e.getTargetZ(), e.isMeteor(), e.getPower());
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
        tag.putFloat("Strength", strength);
        tag.putFloat("Intensity", intensity);
        tag.putFloat("Target", target);
        tag.putInt("EpisodeTicks", episodeTicksLeft);
        tag.putInt("EruptionTicks", eruptionTicks);
        tag.putInt("Radius", radius);
        tag.putInt("Depth", depth);
        tag.putInt("ClearHeight", clearHeight);
        tag.putInt("MaxSpill", maxSpill);
        tag.putString("Stage", stage.name());
        tag.putInt("StageTicks", stageTicks);
        tag.putInt("CarveIndex", carveIndex);
        tag.putInt("CoolIndex", coolIndex);
        tag.putInt("FirstFlowAt", firstFlowAt);
        tag.putInt("FlowCooldown", flowCooldown);
        return tag;
    }

    static Volcano load(CompoundTag tag) {
        // Volcanoes saved by older versions had no strength; give them the default.
        float strength = tag.contains("Strength") ? tag.getFloat("Strength") : VolcanoConfig.DEFAULT_INTENSITY.get();
        Volcano v = new Volcano(tag.getInt("Id"),
                new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                tag.getLong("Seed"), strength, tag.getInt("EruptionTicks"),
                tag.getInt("Radius"), tag.getInt("Depth"), tag.getInt("ClearHeight"), tag.getInt("MaxSpill"));
        try {
            v.stage = Stage.valueOf(tag.getString("Stage"));
        } catch (IllegalArgumentException ex) {
            v.stage = Stage.DONE;
        }
        if (tag.contains("Strength")) {
            v.intensity = Mth.clamp(tag.getFloat("Intensity"), 1.0F, 20.0F);
            v.target = Mth.clamp(tag.getFloat("Target"), 1.0F, 20.0F);
            v.episodeTicksLeft = tag.getInt("EpisodeTicks");
        }
        v.stageTicks = tag.getInt("StageTicks");
        v.carveIndex = tag.getInt("CarveIndex");
        v.coolIndex = tag.getInt("CoolIndex");
        v.firstFlowAt = tag.contains("FirstFlowAt") ? tag.getInt("FirstFlowAt") : -1;
        v.flowCooldown = tag.getInt("FlowCooldown");
        return v;
    }
}
