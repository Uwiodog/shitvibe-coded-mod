package com.volcanodisaster.volcano;

import com.volcanodisaster.VolcanoConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Everything volcanic in one dimension - volcanoes, clusters, geysers, running pyroclastic flows and ash-biome zones -
 * saved with the world so it all carries on after reloading.
 */
public class VolcanoData extends SavedData {
    private static final String NAME = "volcanodisaster_volcanoes";
    private static final int LOADED_CHUNKS_PER_TICK = 64;
    private static final TicketType<Long> CATCH_UP_TICKET = TicketType.create("volcanodisaster:catch_up", Long::compare);
    private static final int CATCH_UP_TIMEOUT = 20 * 20;

    /** Chunks that loaded since the last tick, per dimension (filled from the chunk-load event). */
    private static final Map<ResourceKey<Level>, Queue<Long>> JUST_LOADED = new ConcurrentHashMap<>();

    private final List<Volcano> volcanoes = new ArrayList<>();
    private final List<PyroclasticFlow> flows = new ArrayList<>();
    private final List<AshZone> ashZones = new ArrayList<>();
    private final List<VolcanoCluster> clusters = new ArrayList<>();
    private final List<Geyser> geysers = new ArrayList<>();
    private int nextId = 1;

    // Background catch-up (not saved): chunks being loaded so skipped flow/biome work can be done.
    private final List<long[]> catchUpJobs = new ArrayList<>();
    private final Set<Long> catchUpBusy = new HashSet<>();
    private double catchUpBudget;

    public static VolcanoData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(VolcanoData::load, VolcanoData::new, NAME);
    }

    public static void onChunkLoaded(ServerLevel level, ChunkPos pos) {
        JUST_LOADED.computeIfAbsent(level.dimension(), k -> new ConcurrentLinkedQueue<>()).add(pos.toLong());
    }

    public static void clearStatic() {
        JUST_LOADED.clear();
        RegionFiles.clear();
    }

    public List<Volcano> all() {
        return Collections.unmodifiableList(volcanoes);
    }

    /** @param strength 1-20, or below 1 for the configured default; @param days below 0 for the configured default */
    public Volcano spawn(ServerLevel level, BlockPos center, float strength, double days) {
        double d = days >= 0 ? days : VolcanoConfig.ERUPTION_DAYS.get();
        int eruptionTicks = (int) Math.round(d * Volcano.TICKS_PER_DAY);
        float s = strength >= 1.0F ? strength : VolcanoConfig.DEFAULT_INTENSITY.get();
        Volcano v = new Volcano(nextId++, center, level.getRandom().nextLong(), s, eruptionTicks,
                VolcanoConfig.CALDERA_RADIUS.get(), VolcanoConfig.CALDERA_DEPTH.get(),
                VolcanoConfig.CLEAR_HEIGHT.get(), VolcanoConfig.MAX_SPILL_DISTANCE.get(), 0, 0);
        volcanoes.add(v);
        int ashRadius = VolcanoConfig.ASH_BIOME_RADIUS.get();
        if (ashRadius > 0 && ashRadiusFor(center.getX(), center.getZ()) <= 0) {
            ashZones.add(new AshZone(center.getX(), center.getZ(), ashRadius));
        }
        setDirty();
        return v;
    }

    /** A vent belonging to a cluster (no ash zone of its own: the cluster has one big one). */
    Volcano spawnVent(ServerLevel level, BlockPos center, float strength, int eruptionTicks, int clusterId, int dormantTicks) {
        Volcano v = new Volcano(nextId++, center, level.getRandom().nextLong(), strength, eruptionTicks,
                VolcanoConfig.CALDERA_RADIUS.get(), VolcanoConfig.CALDERA_DEPTH.get(),
                VolcanoConfig.CLEAR_HEIGHT.get(), VolcanoConfig.MAX_SPILL_DISTANCE.get(), clusterId, dormantTicks);
        volcanoes.add(v);
        setDirty();
        return v;
    }

    Geyser addGeyser(int clusterId, int x, int y, int z, float strength, int lifeTicks, net.minecraft.util.RandomSource rand) {
        Geyser g = new Geyser(nextId++, clusterId, x, y, z, strength, lifeTicks, rand);
        geysers.add(g);
        setDirty();
        return g;
    }

    Geyser geyserById(int id) {
        for (Geyser g : geysers) {
            if (g.id == id) {
                return g;
            }
        }
        return null;
    }

    void removeGeysers(int clusterId) {
        geysers.removeIf(g -> g.clusterId == clusterId);
        setDirty();
    }

    /**
     * @param width   cluster width in blocks, or below 1 for the configured default
     * @param vents   number of vents, or below 1 for the configured/automatic count
     * @param days    how long new vents keep opening, below 0 for the configured default (0 = until stopped)
     */
    public VolcanoCluster spawnCluster(ServerLevel level, BlockPos center, int width, int vents, double days) {
        int w = width >= 1 ? width : VolcanoConfig.CLUSTER_WIDTH.get();
        int configured = VolcanoConfig.CLUSTER_VENTS.get();
        int n = vents >= 1 ? vents : configured > 0 ? configured : VolcanoCluster.autoVentCount(w);
        double d = days >= 0 ? days : VolcanoConfig.CLUSTER_DAYS.get();
        VolcanoCluster c = new VolcanoCluster(nextId++, center, w, n, (int) Math.round(d * Volcano.TICKS_PER_DAY),
                VolcanoConfig.CLUSTER_INTENSITY.get(), level.getRandom().nextLong());
        clusters.add(c);
        // One big Ashfall area covering the whole cluster.
        int ashMargin = VolcanoConfig.ASH_BIOME_RADIUS.get();
        if (ashMargin > 0) {
            ashZones.add(new AshZone(center.getX(), center.getZ(), w / 2 + ashMargin / 2));
        }
        setDirty();
        return c;
    }

    public List<VolcanoCluster> allClusters() {
        return Collections.unmodifiableList(clusters);
    }

    public VolcanoCluster clusterById(int id) {
        for (VolcanoCluster c : clusters) {
            if (c.id == id) {
                return c;
            }
        }
        return null;
    }

    public VolcanoCluster nearestCluster(BlockPos pos) {
        VolcanoCluster best = null;
        double bestSq = Double.MAX_VALUE;
        for (VolcanoCluster c : clusters) {
            double d = c.getCenter().distSqr(pos);
            if (d < bestSq) {
                bestSq = d;
                best = c;
            }
        }
        return best;
    }

    /** Activity multiplier of a cluster (1 if it's gone). */
    double clusterFactor(int clusterId) {
        VolcanoCluster c = clusterById(clusterId);
        return c != null ? c.factor() : 1.0;
    }

    public void setClusterActivity(VolcanoCluster c, int value) {
        c.setActivity(value);
        setDirty();
    }

    public void startShower(ServerLevel level, VolcanoCluster c) {
        c.startShower(level);
        setDirty();
    }

    public boolean startGreatFlow(ServerLevel level, VolcanoCluster c) {
        boolean ok = c.triggerGreatFlow(level, this);
        setDirty();
        return ok;
    }

    public void stopCluster(ServerLevel level, VolcanoCluster c) {
        c.stop(level, this);
        setDirty();
    }

    /** Removes the cluster and all its vents and geysers immediately (lava stays as it is). */
    public void removeCluster(ServerLevel level, VolcanoCluster c) {
        c.stop(level, this);
        for (Volcano v : new ArrayList<>(volcanoes)) {
            if (v.clusterId == c.id) {
                remove(level, v);
            }
        }
        clusters.remove(c);
        setDirty();
    }

    public String clusterSummary(VolcanoCluster c) {
        return c.summary(this);
    }

    public List<Volcano> clusterVents(VolcanoCluster c, BlockPos from) {
        return c.ventsByDistance(this, from);
    }

    boolean hasActiveGreatFlow(int clusterId) {
        for (PyroclasticFlow f : flows) {
            if (f.clusterId == clusterId && f.stage != PyroclasticFlow.Stage.DONE) {
                return true;
            }
        }
        return false;
    }

    public Volcano byId(int id) {
        for (Volcano v : volcanoes) {
            if (v.id == id) {
                return v;
            }
        }
        return null;
    }

    public Volcano nearest(BlockPos pos) {
        Volcano best = null;
        double bestSq = Double.MAX_VALUE;
        for (Volcano v : volcanoes) {
            double d = v.getCenter().distSqr(pos);
            if (d < bestSq) {
                bestSq = d;
                best = v;
            }
        }
        return best;
    }

    /** Deletes a volcano straight away, leaving whatever lava is there as it is. */
    public void remove(ServerLevel level, Volcano v) {
        v.releaseTicket(level);
        volcanoes.remove(v);
        setDirty();
    }

    void addFlow(PyroclasticFlow flow) {
        flows.add(flow);
        setDirty();
    }

    boolean hasActiveFlow(int volcanoId) {
        for (PyroclasticFlow f : flows) {
            if (f.volcanoId == volcanoId && f.stage != PyroclasticFlow.Stage.DONE) {
                return true;
            }
        }
        return false;
    }

    /** Radius of the ash zone centred at this volcano, or 0 if there is none. */
    int ashRadiusFor(int x, int z) {
        for (AshZone zone : ashZones) {
            if (zone.cx == x && zone.cz == z) {
                return zone.radius;
            }
        }
        return 0;
    }

    public int clearAshZones() {
        int n = ashZones.size();
        ashZones.clear();
        setDirty();
        return n;
    }

    public void tick(ServerLevel level) {
        List<ChunkAccess> biomeChanged = new ArrayList<>();
        handleLoadedChunks(level, biomeChanged);

        if (volcanoes.isEmpty() && flows.isEmpty() && ashZones.isEmpty() && clusters.isEmpty() && geysers.isEmpty()
                && catchUpJobs.isEmpty()) {
            return;
        }

        for (VolcanoCluster c : new ArrayList<>(clusters)) {
            c.tick(level, this);
        }
        clusters.removeIf(VolcanoCluster::isFinished);
        for (Geyser g : geysers) {
            g.tick(level);
        }
        geysers.removeIf(Geyser::isFinished);

        for (AshZone zone : ashZones) {
            zone.sweep(level, biomeChanged);
        }

        Iterator<Volcano> it = volcanoes.iterator();
        while (it.hasNext()) {
            Volcano v = it.next();
            v.tick(level, this);
            if (v.stage == Volcano.Stage.DONE) {
                v.releaseTicket(level);
                it.remove();
            }
        }

        // Copy: a flow never adds flows, but stay safe against changes while iterating.
        for (PyroclasticFlow f : new ArrayList<>(flows)) {
            f.tick(level);
        }
        flows.removeIf(PyroclasticFlow::isFinished);

        tickCatchUp(level, biomeChanged);

        if (!biomeChanged.isEmpty()) {
            level.getChunkSource().chunkMap.resendBiomesForChunks(biomeChanged);
        }
        setDirty();
    }

    /**
     * Pyroclastic flows and the Ashfall biome only change chunks that are loaded at the time; the
     * rest are remembered. This quietly loads those leftover chunks a few at a time (only ones that
     * already exist in the save), finishes them off, and lets them unload again, so the damage
     * fills in completely instead of leaving untouched patches.
     */
    private void tickCatchUp(ServerLevel level, List<ChunkAccess> biomeChanged) {
        Iterator<long[]> it = catchUpJobs.iterator();
        while (it.hasNext()) {
            long[] job = it.next();
            job[1]++;
            int x = ChunkPos.getX(job[0]);
            int z = ChunkPos.getZ(job[0]);
            boolean ready = true;
            for (int dx = -1; dx <= 1 && ready; dx++) {
                for (int dz = -1; dz <= 1 && ready; dz++) {
                    ready = level.getChunkSource().getChunkNow(x + dx, z + dz) != null;
                }
            }
            if (ready) {
                applyChunk(level, x, z, job[0], biomeChanged);
            }
            if (ready || job[1] > CATCH_UP_TIMEOUT) {
                ChunkPos cp = new ChunkPos(x, z);
                level.getChunkSource().removeRegionTicket(CATCH_UP_TICKET, cp, 1, job[0]);
                catchUpBusy.remove(job[0]);
                it.remove();
            }
        }

        double perSecond = VolcanoConfig.CATCH_UP_CHUNKS_PER_SECOND.get();
        if (perSecond <= 0) {
            return;
        }
        int maxJobs = Math.max(2, (int) Math.ceil(perSecond * 2));
        catchUpBudget = Math.min(catchUpBudget + perSecond / 20.0, maxJobs);
        while (catchUpBudget >= 1.0 && catchUpJobs.size() < maxJobs) {
            Long next = nextCatchUpChunk(level);
            if (next == null) {
                catchUpBudget = 0;
                break;
            }
            catchUpBudget -= 1.0;
            ChunkPos cp = new ChunkPos(ChunkPos.getX(next), ChunkPos.getZ(next));
            level.getChunkSource().addRegionTicket(CATCH_UP_TICKET, cp, 1, next);
            catchUpBusy.add(next);
            catchUpJobs.add(new long[]{next, 0});
        }
    }

    private Long nextCatchUpChunk(ServerLevel level) {
        for (PyroclasticFlow f : flows) {
            if (f.stage == PyroclasticFlow.Stage.DONE) { // once the front has gone all the way
                Long key = f.nextMissed(level, catchUpBusy);
                if (key != null) {
                    return key;
                }
            }
        }
        for (AshZone zone : ashZones) {
            Long key = zone.nextCatchUp(level, catchUpBusy);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private void applyChunk(ServerLevel level, int chunkX, int chunkZ, long key, List<ChunkAccess> biomeChanged) {
        for (AshZone zone : ashZones) {
            zone.convertIfLoaded(level, chunkX, chunkZ, biomeChanged);
        }
        for (PyroclasticFlow f : flows) {
            if (f.hasMissed(key)) {
                f.applyMissedChunk(level, chunkX, chunkZ);
            }
        }
    }

    /** Chunks that just loaded catch up on anything that happened while they were unloaded. */
    private void handleLoadedChunks(ServerLevel level, List<ChunkAccess> biomeChanged) {
        Queue<Long> queue = JUST_LOADED.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        if (flows.isEmpty() && ashZones.isEmpty()) {
            queue.clear();
            return;
        }
        for (int i = 0; i < LOADED_CHUNKS_PER_TICK; i++) {
            Long key = queue.poll();
            if (key == null) {
                break;
            }
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                continue;
            }
            applyChunk(level, chunkX, chunkZ, key, biomeChanged);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Volcano v : volcanoes) {
            list.add(v.save());
        }
        tag.put("Volcanoes", list);
        ListTag flowList = new ListTag();
        for (PyroclasticFlow f : flows) {
            flowList.add(f.save());
        }
        tag.put("Flows", flowList);
        ListTag zoneList = new ListTag();
        for (AshZone z : ashZones) {
            zoneList.add(z.save());
        }
        tag.put("AshZones", zoneList);
        ListTag clusterList = new ListTag();
        for (VolcanoCluster c : clusters) {
            clusterList.add(c.save());
        }
        tag.put("Clusters", clusterList);
        ListTag geyserList = new ListTag();
        for (Geyser g : geysers) {
            geyserList.add(g.save());
        }
        tag.put("Geysers", geyserList);
        tag.putInt("NextId", nextId);
        return tag;
    }

    public static VolcanoData load(CompoundTag tag) {
        VolcanoData data = new VolcanoData();
        ListTag list = tag.getList("Volcanoes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.volcanoes.add(Volcano.load(list.getCompound(i)));
        }
        ListTag flowList = tag.getList("Flows", Tag.TAG_COMPOUND);
        for (int i = 0; i < flowList.size(); i++) {
            data.flows.add(PyroclasticFlow.load(flowList.getCompound(i)));
        }
        ListTag zoneList = tag.getList("AshZones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zoneList.size(); i++) {
            data.ashZones.add(AshZone.load(zoneList.getCompound(i)));
        }
        ListTag clusterList = tag.getList("Clusters", Tag.TAG_COMPOUND);
        for (int i = 0; i < clusterList.size(); i++) {
            data.clusters.add(VolcanoCluster.load(clusterList.getCompound(i)));
        }
        ListTag geyserList = tag.getList("Geysers", Tag.TAG_COMPOUND);
        for (int i = 0; i < geyserList.size(); i++) {
            data.geysers.add(Geyser.load(geyserList.getCompound(i)));
        }
        data.nextId = Math.max(1, tag.getInt("NextId"));
        return data;
    }
}
