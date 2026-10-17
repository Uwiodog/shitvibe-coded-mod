package com.volcanodisaster.client;

import com.volcanodisaster.VolcanoMod;
import com.volcanodisaster.entity.ModEntities;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Draws fire bombs as glowing fire charges and meteors as big glowing magma blocks (both wrapped in flames). */
@Mod.EventBusSubscriber(modid = VolcanoMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.FIRE_BOMB.get(), ctx -> new ThrownItemRenderer<>(ctx, 2.5F, true));
        event.registerEntityRenderer(ModEntities.METEOR.get(), ctx -> new ThrownItemRenderer<>(ctx, 5.0F, true));
    }
}
