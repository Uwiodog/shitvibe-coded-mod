package com.volcanodisaster.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Burning lava bomb thrown out of the caldera in a high arc. */
public class FireBombEntity extends VolcanicProjectile {
    private final ItemStack look = new ItemStack(Items.FIRE_CHARGE);

    public FireBombEntity(EntityType<? extends FireBombEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected double gravity() {
        return 0.04;
    }

    @Override
    public boolean isMeteor() {
        return false;
    }

    @Override
    protected void spawnTrail(Vec3 motion) {
        this.level().addParticle(ParticleTypes.FLAME, this.getX(), this.getY() + 0.3, this.getZ(), 0.0, 0.0, 0.0);
        if (this.random.nextInt(2) == 0) {
            this.level().addAlwaysVisibleParticle(ParticleTypes.LARGE_SMOKE, true,
                    this.getX(), this.getY() + 0.3, this.getZ(), 0.0, 0.02, 0.0);
        }
    }

    @Override
    public ItemStack getItem() {
        return look;
    }
}
