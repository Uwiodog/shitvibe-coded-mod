package com.volcanodisaster.volcano;

import com.volcanodisaster.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * A boiling vent under water (part of a volcanic cluster). It steams quietly, then every minute or
 * two blasts a column of boiling water and steam into the sky, scalding and launching anything
 * standing over it. A ring of magma on the bottom drags swimmers and boats down.
 */
public class Geyser {
    final int id;
    final int clusterId;
    final int x;
    final int y; // water surface
    final int z;
    final float strength;
    final int lifeTicks;

    int age;
    int nextBurst;
    int burstLeft;
    int burstHeight;
    boolean seeded;

    Geyser(int id, int clusterId, int x, int y, int z, float strength, int lifeTicks, RandomSource rand) {
        this.id = id;
        this.clusterId = clusterId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.strength = strength;
        this.lifeTicks = lifeTicks;
        this.nextBurst = 200 + rand.nextInt(1200);
    }

    public int getId() {
        return id;
    }

    public BlockPos getCenter() {
        return new BlockPos(x, y, z);
    }

    boolean isFinished() {
        return lifeTicks > 0 && age >= lifeTicks;
    }

    void tick(ServerLevel level) {
        age++;
        boolean loaded = level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
        if (loaded && !seeded) {
            seedVent(level);
        }
        RandomSource rand = level.getRandom();
        List<ServerPlayer> watchers = loaded ? Volcano.playersNear(level, x, y, z, 400) : List.of();

        if (burstLeft > 0) {
            burstLeft--;
            if (loaded) {
                jet(level, watchers, rand);
            }
            return;
        }
        if (--nextBurst <= 0) {
            burstLeft = 100 + rand.nextInt(200);
            burstHeight = 15 + (int) (strength * 2.5F) + rand.nextInt(12);
            // Stronger geysers go off more often.
            nextBurst = (int) ((900 + rand.nextInt(1800)) * (10.0F / Math.max(2.0F, strength)));
            if (loaded) {
                level.playSound(null, x + 0.5, y, z + 0.5, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 8.0F, 1.4F);
                level.playSound(null, x + 0.5, y, z + 0.5, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 8.0F, 0.5F);
                ModNetwork.shakeAround(level, x, y, z, 48, 0.5F);
            }
            return;
        }
        // Quiet steaming between bursts.
        if (loaded && age % 6 == 0) {
            for (ServerPlayer p : watchers) {
                Fx.far(p, ParticleTypes.CLOUD, x + 0.5, y + 1.0, z + 0.5, 3, 1.5, 0.3, 1.5, 0.02);
            }
            if (rand.nextInt(10) == 0) {
                level.playSound(null, x + 0.5, y, z + 0.5, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.5F, 0.6F);
            }
        }
    }

    private void jet(ServerLevel level, List<ServerPlayer> watchers, RandomSource rand) {
        double speed = burstHeight / 18.0;
        for (ServerPlayer p : watchers) {
            for (int i = 0; i < 4; i++) {
                Fx.far(p, ParticleTypes.CLOUD, x + 0.5 + (rand.nextDouble() - 0.5) * 2.5, y + 1.0,
                        z + 0.5 + (rand.nextDouble() - 0.5) * 2.5, 0, 0.0, speed * (0.7 + rand.nextDouble() * 0.5), 0.0, 1.0);
            }
            Fx.far(p, ParticleTypes.SPLASH, x + 0.5, y + 1.0 + rand.nextDouble() * burstHeight * 0.6, z + 0.5, 30, 1.2, 2.0, 1.2, 0.4);
            Fx.far(p, ParticleTypes.FALLING_WATER, x + 0.5, y + burstHeight * 0.7, z + 0.5, 12, 3.5, 2.0, 3.5, 0.0);
            if (rand.nextInt(3) == 0) {
                Fx.far(p, ParticleTypes.POOF, x + 0.5, y + 1.0, z + 0.5, 6, 2.0, 0.5, 2.0, 0.05);
            }
        }
        if (rand.nextInt(8) == 0) {
            level.playSound(null, x + 0.5, y, z + 0.5, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 4.0F, 0.4F + rand.nextFloat() * 0.3F);
        }
        // Scald and launch whatever is above the vent.
        if (age % 4 == 0) {
            AABB column = new AABB(x - 3, y - 2, z - 3, x + 4, y + burstHeight, z + 4);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, column)) {
                if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
                    continue;
                }
                e.hurt(level.damageSources().hotFloor(), 2.0F + strength * 0.2F);
                launch(e, rand);
            }
            for (Entity e : level.getEntitiesOfClass(Entity.class, column)) {
                if (!(e instanceof LivingEntity)) {
                    launch(e, rand); // boats, items...
                }
            }
        }
    }

    private void launch(Entity e, RandomSource rand) {
        e.setDeltaMovement((rand.nextDouble() - 0.5) * 0.6, 1.0 + rand.nextDouble() * 0.6, (rand.nextDouble() - 0.5) * 0.6);
        e.hurtMarked = true;
    }

    /** Puts a little ring of magma on the bottom under the vent: it boils and pulls things down. */
    private void seedVent(ServerLevel level) {
        seeded = true;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        int bottom = y;
        while (bottom > level.getMinBuildHeight() + 1 && level.getFluidState(p.setY(bottom)).is(FluidTags.WATER)) {
            bottom--;
        }
        if (bottom == y) {
            return; // not actually water here any more
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                p.set(x + dx, bottom, z + dz);
                if (!level.isOutsideBuildHeight(p) && Impacts.breakable(level, p, level.getBlockState(p))
                        && level.getFluidState(p.above()).is(FluidTags.WATER)) {
                    level.setBlock(p.immutable(), Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putInt("Cluster", clusterId);
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putInt("Z", z);
        tag.putFloat("Strength", strength);
        tag.putInt("Life", lifeTicks);
        tag.putInt("Age", age);
        tag.putInt("NextBurst", nextBurst);
        tag.putBoolean("Seeded", seeded);
        return tag;
    }

    static Geyser load(CompoundTag tag) {
        Geyser g = new Geyser(tag.getInt("Id"), tag.getInt("Cluster"), tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"),
                tag.getFloat("Strength"), tag.getInt("Life"), RandomSource.create(tag.getInt("Id")));
        g.age = tag.getInt("Age");
        g.nextBurst = tag.getInt("NextBurst");
        g.seeded = tag.getBoolean("Seeded");
        return g;
    }
}
