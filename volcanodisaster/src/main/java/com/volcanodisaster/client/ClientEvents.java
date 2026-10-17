package com.volcanodisaster.client;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.VolcanoMod;
import com.volcanodisaster.network.ClientEffects;
import com.volcanodisaster.volcano.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.ThreadLocalRandom;

/** Ash haze inside the Ashfall biome, and screen shake. */
@Mod.EventBusSubscriber(modid = VolcanoMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEvents {
    private static float haze;
    private static float prevHaze;

    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        prevHaze = haze;
        boolean inAsh = mc.level != null && mc.player != null
                && mc.level.getBiome(mc.player.blockPosition()).is(ModBiomes.ASHFALL);
        // Fade in/out over ~2.5 seconds as you cross the biome edge.
        haze = Mth.approach(haze, inAsh ? 1.0F : 0.0F, 0.02F);
        ClientEffects.tick();
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!VolcanoConfig.ASH_FOG.get() || event.getType() != FogType.NONE) {
            return;
        }
        float f = Mth.lerp((float) event.getPartialTick(), prevHaze, haze);
        if (f <= 0.001F) {
            return;
        }
        float far = event.getFarPlaneDistance();
        float target = Math.min(far, VolcanoConfig.ASH_FOG_DISTANCE.get().floatValue());
        float newFar = Mth.lerp(f, far, target);
        float newNear = Mth.lerp(f, event.getNearPlaneDistance(), newFar * 0.1F);
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(Math.min(newNear, newFar * 0.9F));
        event.setCanceled(true); // required for the new distances to be used
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!VolcanoConfig.SCREEN_SHAKE.get()) {
            return;
        }
        float s = ClientEffects.shake((float) event.getPartialTick());
        if (s <= 0.01F) {
            return;
        }
        double scale = Minecraft.getInstance().options.screenEffectScale().get();
        float amount = (float) (s * scale);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        event.setYaw(event.getYaw() + (r.nextFloat() - 0.5F) * 2.0F * amount);
        event.setPitch(event.getPitch() + (r.nextFloat() - 0.5F) * 1.6F * amount);
        event.setRoll(event.getRoll() + (r.nextFloat() - 0.5F) * 3.0F * amount);
    }
}
