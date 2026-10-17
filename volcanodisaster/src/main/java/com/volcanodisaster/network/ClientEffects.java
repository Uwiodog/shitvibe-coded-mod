package com.volcanodisaster.network;

/**
 * Screen-shake state on the client. Plain Java on purpose (no client classes) so the network
 * handler can reach it safely; the client event code reads it every frame.
 */
public final class ClientEffects {
    private static float shake;
    private static float prevShake;

    private ClientEffects() {}

    public static void addShake(float strength) {
        shake = Math.min(3.0F, Math.max(shake, strength) + strength * 0.25F);
    }

    /** Called once per client tick: shakes fade out over about a second and a half. */
    public static void tick() {
        prevShake = shake;
        shake = Math.max(0.0F, shake * 0.93F - 0.01F);
    }

    public static float shake(float partialTick) {
        return prevShake + (shake - prevShake) * partialTick;
    }
}
