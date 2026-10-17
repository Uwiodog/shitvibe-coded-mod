package com.volcanodisaster.entity;

import com.volcanodisaster.VolcanoMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, VolcanoMod.MODID);

    public static final RegistryObject<EntityType<FireBombEntity>> FIRE_BOMB = ENTITIES.register("fire_bomb",
            () -> EntityType.Builder.<FireBombEntity>of(FireBombEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .fireImmune()
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("fire_bomb"));

    public static final RegistryObject<EntityType<MeteorEntity>> METEOR = ENTITIES.register("meteor",
            () -> EntityType.Builder.<MeteorEntity>of(MeteorEntity::new, MobCategory.MISC)
                    .sized(1.2F, 1.2F)
                    .fireImmune()
                    .clientTrackingRange(20)
                    .updateInterval(1)
                    .build("meteor"));

    private ModEntities() {}
}
