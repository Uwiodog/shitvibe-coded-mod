package com.volcanodisaster.volcano;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A disc around a volcano that is repainted as the Ashfall biome. Loaded chunks are converted a few
 * at a time; chunks that aren't loaded yet are converted the moment they load. Kept forever, so the
 * land stays ash-covered after the volcano is gone.
 */
public class AshZone {
    private static final int SWEEP_PER_TICK = 40;
    private static final int MAX_CONVERT_PER_TICK = 8;

    final int cx;
    final int cz;
    final int radius;
    private final Set<Long> done = new HashSet<>();
    private int sweepIndex;
    /** Position of the background catch-up scan (chunks that weren't loaded during the sweep). */
    private int catchIndex;

    AshZone(int cx, int cz, int radius) {
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
    }

    boolean touches(int chunkX, int chunkZ) {
        // Closest point of the chunk to the centre.
        int nx = Math.max(chunkX << 4, Math.min(cx, (chunkX << 4) + 15));
        int nz = Math.max(chunkZ << 4, Math.min(cz, (chunkZ << 4) + 15));
        long dx = nx - cx;
        long dz = nz - cz;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    boolean contains(int x, int z) {
        long dx = x - cx;
        long dz = z - cz;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    /** Converts loaded chunks that haven't been done yet, a few per tick. */
    void sweep(ServerLevel level, List<ChunkAccess> changed) {
        int rc = (radius >> 4) + 1;
        int side = 2 * rc + 1;
        int total = side * side;
        if (sweepIndex >= total) {
            return;
        }
        int converted = 0;
        for (int i = 0; i < SWEEP_PER_TICK && sweepIndex < total && converted < MAX_CONVERT_PER_TICK; i++, sweepIndex++) {
            int chunkX = (cx >> 4) + sweepIndex % side - rc;
            int chunkZ = (cz >> 4) + sweepIndex / side - rc;
            if (convertIfLoaded(level, chunkX, chunkZ, changed)) {
                converted++;
            }
        }
    }

    /**
     * Next chunk in the zone that still isn't Ashfall and exists in the save (so loading it won't
     * generate new terrain), or null if there's nothing left. Chunks that were never generated are
     * skipped; if they get generated later they're converted as they load.
     */
    Long nextCatchUp(ServerLevel level, Set<Long> busy) {
        int rc = (radius >> 4) + 1;
        int side = 2 * rc + 1;
        int total = side * side;
        if (sweepIndex < total) {
            return null; // let the sweep of loaded chunks finish first
        }
        for (int checks = 0; checks < 256 && catchIndex < total; checks++, catchIndex++) {
            int chunkX = (cx >> 4) + catchIndex % side - rc;
            int chunkZ = (cz >> 4) + catchIndex / side - rc;
            long key = ChunkPos.asLong(chunkX, chunkZ);
            if (done.contains(key) || busy.contains(key) || !touches(chunkX, chunkZ)) {
                continue;
            }
            if (!RegionFiles.chunkExists(level, chunkX, chunkZ)) {
                continue;
            }
            catchIndex++;
            return key;
        }
        return null;
    }

    boolean isDone(long key) {
        return done.contains(key);
    }

    /** Called for chunks that just loaded. */
    boolean convertIfLoaded(ServerLevel level, int chunkX, int chunkZ, List<ChunkAccess> changed) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (done.contains(key) || !touches(chunkX, chunkZ)) {
            return false;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        Optional<? extends Holder<Biome>> ash = level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(ModBiomes.ASHFALL);
        if (ash.isEmpty()) {
            return false; // biome data didn't load; nothing we can do
        }
        Holder<Biome> ashBiome = ash.get();
        BiomeResolver resolver = (qx, qy, qz, sampler) ->
                contains(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2) ? ashBiome : chunk.getNoiseBiome(qx, qy, qz);
        chunk.fillBiomesFromNoise(resolver, level.getChunkSource().randomState().sampler());
        chunk.setUnsaved(true);
        changed.add(chunk);
        done.add(key);
        return true;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", cx);
        tag.putInt("Z", cz);
        tag.putInt("Radius", radius);
        tag.putInt("Sweep", sweepIndex);
        tag.putInt("Catch", catchIndex);
        long[] arr = new long[done.size()];
        int i = 0;
        for (long l : done) {
            arr[i++] = l;
        }
        tag.putLongArray("Done", arr);
        return tag;
    }

    static AshZone load(CompoundTag tag) {
        AshZone z = new AshZone(tag.getInt("X"), tag.getInt("Z"), tag.getInt("Radius"));
        z.sweepIndex = tag.getInt("Sweep");
        z.catchIndex = tag.getInt("Catch");
        for (long l : tag.getLongArray("Done")) {
            z.done.add(l);
        }
        return z;
    }
}
