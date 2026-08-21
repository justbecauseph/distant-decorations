package me.justbecause.distantdecorations.client.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientDecorationWorld {
    private final ResourceKey<Level> dimension;
    private final Map<Long, ClientDecorationRegion> regions = new ConcurrentHashMap<>();
    private final Map<Long, PendingSnapshot> pendingSnapshots = new ConcurrentHashMap<>();

    private record PendingDelta(long revision, List<DecorationRecord> additions, List<DecorationId> removals) {}

    private static final class PendingSnapshot {
        final int regionX;
        final int regionZ;
        final long revision;
        final int partCount;
        final Map<Integer, List<DecorationRecord>> parts = new ConcurrentHashMap<>();
        final List<PendingDelta> bufferedDeltas = Collections.synchronizedList(new ArrayList<>());

        PendingSnapshot(int regionX, int regionZ, long revision, int partCount) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.revision = revision;
            this.partCount = partCount;
        }

        boolean isComplete() {
            return parts.size() == partCount;
        }
    }

    public ClientDecorationWorld(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public static long packRegionKey(int regionX, int regionZ) {
        return (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
    }

    @Nullable
    public ClientDecorationRegion getRegion(int regionX, int regionZ) {
        return regions.get(packRegionKey(regionX, regionZ));
    }

    public ClientDecorationRegion getOrCreateRegion(int regionX, int regionZ, long revision) {
        return regions.computeIfAbsent(packRegionKey(regionX, regionZ), k -> new ClientDecorationRegion(regionX, regionZ, revision));
    }

    public void putSnapshotPart(int regionX, int regionZ, long revision, int partIndex, int partCount, List<DecorationRecord> records) {
        long key = packRegionKey(regionX, regionZ);
        ClientDecorationRegion currentRegion = regions.get(key);
        if (currentRegion != null && currentRegion.revision() > revision) {
            // Outdated snapshot, ignore
            return;
        }

        if (partCount <= 1) {
            ClientDecorationRegion region = new ClientDecorationRegion(regionX, regionZ, revision);
            region.putBulk(records);

            PendingSnapshot oldPending = pendingSnapshots.remove(key);
            if (oldPending != null) {
                synchronized (oldPending.bufferedDeltas) {
                    oldPending.bufferedDeltas.sort(Comparator.comparingLong(PendingDelta::revision));
                    for (PendingDelta d : oldPending.bufferedDeltas) {
                        if (d.revision() > region.revision()) {
                            region.setRevision(d.revision());
                            for (DecorationRecord add : d.additions()) region.addOrUpdate(add);
                            for (DecorationId rem : d.removals()) region.remove(rem);
                        }
                    }
                }
            }

            regions.put(key, region);
            return;
        }

        PendingSnapshot pending = pendingSnapshots.compute(key, (k, existing) -> {
            if (existing == null || existing.revision < revision) {
                existing = new PendingSnapshot(regionX, regionZ, revision, partCount);
            }
            if (existing.revision == revision) {
                existing.parts.put(partIndex, records);
            }
            return existing;
        });

        if (pending != null && pending.isComplete() && pending.revision == revision) {
            List<DecorationRecord> allRecords = new ArrayList<>();
            for (int i = 0; i < pending.partCount; i++) {
                List<DecorationRecord> partRecords = pending.parts.get(i);
                if (partRecords != null) {
                    allRecords.addAll(partRecords);
                }
            }

            ClientDecorationRegion region = new ClientDecorationRegion(regionX, regionZ, revision);
            region.putBulk(allRecords);

            // Replay any buffered deltas that arrived while assembling parts
            synchronized (pending.bufferedDeltas) {
                pending.bufferedDeltas.sort(Comparator.comparingLong(PendingDelta::revision));
                for (PendingDelta d : pending.bufferedDeltas) {
                    if (d.revision() > region.revision()) {
                        region.setRevision(d.revision());
                        for (DecorationRecord add : d.additions()) region.addOrUpdate(add);
                        for (DecorationId rem : d.removals()) region.remove(rem);
                    }
                }
            }

            regions.put(key, region);
            pendingSnapshots.remove(key);
        }
    }

    public void putSnapshot(int regionX, int regionZ, long revision, List<DecorationRecord> records) {
        putSnapshotPart(regionX, regionZ, revision, 0, 1, records);
    }

    public void applyDelta(int regionX, int regionZ, long revision, List<DecorationRecord> additions, List<DecorationId> removals) {
        long key = packRegionKey(regionX, regionZ);
        PendingSnapshot pending = pendingSnapshots.get(key);
        if (pending != null && pending.revision <= revision) {
            // Snapshot assembly in progress! Buffer delta to apply after snapshot is complete.
            pending.bufferedDeltas.add(new PendingDelta(revision, additions, removals));
            return;
        }

        ClientDecorationRegion region = regions.get(key);
        if (region == null) {
            // Region is not yet loaded / no snapshot received yet.
            // Do not synthesize an empty region with missing baseline.
            return;
        }

        if (revision <= region.revision()) {
            // Stale delta, ignore
            return;
        }

        region.setRevision(revision);
        for (DecorationRecord record : additions) {
            region.addOrUpdate(record);
        }
        for (DecorationId id : removals) {
            region.remove(id);
        }
    }

    public void unloadRegion(int regionX, int regionZ) {
        long key = packRegionKey(regionX, regionZ);
        regions.remove(key);
        pendingSnapshots.remove(key);
    }

    public void clear() {
        regions.clear();
        pendingSnapshots.clear();
    }

    public Collection<ClientDecorationRegion> getAllRegions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public int getTotalDecorationsCount() {
        int total = 0;
        for (ClientDecorationRegion region : regions.values()) {
            total += region.size();
        }
        return total;
    }
}


