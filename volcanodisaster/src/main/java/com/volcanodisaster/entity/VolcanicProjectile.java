package com.volcanodisaster.entity;

import com.volcanodisaster.volcano.Impacts;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.network.NetworkHooks;

/**
 * A burning projectile that flies on a fixed ballistic arc (no air drag) towards a target,
 * then blows a crater wherever it hits.
 */
public abstract class VolcanicProjectile extends Projectile implements ItemSupplier {
    private static final int MAX_LIFE_TICKS = 600;

    private int life;
    private float power = 1.0F;
    private double targetX;
    private double targetZ;

    protected VolcanicProjectile(EntityType<? extends VolcanicProjectile> type, Level level) {
        super(type, level);
    }

    /** Downward acceleration in blocks per tick per tick. */
    protected abstract double gravity();

    public abstract boolean isMeteor();

    protected abstract void spawnTrail(Vec3 motion);

    /**
     * Places the projectile at (x, y, z) and gives it the exact velocity needed to land at
     * (x + dx, y + dy, z + dz) after {@code ticks} ticks.
     */
    public void launch(double x, double y, double z, double dx, double dy, double dz, int ticks) {
        this.moveTo(x, y, z, 0.0F, 0.0F);
        double g = gravity();
        double vy = (dy + g * ticks * (ticks - 1) / 2.0) / ticks;
        this.setDeltaMovement(dx / ticks, vy, dz / ticks);
        this.targetX = x + dx;
        this.targetZ = z + dz;
    }

    /** Blast size multiplier (1 = normal). Only used on the server. */
    public void setPower(float power) {
        this.power = power;
    }

    public float getPower() {
        return power;
    }

    public double getTargetX() {
        return targetX;
    }

    public double getTargetZ() {
        return targetZ;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 motion = this.getDeltaMovement();

        if (!this.level().isClientSide) {
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            if (hit.getType() != HitResult.Type.MISS && !ForgeEventFactory.onProjectileImpact(this, hit)) {
                Vec3 at = hit.getLocation();
                Impacts.impact((ServerLevel) this.level(), at.x, at.y, at.z, isMeteor(), power, this.random);
                this.discard();
                return;
            }
            if (++life > MAX_LIFE_TICKS || this.getY() < this.level().getMinBuildHeight() - 32) {
                this.discard();
                return;
            }
        } else {
            spawnTrail(motion);
        }

        this.setPos(this.getX() + motion.x, this.getY() + motion.y, this.getZ() + motion.z);
        this.setDeltaMovement(motion.x, motion.y - gravity(), motion.z);
    }

    @Override
    public boolean displayFireAnimation() {
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        // In-flight rocks are not written to disk; the volcano turns any that get stuck in
        // unloaded chunks into far-away impacts instead.
        return false;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
