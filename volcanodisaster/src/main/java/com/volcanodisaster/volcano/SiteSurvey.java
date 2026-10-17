package com.volcanodisaster.volcano;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Looks at the ground around a possible vent site: how high the ground really is (under the streets,
 * not on the rooftops), and whether it's mostly water.
 */
public final class SiteSurvey {
    /** Sample grid: 7 x 7 columns, 4 blocks apart, so 24 x 24 blocks around the site. */
    private static final int STEPS = 3;
    private static final int SPACING = 4;
    public static final int REACH = STEPS * SPACING;

    public enum Kind { LAND, WATER, INVALID }

    public final Kind kind;
    /** Ground level for LAND sites, water surface for WATER sites. */
    public final int y;

    private SiteSurvey(Kind kind, int y) {
        this.kind = kind;
        this.y = y;
    }

    /** All chunks within {@link #REACH} of (x, z) must be loaded. */
    public static SiteSurvey survey(ServerLevel level, int x, int z) {
        List<Integer> ground = new ArrayList<>();
        List<Integer> water = new ArrayList<>();
        int voids = 0;
        int total = 0;
        for (int i = -STEPS; i <= STEPS; i++) {
            for (int j = -STEPS; j <= STEPS; j++) {
                int sx = x + i * SPACING;
                int sz = z + j * SPACING;
                total++;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz) - 1;
                if (top <= level.getMinBuildHeight() + 1) {
                    voids++;
                    continue;
                }
                BlockState s = level.getBlockState(new BlockPos(sx, top, sz));
                if (s.getFluidState().is(FluidTags.WATER)) {
                    water.add(top);
                } else {
                    ground.add(top);
                }
            }
        }
        if (voids * 2 > total) {
            return new SiteSurvey(Kind.INVALID, 0); // edge of the map / void
        }
        if (water.size() * 2 >= total) {
            Collections.sort(water);
            return new SiteSurvey(Kind.WATER, water.get(water.size() / 2));
        }
        if (ground.isEmpty()) {
            return new SiteSurvey(Kind.INVALID, 0);
        }
        // Buildings stick up; streets, yards and plazas are the low readings. The 20th percentile
        // is the street level even in a dense city block.
        Collections.sort(ground);
        return new SiteSurvey(Kind.LAND, ground.get(Mth.floor((ground.size() - 1) * 0.2)));
    }

    /**
     * Keeps vents on (roughly) the same plane: ground within the tolerance is used as is, ground a
     * bit further off is clamped to the edge of the band (the caldera gets cut into the slope),
     * and anything more than twice the tolerance away returns null (skip this site).
     */
    public static Integer planeY(int ground, int baseY, int tolerance) {
        int d = ground - baseY;
        if (Math.abs(d) > tolerance * 2) {
            return null;
        }
        return baseY + Mth.clamp(d, -tolerance, tolerance);
    }
}
