package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import com.volcanodisaster.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Crater, obsidian and fire left behind by a fire bomb or meteor hit. */
public final class Impacts {
    /** Send to clients, skip neighbour/shape updates: fast and never pops off item drops. */
    public static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private Impacts() {}

    /**
     * @param power blast size multiplier: about 0.5 for a small meteor, 1 for a medium one, 2 for a huge one
     */
    public static void impact(ServerLevel level, double x, double y, double z, boolean meteor, float power, RandomSource rand) {
        double base = meteor ? VolcanoConfig.METEOR_BLAST_RADIUS.get() : VolcanoConfig.FIRE_BOMB_BLAST_RADIUS.get();
        double r = base * power * (0.85 + rand.nextDouble() * 0.3);
        BlockPos c = BlockPos.containing(x, y - (meteor ? Math.min(3.0, power) : 0.3), z);

        if (r > 0.5) {
            carveCrater(level, c, r, rand);
        }
        double obsidianChance = VolcanoConfig.OBSIDIAN_CHANCE.get() * (meteor ? 1.0 : 0.25);
        double fireChance = Math.min(1.0, VolcanoConfig.FIRE_CHANCE.get() * (meteor ? 1.0 : 1.7));
        scorch(level, c, Math.max(r, 1.0) + 1.5, obsidianChance, fireChance, rand);
        if (meteor) {
            placeMeteorite(level, c, r, power, rand);
        }
        effects(level, x, y, z, r, meteor, power, rand);
    }

    /** Blows out a slightly flattened, ragged sphere of blocks. Obsidian and other blast-proof blocks survive. */
    private static void carveCrater(ServerLevel level, BlockPos c, double r, RandomSource rand) {
        int ri = Mth.ceil(r + 0.6);
        double inner = Math.max(0.0, r - 1.5);
        double innerSq = inner * inner;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    double sy = dy * 1.25;
                    double d2 = dx * dx + sy * sy + dz * dz;
                    double lim = r + (rand.nextDouble() - 0.5) * 1.2;
                    if (d2 > lim * lim) {
                        continue;
                    }
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    // The outer shell updates neighbours so sand falls and liquids flow in.
                    destroy(level, p, d2 > innerSq ? Block.UPDATE_ALL : QUIET);
                }
            }
        }
    }

    /** Turns some of the exposed ground into obsidian/magma and lights fires on top. */
    private static void scorch(ServerLevel level, BlockPos c, double radius, double obsidianChance, double fireChance, RandomSource rand) {
        int ri = Mth.ceil(radius);
        double rSq = radius * radius;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                if (dx * dx + dz * dz > rSq) {
                    continue;
                }
                for (int y = c.getY() + ri + 1; y >= c.getY() - ri - 3; y--) {
                    p.set(c.getX() + dx, y, c.getZ() + dz);
                    if (level.isOutsideBuildHeight(p) || !level.hasChunkAt(p)) {
                        break;
                    }
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) {
                        continue;
                    }
                    // First non-air block from above: the surface of this column.
                    if (!s.getFluidState().isEmpty() || !s.blocksMotion()) {
                        break;
                    }
                    if (rand.nextDouble() < obsidianChance && breakable(level, p, s)) {
                        clearContents(level, p, s);
                        level.setBlock(p.immutable(), crustBlock(rand), Block.UPDATE_ALL);
                    }
                    if (rand.nextDouble() < fireChance) {
                        igniteAbove(level, p.immutable());
                    }
                    break;
                }
            }
        }
    }

    /** A small lump of obsidian and magma sitting at the bottom of a meteor crater. */
    private static void placeMeteorite(ServerLevel level, BlockPos c, double r, float power, RandomSource rand) {
        int top = c.getY() + Mth.ceil(r);
        int bottom = c.getY() - Mth.ceil(r) - 4;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(c.getX(), top, c.getZ());
        int floor = Integer.MIN_VALUE;
        for (int y = top; y >= bottom; y--) {
            p.setY(y);
            if (level.isOutsideBuildHeight(p) || !level.hasChunkAt(p)) {
                return;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && (s.blocksMotion() || !s.getFluidState().isEmpty())) {
                floor = y;
                break;
            }
        }
        if (floor == Integer.MIN_VALUE) {
            return;
        }
        int cy = floor + 1;
        // Bigger meteors leave bigger lumps.
        double coreSq = (2.2 + rand.nextDouble() * 1.5) * Math.max(0.5, power * power);
        int ext = Mth.ceil(Math.sqrt(coreSq)) + 1;
        for (int dx = -ext; dx <= ext; dx++) {
            for (int dy = -1; dy <= ext; dy++) {
                for (int dz = -ext; dz <= ext; dz++) {
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > coreSq + rand.nextDouble() * 1.2) {
                        continue;
                    }
                    p.set(c.getX() + dx, cy + dy, c.getZ() + dz);
                    if (level.isOutsideBuildHeight(p) || !level.hasChunkAt(p)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.canBeReplaced() || (breakable(level, p, s) && dy <= 0)) {
                        clearContents(level, p, s);
                        float roll = rand.nextFloat();
                        BlockState rock = roll < 0.6F ? Blocks.OBSIDIAN.defaultBlockState()
                                : roll < 0.88F ? Blocks.MAGMA_BLOCK.defaultBlockState()
                                : Blocks.CRYING_OBSIDIAN.defaultBlockState();
                        level.setBlock(p.immutable(), rock, Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static void effects(ServerLevel level, double x, double y, double z, double r, boolean meteor,
                                float power, RandomSource rand) {
        boolean playerNear = false;
        double sightRange = 320.0 * Math.max(1.0, power);
        for (ServerPlayer player : level.players()) {
            double dSq = player.distanceToSqr(x, y, z);
            if (dSq < 192.0 * 192.0) {
                playerNear = true;
            }
            if (meteor && dSq < sightRange * sightRange) {
                Fx.far(player, ParticleTypes.EXPLOSION_EMITTER, x, y + 1.0, z, power > 1.5F ? 4 : 1, r * 0.3, r * 0.2, r * 0.3, 0.0);
                Fx.far(player, ParticleTypes.LARGE_SMOKE, x, y + 1.0, z, (int) (25 * power), r * 0.5, 1.0 + r * 0.2, r * 0.5, 0.05);
                if (power > 1.5F) {
                    Fx.far(player, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y + 1.0, z, 12, r * 0.4, 1.0, r * 0.4, 0.02);
                }
            }
        }
        // A distant bang: everyone in earshot hears it (big meteors carry for hundreds of blocks).
        if (meteor) {
            level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                    6.0F + 10.0F * power, Math.max(0.3F, 0.7F - 0.2F * power + rand.nextFloat() * 0.15F));
            ModNetwork.shakeAround(level, x, y, z, 40 + 50 * power, 0.4F + 0.5F * power);
        }
        if (!playerNear) {
            return; // nobody close enough to get hurt
        }
        // Damage and knock back mobs/players. Blocks were handled above, so this one doesn't break any.
        level.explode(null, x, y, z, (float) Math.min(Math.max(r, 1.5), 8.0), false, Level.ExplosionInteraction.NONE);
    }

    /** Removes a block without dropping items. Returns true if something was removed. */
    public static boolean destroy(ServerLevel level, BlockPos.MutableBlockPos p, int flags) {
        if (level.isOutsideBuildHeight(p) || !level.hasChunkAt(p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        if (s.isAir() || !breakable(level, p, s)) {
            return false;
        }
        clearContents(level, p, s);
        level.setBlock(p.immutable(), Blocks.AIR.defaultBlockState(), flags);
        return true;
    }

    /** Replaces a block (if it can be broken) without dropping items. */
    public static void replace(ServerLevel level, BlockPos.MutableBlockPos p, BlockState with, int flags) {
        if (level.isOutsideBuildHeight(p) || !level.hasChunkAt(p)) {
            return;
        }
        BlockState s = level.getBlockState(p);
        if (s == with || (!s.isAir() && !breakable(level, p, s))) {
            return;
        }
        clearContents(level, p, s);
        level.setBlock(p.immutable(), with, flags);
    }

    public static boolean breakable(ServerLevel level, BlockPos p, BlockState s) {
        return s.getDestroySpeed(level, p) >= 0.0F && s.getBlock().getExplosionResistance() < 600.0F;
    }

    /** Empties chests etc. first so destroying them doesn't spray items everywhere. */
    private static void clearContents(ServerLevel level, BlockPos p, BlockState s) {
        if (s.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) {
                Clearable.tryClear(be);
            }
        }
    }

    public static void igniteAbove(ServerLevel level, BlockPos ground) {
        BlockPos up = ground.above();
        if (level.isOutsideBuildHeight(up) || !level.getBlockState(up).isAir()) {
            return;
        }
        BlockState fire = BaseFireBlock.getState(level, up);
        if (fire.canSurvive(level, up)) {
            level.setBlock(up, fire, Block.UPDATE_ALL);
        }
    }

    public static BlockState crustBlock(RandomSource rand) {
        return rand.nextFloat() < 0.7F ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState();
    }
}
