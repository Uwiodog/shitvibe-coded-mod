package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.network.ModNetwork;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A pyroclastic flow. BUILDUP: the ground around the caldera gets scorched while the volcano roars.
 * Then the eruption column collapses with a huge bang and RUNNING starts: a ring of scorching ash
 * races outwards along the ground, torching every column it passes. Chunks that were not loaded
 * when the front went past are remembered and torched as soon as they load.
 */
public class PyroclasticFlow {
    public enum Stage { BUILDUP, RUNNING, DONE }

    final int volcanoId;
    final int cx;
    final int cy;
    final int cz;
    final double rim;
    final double maxReach;
    final double speed;
    final int height;
    final int buildupTicks;
    final long seed;
    final float damage;
    /** Non-zero for a cluster's great flow. */
    final int clusterId;

    Stage stage = Stage.BUILDUP;
    int ticks;
    double front;
    final Set<Long> missed = new HashSet<>();

    private final double p1;
    private final double p2;
    private final double p3;

    PyroclasticFlow(int volcanoId, int cx, int cy, int cz, double rim, double maxReach, double speed,
                    int height, int buildupTicks, long seed, float damage, int clusterId) {
        this.volcanoId = volcanoId;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.rim = rim;
        this.maxReach = Math.max(rim + 8, maxReach);
        this.speed = speed;
        this.height = height;
        this.buildupTicks = buildupTicks;
        this.seed = seed;
        this.damage = damage;
        this.clusterId = clusterId;
        this.front = rim;
        RandomSource r = RandomSource.create(seed);
        this.p1 = r.nextDouble() * Math.PI * 2;
        this.p2 = r.nextDouble() * Math.PI * 2;
        this.p3 = r.nextDouble() * Math.PI * 2;
    }

    /** How far the flow gets in a given direction: lobes that go all the way, gaps that stop short. */
    double reachAt(double angle) {
        double n = 0.5 + 0.30 * Math.sin(2 * angle + p1) + 0.15 * Math.sin(5 * angle + p2) + 0.05 * Math.sin(11 * angle + p3);
        n = Mth.clamp(n, 0.0, 1.0);
        return rim + (maxReach - rim) * (0.45 + 0.55 * n);
    }

    boolean isGreat() {
        return clusterId != 0;
    }

    boolean isFinished() {
        return stage == Stage.DONE && missed.isEmpty();
    }

    // ------------------------------------------------------------------ ticking

    void tick(ServerLevel level) {
        RandomSource rand = level.getRandom();
        ticks++;
        switch (stage) {
            case BUILDUP -> tickBuildup(level, rand);
            case RUNNING -> tickRunning(level, rand);
            case DONE -> { }
        }
    }

    private void tickBuildup(ServerLevel level, RandomSource rand) {
        double progress = buildupTicks <= 0 ? 1.0 : ticks / (double) buildupTicks;

        // Scorch the ground around the caldera, harder as the build-up goes on.
        double scorchR = rim + (isGreat() ? 120 : 50);
        int columns = (isGreat() ? 120 : 60) + (int) ((isGreat() ? 200 : 100) * progress);
        for (int i = 0; i < columns; i++) {
            double a = rand.nextDouble() * Math.PI * 2;
            double d = rim + (scorchR - rim) * Math.sqrt(rand.nextDouble());
            double s = 0.35 + 0.4 * progress * (1.0 - (d - rim) / (scorchR - rim));
            Scorch.column(level, cx + (int) Math.round(Math.cos(a) * d), cz + (int) Math.round(Math.sin(a) * d),
                    cy, s, 10, false, 1, rand);
        }

        if (ticks % Math.max(4, 16 - (int) (12 * progress)) == 0) {
            level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                    10.0F + 10.0F * (float) progress, 0.3F + 0.25F * (float) progress);
            ModNetwork.shakeAround(level, cx, cy, cz, isGreat() ? 700 : 260, 0.2F + (isGreat() ? 1.0F : 0.6F) * (float) progress);
        }
        if (ticks % 2 == 0) {
            for (ServerPlayer player : Volcano.playersNear(level, cx, cy, cz, 700)) {
                Fx.far(player, ParticleTypes.LAVA, cx + 0.5, cy + 1.0, cz + 0.5, 12, rim * 0.4, 0.5, rim * 0.4, 0.0);
                Fx.far(player, ParticleTypes.FLAME, cx + 0.5, cy + 2.0, cz + 0.5, 10, rim * 0.3, 3.0, rim * 0.3, 0.15);
                for (int i = 0; i < 3; i++) {
                    Fx.far(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                            cx + 0.5 + (rand.nextDouble() - 0.5) * rim, cy + 1.0, cz + 0.5 + (rand.nextDouble() - 0.5) * rim,
                            0, 0.0, 0.35 + 0.25 * progress, 0.0, 1.0);
                }
            }
        }
        if (VolcanoConfig.VOLCANIC_LIGHTNING.get() && rand.nextInt(isGreat() ? 5 : 14) == 0) {
            Volcano.lightningNear(level, cx, cy, cz, rim * 2.5, rand);
        }

        if (ticks >= buildupTicks) {
            boom(level, rand);
            stage = Stage.RUNNING;
            ticks = 0;
        }
    }

    /** The eruption column collapses. */
    private void boom(ServerLevel level, RandomSource rand) {
        float volume = isGreat() ? 70.0F : 40.0F;
        level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, volume, 0.3F);
        level.playSound(null, cx + 0.5, cy, cz + 0.5, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, volume, 0.5F);
        ModNetwork.shakeAround(level, cx, cy, cz, isGreat() ? 1100 : 600, isGreat() ? 3.0F : 2.0F);
        for (ServerPlayer player : Volcano.playersNear(level, cx, cy, cz, 800)) {
            for (int i = 0; i < 16; i++) {
                double a = i / 16.0 * Math.PI * 2;
                Fx.far(player, ParticleTypes.EXPLOSION_EMITTER, cx + 0.5 + Math.cos(a) * rim, cy + 2.0 + rand.nextDouble() * 6,
                        cz + 0.5 + Math.sin(a) * rim, 1, 0.0, 0.0, 0.0, 0.0);
            }
            Fx.far(player, ParticleTypes.LAVA, cx + 0.5, cy + 2.0, cz + 0.5, 120, rim * 0.6, 2.0, rim * 0.6, 0.0);
        }
        // Blow out what's left right around the rim.
        for (int i = 0; i < (isGreat() ? 12 : 6); i++) {
            double a = rand.nextDouble() * Math.PI * 2;
            Impacts.impact(level, cx + 0.5 + Math.cos(a) * (rim + 4), cy + 1, cz + 0.5 + Math.sin(a) * (rim + 4), true,
                    isGreat() ? 1.8F : 1.3F, rand);
        }
    }

    private void tickRunning(ServerLevel level, RandomSource rand) {
        double prev = front;
        front = Math.min(maxReach, front + speed);
        int fromRing = (int) Math.floor(prev) + 1;
        int toRing = (int) Math.floor(front);
        for (int ring = fromRing; ring <= toRing; ring++) {
            sweepRing(level, ring, rand);
        }

        hurtEntities(level, rand);
        frontEffects(level, rand);

        if (front >= maxReach) {
            stage = Stage.DONE;
        }
    }

    /** Torches every column whose distance from the centre rounds down to {@code ring}. */
    private void sweepRing(ServerLevel level, int ring, RandomSource rand) {
        long inner = (long) ring * ring;
        long outer = (long) (ring + 1) * (ring + 1);
        for (int dx = -ring - 1; dx <= ring + 1; dx++) {
            long dx2 = (long) dx * dx;
            if (dx2 >= outer) {
                continue;
            }
            int dzMin = inner - dx2 > 0 ? (int) Math.ceil(Math.sqrt(inner - dx2)) : 0;
            int dzMax = (int) Math.floor(Math.sqrt(outer - dx2 - 1));
            for (int dz = dzMin; dz <= dzMax; dz++) {
                columnAt(level, dx, dz, rand);
                if (dz != 0) {
                    columnAt(level, dx, -dz, rand);
                }
            }
        }
    }

    private void columnAt(ServerLevel level, int dx, int dz, RandomSource rand) {
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (d <= rim) {
            return;
        }
        double reach = reachAt(Math.atan2(dz, dx));
        if (d > reach) {
            return;
        }
        int x = cx + dx;
        int z = cz + dz;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            missed.add(ChunkPos.asLong(x >> 4, z >> 4));
            return;
        }
        torch(level, x, z, d, reach, rand);
    }

    private void torch(ServerLevel level, int x, int z, double d, double reach, RandomSource rand) {
        double s = 1.0 - (d - rim) / (reach - rim);
        int h = (int) Math.round(height * (0.35 + 0.65 * s));
        int ash = 1 + (int) Math.round(6.0 * Math.pow(s, 1.5));
        Scorch.column(level, x, z, cy, 0.25 + 0.75 * s, h, VolcanoConfig.FLOW_BLAST.get(), ash, rand);
    }

    /** Torches a chunk the front passed while it was unloaded. */
    void applyMissedChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!missed.remove(ChunkPos.asLong(chunkX, chunkZ))) {
            return;
        }
        RandomSource rand = level.getRandom();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = (chunkX << 4) + lx;
                int z = (chunkZ << 4) + lz;
                double dx = x - cx;
                double dz = z - cz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= rim) {
                    continue;
                }
                double reach = reachAt(Math.atan2(dz, dx));
                if (d <= reach && d <= front) {
                    torch(level, x, z, d, reach, rand);
                }
            }
        }
    }

    boolean hasMissed(long chunkKey) {
        return missed.contains(chunkKey);
    }

    private void hurtEntities(ServerLevel level, RandomSource rand) {
        if (ticks % 2 != 0) {
            return;
        }
        double r = front + 4;
        AABB box = new AABB(cx - r, cy - 24, cz - r, cx + r, cy + height + 24, cz + r);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
                continue;
            }
            double dx = e.getX() - cx;
            double dz = e.getZ() - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < front - 8 || d > front + 1 || d > reachAt(Math.atan2(dz, dx)) + 2) {
                continue;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(e.getX()), Mth.floor(e.getZ()));
            if (e.getY() > Math.max(ground, cy) + height) {
                continue;
            }
            // Searing ash: burns, blinds, chokes.
            if (damage > 0) {
                e.hurt(level.damageSources().inFire(), damage);
            }
            e.setSecondsOnFire(isGreat() ? 20 : 12);
            e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0));
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 140, 2));
            if (d > 0.1) {
                e.push(dx / d * 0.8, 0.35, dz / d * 0.8);
                e.hurtMarked = true;
            }
        }
    }

    /** The visible ash cloud along the front, its roar, and shaking anyone near it. */
    private void frontEffects(ServerLevel level, RandomSource rand) {
        List<ServerPlayer> watchers = Volcano.playersNear(level, cx, cy, cz, maxReach + 500);
        if (watchers.isEmpty()) {
            return;
        }
        int samples = Mth.clamp(10 + (int) (front / 6), 10, 60);
        List<double[]> points = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double a = rand.nextDouble() * Math.PI * 2;
            if (front > reachAt(a)) {
                continue; // this lobe already stopped
            }
            double x = cx + 0.5 + Math.cos(a) * front;
            double z = cz + 0.5 + Math.sin(a) * front;
            int gx = Mth.floor(x);
            int gz = Mth.floor(z);
            int ground = level.getChunkSource().getChunkNow(gx >> 4, gz >> 4) != null
                    ? Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz), cy + height) : cy;
            points.add(new double[]{x, ground, z, Math.cos(a), Math.sin(a)});
        }
        double s = 1.0 - (front - rim) / (maxReach - rim);
        for (ServerPlayer player : watchers) {
            for (double[] pt : points) {
                double y = pt[1] + 1.0 + rand.nextDouble() * height * (0.3 + 0.5 * s);
                Fx.far(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pt[0], y, pt[2], 0, pt[3], 0.15, pt[4], 0.12);
                Fx.far(player, ParticleTypes.LARGE_SMOKE, pt[0], pt[1] + 2.0, pt[2], 6, 2.5, 2.0, 2.5, 0.08);
                if (rand.nextInt(4) == 0) {
                    Fx.far(player, ParticleTypes.CAMPFIRE_COSY_SMOKE, pt[0], pt[1] + 1.0, pt[2], 4, 2.0, 1.0, 2.0, 0.03);
                }
                if (rand.nextInt(6) == 0) {
                    Fx.far(player, ParticleTypes.FLAME, pt[0], pt[1] + 1.0, pt[2], 6, 2.0, 1.0, 2.0, 0.05);
                }
            }
            // Shake whoever the front is about to hit.
            double pdx = player.getX() - cx;
            double pdz = player.getZ() - cz;
            double gap = Math.abs(Math.sqrt(pdx * pdx + pdz * pdz) - front);
            if (gap < 60 && ticks % 4 == 0) {
                ModNetwork.shake(player, (float) (1.2 * (1.0 - gap / 60.0)));
            }
        }
        if (ticks % 5 == 0 && !points.isEmpty()) {
            double[] pt = points.get(rand.nextInt(points.size()));
            level.playSound(null, pt[0], pt[1], pt[2], SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 6.0F, 0.35F + rand.nextFloat() * 0.15F);
        }
    }

    // ------------------------------------------------------------------ saving

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Volcano", volcanoId);
        tag.putInt("X", cx);
        tag.putInt("Y", cy);
        tag.putInt("Z", cz);
        tag.putDouble("Rim", rim);
        tag.putDouble("MaxReach", maxReach);
        tag.putDouble("Speed", speed);
        tag.putInt("Height", height);
        tag.putInt("Buildup", buildupTicks);
        tag.putLong("Seed", seed);
        tag.putString("Stage", stage.name());
        tag.putInt("Ticks", ticks);
        tag.putDouble("Front", front);
        tag.putFloat("Damage", damage);
        tag.putInt("Cluster", clusterId);
        long[] arr = new long[missed.size()];
        int i = 0;
        for (long l : missed) {
            arr[i++] = l;
        }
        tag.putLongArray("Missed", arr);
        return tag;
    }

    static PyroclasticFlow load(CompoundTag tag) {
        PyroclasticFlow f = new PyroclasticFlow(tag.getInt("Volcano"), tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"),
                tag.getDouble("Rim"), tag.getDouble("MaxReach"), tag.getDouble("Speed"), tag.getInt("Height"),
                tag.getInt("Buildup"), tag.getLong("Seed"),
                tag.contains("Damage") ? tag.getFloat("Damage") : VolcanoConfig.FLOW_DAMAGE.get().floatValue(), tag.getInt("Cluster"));
        try {
            f.stage = Stage.valueOf(tag.getString("Stage"));
        } catch (IllegalArgumentException ex) {
            f.stage = Stage.DONE;
        }
        f.ticks = tag.getInt("Ticks");
        f.front = tag.getDouble("Front");
        for (long l : tag.getLongArray("Missed")) {
            f.missed.add(l);
        }
        return f;
    }
}
