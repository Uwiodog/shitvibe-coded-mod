package com.volcanodisaster.network;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.VolcanoMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** One tiny message: "shake your screen this hard". */
public final class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VolcanoMod.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ModNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(ShakeMessage.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ShakeMessage::encode)
                .decoder(ShakeMessage::decode)
                .consumerMainThread(ShakeMessage::handle)
                .add();
    }

    public static void shake(ServerPlayer player, float strength) {
        if (strength > 0.02F) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ShakeMessage(strength));
        }
    }

    /** Shakes everyone within {@code range}, fading out with distance. */
    public static void shakeAround(ServerLevel level, double x, double y, double z, double range, float strength) {
        double rSq = range * range;
        for (ServerPlayer player : level.players()) {
            double dSq = player.distanceToSqr(x, y, z);
            if (dSq < rSq) {
                shake(player, strength * (float) (1.0 - Math.sqrt(dSq) / range));
            }
        }
    }

    public static final class ShakeMessage {
        final float strength;

        public ShakeMessage(float strength) {
            this.strength = strength;
        }

        static void encode(ShakeMessage msg, FriendlyByteBuf buf) {
            buf.writeFloat(msg.strength);
        }

        static ShakeMessage decode(FriendlyByteBuf buf) {
            return new ShakeMessage(buf.readFloat());
        }

        static void handle(ShakeMessage msg, Supplier<NetworkEvent.Context> ctx) {
            if (VolcanoConfig.SCREEN_SHAKE.get()) {
                ClientEffects.addShake(msg.strength);
            }
        }
    }
}
