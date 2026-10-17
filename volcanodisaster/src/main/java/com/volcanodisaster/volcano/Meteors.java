package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.entity.MeteorEntity;
import com.volcanodisaster.entity.ModEntities;
import com.volcanodisaster.entity.VolcanicProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Iterator;
import java.util.List;

/** Dropping meteors from the sky; shared by single volcanoes and cluster meteor showers. */
public final class Meteors {
    public static final int SMALL = 0;
    public static final int MEDIUM = 1;
    public static final int HUGE = 2;

    private Meteors() {}

    /** Mostly small ones, some medium, now and then a huge one. */
    public static int randomSize(RandomSource rand, double hugeChance) {
        double roll = rand.nextDouble();
        if (roll < hugeChance) {
            return HUGE;
        }
        return roll < hugeChance + 0.33 ? MEDIUM : SMALL;
    }

    public static float power(int size, RandomSource rand) {
        return switch (size) {
            case SMALL -> 0.45F + rand.nextFloat() * 0.35F;
            case MEDIUM -> 0.9F + rand.nextFloat() * 0.4F;
            default -> 1.6F + rand.nextFloat() * 0.8F;
        };
    }

    /**
     * Drops a meteor on (tx, tz). If a player is near enough for it to be seen, it's a real falling
     * rock coming in at an angle; otherwise the hit is applied directly to the (possibly unloaded) chunk.
     */
    public static void drop(ServerLevel level, double tx, double tz, int size, RandomSource rand, List<VolcanicProjectile> inFlight) {
        float power = power(size, rand);
        if (inFlight.size() < VolcanoConfig.MAX_ACTIVE_PROJECTILES.get() && RemoteImpacts.isLive(level, tx, tz)) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(tx), Mth.floor(tz));
            double a = rand.nextDouble() * Math.PI * 2;
            double off = 15 + rand.nextDouble() * 45;
            double sx = tx + Math.cos(a) * off;
            double sz = tz + Math.sin(a) * off;
            if (!RemoteImpacts.isLive(level, sx, sz)) {
                sx = tx;
                sz = tz;
            }
            double sy = Math.min(ty + 90 + rand.nextDouble() * 60, level.getMaxBuildHeight() + 48);
            double dx = tx - sx;
            double dy = ty - sy;
            double dz = tz - sz;
            // Big ones come in a little slower, so you see them coming.
            double speed = size == HUGE ? 1.6 : 2.2;
            int ticks = Mth.clamp((int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / speed), 15, 140);
            MeteorEntity meteor = new MeteorEntity(switch (size) {
                case SMALL -> ModEntities.METEOR_SMALL.get();
                case MEDIUM -> ModEntities.METEOR.get();
                default -> ModEntities.METEOR_LARGE.get();
            }, level);
            meteor.launch(sx, sy, sz, dx, dy, dz, ticks);
            meteor.setPower(power);
            level.addFreshEntity(meteor);
            inFlight.add(meteor);
        } else if (VolcanoConfig.IMPACT_UNLOADED_CHUNKS.get()) {
            RemoteImpacts.add(level, tx, tz, true, power);
        }
    }

    /** Turns rocks that flew into chunks nobody is near (where they'd freeze in mid-air) into far-away hits. */
    public static void checkInFlight(ServerLevel level, List<VolcanicProjectile> inFlight) {
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
}
