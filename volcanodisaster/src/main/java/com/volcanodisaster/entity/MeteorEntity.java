package com.volcanodisaster.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Big burning rock that falls out of the sky at a steep angle. */
public class MeteorEntity extends VolcanicProjectile {
    private final ItemStack look = new ItemStack(Items.MAGMA_BLOCK);

    public MeteorEntity(EntityType<? extends MeteorEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected double gravity() {
        return 0.02;
    }

    @Override
    public boolean isMeteor() {
        return true;
    }

    @Override
    protected void spawnTrail(Vec3 motion) {
        // Fill in the gap the meteor covers each tick so the trail looks continuous.
        for (int i = 0; i < 3; i++) {
            double f = i / 3.0;
            double x = this.getX() - motion.x * f + (this.random.nextDouble() - 0.5) * 0.6;
            double y = this.getY() + 0.6 - motion.y * f + (this.random.nextDouble() - 0.5) * 0.6;
            double z = this.getZ() - motion.z * f + (this.random.nextDouble() - 0.5) * 0.6;
            this.level().addAlwaysVisibleParticle(ParticleTypes.FLAME, true, x, y, z, 0.0, 0.0, 0.0);
            this.level().addAlwaysVisibleParticle(ParticleTypes.LARGE_SMOKE, true, x, y, z, 0.0, 0.03, 0.0);
        }
        if (this.random.nextInt(3) == 0) {
            this.level().addAlwaysVisibleParticle(ParticleTypes.LAVA, true, this.getX(), this.getY() + 0.6, this.getZ(), 0.0, 0.0, 0.0);
        }
    }

    @Override
    public ItemStack getItem() {
        return look;
    }
}
