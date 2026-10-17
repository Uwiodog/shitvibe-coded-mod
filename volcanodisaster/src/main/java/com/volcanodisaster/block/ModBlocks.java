package com.volcanodisaster.block;

import com.volcanodisaster.VolcanoMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Volcanic ash: a snow-like layer that piles up, and a full block. Neither melts. */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, VolcanoMod.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, VolcanoMod.MODID);

    /** Snow layer behaviour (1-8 layers, stacking, walking through thin layers), but no random ticks so it never melts. */
    public static final RegistryObject<Block> ASH_LAYER = BLOCKS.register("ash_layer",
            () -> new SnowLayerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .replaceable()
                    .forceSolidOff()
                    .strength(0.1F)
                    .sound(SoundType.SAND)
                    .pushReaction(PushReaction.DESTROY)));

    public static final RegistryObject<Block> ASH_BLOCK = BLOCKS.register("ash_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(0.5F)
                    .sound(SoundType.SAND)));

    public static final RegistryObject<Item> ASH_LAYER_ITEM = ITEMS.register("ash_layer",
            () -> new BlockItem(ASH_LAYER.get(), new Item.Properties()));
    public static final RegistryObject<Item> ASH_BLOCK_ITEM = ITEMS.register("ash_block",
            () -> new BlockItem(ASH_BLOCK.get(), new Item.Properties()));

    private ModBlocks() {}

    /** Ash layer with the given number of layers (1-8); 8 or more gives the full ash block. */
    public static BlockState ash(int layers) {
        if (layers >= 8) {
            return ASH_BLOCK.get().defaultBlockState();
        }
        return ASH_LAYER.get().defaultBlockState().setValue(SnowLayerBlock.LAYERS, Math.max(1, layers));
    }

    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(ASH_LAYER_ITEM);
            event.accept(ASH_BLOCK_ITEM);
        }
    }
}
