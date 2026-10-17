package com.volcanodisaster.volcano;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;

/** Particle helper that ignores the normal 32-block particle range, so plumes and blasts are visible across the city. */
public final class Fx {
    private Fx() {}

    /**
     * Same meaning as /particle ... force: with count 0, (dx, dy, dz) * speed is the particle's velocity;
     * with count > 0, (dx, dy, dz) is the random spread.
     */
    public static void far(ServerPlayer player, ParticleOptions type, double x, double y, double z,
                           int count, double dx, double dy, double dz, double speed) {
        player.connection.send(new ClientboundLevelParticlesPacket(type, true, x, y, z,
                (float) dx, (float) dy, (float) dz, (float) speed, count));
    }
}
