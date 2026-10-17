package com.volcanodisaster.volcano;

import com.volcanodisaster.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What heat does to one column of the world: shared by the slowly growing scorched zone and by
 * pyroclastic flows (which hit much harder).
 */
public final class Scorch {
    private static final Map<Block, Boolean> GLASS = new IdentityHashMap<>();
    private static final Set<Block> TO_COARSE_DIRT = Set.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
            Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.MOSS_BLOCK, Blocks.ROOTED_DIRT, Blocks.DIRT);

    private Scorch() {}

    /**
     * @param baseY    roughly the ground level of the area (the volcano's Y)
     * @param strength 0..1, how hot it is here (1 = right next to the volcano)
     * @param height   how many blocks above {@code baseY} the heat reaches
     * @param blast    knock down weak blocks (wood, glass, wool, dirt) with a chance that grows with strength
     * @param maxAsh   most ash layers to add on top of the column (0 = none)
     */
    public static void column(ServerLevel level, int x, int z, int baseY, double strength, int height,
                              boolean blast, int maxAsh, RandomSource rand) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, baseY, z);
        if (!level.hasChunkAt(p)) {
            return;
        }
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int yHigh = Math.min(top, baseY + height);
        int yLow = Math.max(level.getMinBuildHeight(), Math.min(top, baseY) - 4);
        boolean surfaceFound = false;
        int surfaceY = Integer.MIN_VALUE;

        for (int y = yHigh; y >= yLow; y--) {
            p.setY(y);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            boolean isSurface = !surfaceFound;
            BlockState result = transform(level, p, s, strength, blast, isSurface, rand);
            if (result != null) {
                Impacts.replace(level, p, result, Impacts.QUIET);
                s = result;
            }
            if (!surfaceFound && !s.isAir()) {
                FluidState f = s.getFluidState();
                if (!f.isEmpty() || s.blocksMotion() || s.is(ModBlocks.ASH_LAYER.get())) {
                    surfaceFound = true;
                    surfaceY = y;
                }
            }
            // Set flammable things alight.
            if (!s.isAir() && rand.nextDouble() < 0.12 * strength
                    && s.isFlammable(level, p, Direction.UP)) {
                Impacts.igniteAbove(level, p.immutable());
            }
        }

        // Only bury the column if its top is inside the hot zone (ground or a low roof, not a skyscraper).
        if (maxAsh > 0 && surfaceFound && top <= yHigh && surfaceY == top) {
            addAsh(level, x, surfaceY, z, maxAsh, rand);
        }
    }

    /** Returns the replacement for a block, or null to leave it. */
    private static BlockState transform(ServerLevel level, BlockPos p, BlockState s, double strength,
                                        boolean blast, boolean isSurface, RandomSource rand) {
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) {
            // Boil off water near the volcano.
            if (f.is(FluidTags.WATER) && s.is(Blocks.WATER) && strength > 0.55 && rand.nextDouble() < strength) {
                return Blocks.AIR.defaultBlockState();
            }
            return null;
        }
        if (s.is(ModBlocks.ASH_LAYER.get()) || s.is(ModBlocks.ASH_BLOCK.get()) || s.getBlock() instanceof BaseFireBlock) {
            return null; // keep ash, and don't put out fires
        }
        if (s.is(BlockTags.LEAVES)) {
            return rand.nextDouble() < 0.4 + 0.6 * strength ? Blocks.AIR.defaultBlockState() : null;
        }
        if (isGlass(s.getBlock())) {
            return rand.nextDouble() < 0.3 + 0.7 * strength ? Blocks.AIR.defaultBlockState() : null;
        }
        if (s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.ICE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.PACKED_ICE)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (s.canBeReplaced()) {
            // Grass, flowers, crops, vines...
            return rand.nextDouble() < 0.5 + 0.5 * strength ? Blocks.AIR.defaultBlockState() : null;
        }
        if (s.is(BlockTags.WOOL)) {
            return rand.nextDouble() < strength ? Blocks.AIR.defaultBlockState() : null;
        }
        if (s.is(BlockTags.LOGS) && s.hasProperty(RotatedPillarBlock.AXIS) && rand.nextDouble() < 0.25 + 0.6 * strength) {
            // Charred trunk.
            return Blocks.BASALT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, s.getValue(RotatedPillarBlock.AXIS));
        }
        if (isSurface && TO_COARSE_DIRT.contains(s.getBlock()) && rand.nextDouble() < 0.4 + 0.6 * strength) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (blast && strength > 0.45) {
            // Wood and other burnables, plus soft stuff like dirt, sand and gravel. Stone, brick,
            // concrete, terracotta and quartz stay standing.
            boolean weak = s.getBlock().getExplosionResistance() <= 0.6F || s.isFlammable(level, p, Direction.UP);
            double chance = (strength - 0.45) / 0.55 * 0.7;
            if (weak && rand.nextDouble() < chance && Impacts.breakable(level, p, s)) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return null;
    }

    /** Adds up to {@code maxAsh} layers of ash on top of the block at surfaceY. */
    public static void addAsh(ServerLevel level, int x, int surfaceY, int z, int maxAsh, RandomSource rand) {
        BlockPos ground = new BlockPos(x, surfaceY, z);
        BlockState g = level.getBlockState(ground);
        int add = 1 + rand.nextInt(Math.max(1, maxAsh));
        if (g.is(ModBlocks.ASH_LAYER.get())) {
            int layers = g.getValue(SnowLayerBlock.LAYERS) + add;
            level.setBlock(ground, ModBlocks.ash(layers), Impacts.QUIET);
            return;
        }
        if (!g.getFluidState().isEmpty() || level.isOutsideBuildHeight(ground.above())) {
            return;
        }
        BlockPos above = ground.above();
        BlockState a = level.getBlockState(above);
        if (!a.isAir() && !a.is(Blocks.FIRE)) {
            return;
        }
        BlockState ash = ModBlocks.ash(add);
        if (ash.canSurvive(level, above)) {
            level.setBlock(above, ash, Impacts.QUIET);
        }
    }

    /** Picks up glass blocks and panes from any mod, by name. */
    static boolean isGlass(Block block) {
        return GLASS.computeIfAbsent(block, b -> {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
            return id != null && id.getPath().contains("glass");
        });
    }
}
