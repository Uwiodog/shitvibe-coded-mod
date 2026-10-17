package com.volcanodisaster.volcano;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tells whether a chunk has ever been saved, by reading the region file's header, without loading
 * or generating anything. Used so background work never generates new terrain beyond the edge of a
 * map (for example into the void around a downloaded city).
 */
public final class RegionFiles {
    private static final long CACHE_TICKS = 20 * 30;
    private static final Map<String, Header> CACHE = new HashMap<>();

    private record Header(int[] offsets, long readAt) {}

    private RegionFiles() {}

    public static boolean chunkExists(ServerLevel level, int chunkX, int chunkZ) {
        Path folder = regionFolder(level);
        if (folder == null) {
            return false;
        }
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        String key = level.dimension().location() + "/" + rx + "." + rz;
        long now = level.getGameTime();
        Header header = CACHE.get(key);
        if (header == null || now - header.readAt() > CACHE_TICKS) {
            header = new Header(readHeader(folder.resolve("r." + rx + "." + rz + ".mca")), now);
            CACHE.put(key, header);
        }
        if (header.offsets() == null) {
            return false;
        }
        return header.offsets()[(chunkX & 31) + (chunkZ & 31) * 32] != 0;
    }

    public static void clear() {
        CACHE.clear();
    }

    /** The 1024 location entries at the start of a region file (0 = chunk not saved). */
    private static int[] readHeader(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 4096) {
                return null;
            }
            byte[] bytes = new byte[4096];
            raf.readFully(bytes);
            int[] offsets = new int[1024];
            for (int i = 0; i < 1024; i++) {
                int b0 = bytes[i * 4] & 0xFF;
                int b1 = bytes[i * 4 + 1] & 0xFF;
                int b2 = bytes[i * 4 + 2] & 0xFF;
                int sectors = bytes[i * 4 + 3] & 0xFF;
                int sector = (b0 << 16) | (b1 << 8) | b2;
                offsets[i] = sector != 0 && sectors != 0 ? sector : 0;
            }
            return offsets;
        } catch (IOException e) {
            return null;
        }
    }

    /** Same layout Minecraft uses: the overworld at the save root, the Nether in DIM-1, the End in DIM1, others under dimensions/. */
    private static Path regionFolder(ServerLevel level) {
        if (level.getServer() == null) {
            return null;
        }
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dim;
        if (level.dimension() == Level.OVERWORLD) {
            dim = root;
        } else if (level.dimension() == Level.NETHER) {
            dim = root.resolve("DIM-1");
        } else if (level.dimension() == Level.END) {
            dim = root.resolve("DIM1");
        } else {
            ResourceLocation id = level.dimension().location();
            dim = root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        }
        return dim.resolve("region");
    }
}
