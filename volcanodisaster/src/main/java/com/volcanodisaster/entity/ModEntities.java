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

    /** Three sizes of the same falling rock; they only differ in hitbox, look and how big the bang is. */
    public static final RegistryObject<EntityType<MeteorEntity>> METEOR_SMALL = meteor("meteor_small", 0.8F, 20);
    public static final RegistryObject<EntityType<MeteorEntity>> METEOR = meteor("meteor", 1.2F, 20);
    public static final RegistryObject<EntityType<MeteorEntity>> METEOR_LARGE = meteor("meteor_large", 2.0F, 24);

    private static RegistryObject<EntityType<MeteorEntity>> meteor(String name, float size, int trackingRange) {
        return ENTITIES.register(name,
                () -> EntityType.Builder.<MeteorEntity>of(MeteorEntity::new, MobCategory.MISC)
                        .sized(size, size)
                        .fireImmune()
                        .clientTrackingRange(trackingRange)
                        .updateInterval(1)
                        .build(name));
    }

    private ModEntities() {}
}
