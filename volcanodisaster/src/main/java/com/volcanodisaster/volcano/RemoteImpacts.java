package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Fire bombs and meteors that land where no player is nearby. Their chunk is loaded with a
 * ticket, the hit is applied to the real blocks, and the chunk is kept ticking for a while so
 * the fires keep burning; then the ticket is dropped and the chunk unloads (and saves).
 */
public final class RemoteImpacts {
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("volcanodisaster:impact", Comparator.comparingLong(ChunkPos::toLong));
    /** Ticket distance 1: the hit chunk ticks (fire spreads, lava flows), its neighbours are loaded. */
    private static final int TICKET_DISTANCE = 1;
    private static final int MAX_QUEUE = 512;
    private static final int GIVE_UP_TICKS = 20 * 30;
    private static final int MAX_HITS_PER_TICK = 6;

    private static final Map<ResourceKey<Level>, State> STATES = new HashMap<>();

    private static final class Pending {
        final double x;
        final double z;
        final boolean meteor;
        int waited;

        Pending(double x, double z, boolean meteor) {
            this.x = x;
            this.z = z;
            this.meteor = meteor;
        }
    }

    private static final class State {
        final ArrayDeque<Pending> queue = new ArrayDeque<>();
        /** chunk pos (as long) -> game time when its ticket should be removed. */
        final Map<Long, Long> tickets = new HashMap<>();
    }

    private RemoteImpacts() {}

    public static void add(ServerLevel level, double x, double z, boolean meteor) {
        State state = STATES.computeIfAbsent(level.dimension(), k -> new State());
        if (state.queue.size() >= MAX_QUEUE) {
            return;
        }
        state.queue.add(new Pending(x, z, meteor));
    }

    public static void tick(ServerLevel level) {
        State state = STATES.get(level.dimension());
        if (state == null) {
            return;
        }
        long now = level.getGameTime();
        long burnTicks = VolcanoConfig.REMOTE_BURN_SECONDS.get() * 20L;
        int maxChunks = VolcanoConfig.MAX_REMOTE_CHUNKS.get();

        int hits = 0;
        int toCheck = state.queue.size();
        for (int i = 0; i < toCheck && hits < MAX_HITS_PER_TICK; i++) {
            Pending p = state.queue.poll();
            if (p == null) {
                break;
            }
            ChunkPos cp = new ChunkPos(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4);
            long key = cp.toLong();
            Long expiry = state.tickets.get(key);
            if (expiry == null) {
                if (state.tickets.size() >= maxChunks) {
                    requeue(state, p);
                    continue;
                }
                level.getChunkSource().addRegionTicket(TICKET, cp, TICKET_DISTANCE, cp, true);
                state.tickets.put(key, now + burnTicks);
            } else if (expiry < now + burnTicks) {
                state.tickets.put(key, now + burnTicks);
            }

            if (isReady(level, cp)) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z));
                if (y > level.getMinBuildHeight()) {
                    Impacts.impact(level, p.x, y, p.z, p.meteor, level.getRandom());
                }
                hits++;
            } else {
                requeue(state, p);
            }
        }

        Iterator<Map.Entry<Long, Long>> it = state.tickets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (e.getValue() <= now) {
                ChunkPos cp = new ChunkPos(e.getKey());
                level.getChunkSource().removeRegionTicket(TICKET, cp, TICKET_DISTANCE, cp, true);
                it.remove();
            }
        }
    }

    private static void requeue(State state, Pending p) {
        if (++p.waited < GIVE_UP_TICKS) {
            state.queue.add(p);
        }
    }

    /** The hit chunk and all 8 neighbours must be fully loaded so the crater never forces a sync load. */
    private static boolean isReady(ServerLevel level, ChunkPos cp) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cp.x + dx, cp.z + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Is (x, z) somewhere a flying projectile would actually move? */
    public static boolean isLive(ServerLevel level, double x, double z) {
        return level.isPositionEntityTicking(BlockPos.containing(x, 0, z));
    }

    public static void clearAll() {
        STATES.clear();
    }
}
