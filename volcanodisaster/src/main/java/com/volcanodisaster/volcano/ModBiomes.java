package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/** The biome itself is defined in data/volcanodisaster/worldgen/biome/ashfall.json. */
public final class ModBiomes {
    public static final ResourceKey<Biome> ASHFALL =
            ResourceKey.create(Registries.BIOME, new ResourceLocation(VolcanoMod.MODID, "ashfall"));

    private ModBiomes() {}
}
