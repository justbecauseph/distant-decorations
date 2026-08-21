package me.justbecause.distantdecorations.client.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientDecorationWorld {
    private final ResourceKey<Level> dimension;
    private final Map<Long, ClientDecorationRegion> regions = new ConcurrentHashMap<>();

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

    public void putSnapshot(int regionX, int regionZ, long revision, List<DecorationRecord> records) {
        ClientDecorationRegion region = new ClientDecorationRegion(regionX, regionZ, revision);
        for (DecorationRecord record : records) {
            region.addOrUpdate(record);
        }
        regions.put(packRegionKey(regionX, regionZ), region);
    }

    public void applyDelta(int regionX, int regionZ, long revision, List<DecorationRecord> additions, List<DecorationId> removals) {
        ClientDecorationRegion region = getOrCreateRegion(regionX, regionZ, revision);
        region.setRevision(revision);
        for (DecorationRecord record : additions) {
            region.addOrUpdate(record);
        }
        for (DecorationId id : removals) {
            region.remove(id);
        }
    }

    public void unloadRegion(int regionX, int regionZ) {
        regions.remove(packRegionKey(regionX, regionZ));
    }

    public void clear() {
        regions.clear();
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
