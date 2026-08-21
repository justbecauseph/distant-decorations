package me.justbecause.distantdecorations.client.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.world.phys.AABB;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DecorationRenderCell {
    public static final int CHUNKS_PER_CELL_AXIS = 4;
    public static final int BLOCKS_PER_CELL_AXIS = CHUNKS_PER_CELL_AXIS * 16; // 64 blocks

    private final int cellX;
    private final int cellZ;
    private final Map<DecorationId, DecorationRecord> records = new ConcurrentHashMap<>();
    private volatile AABB cellBounds;

    public DecorationRenderCell(int cellX, int cellZ) {
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.cellBounds = calculateDefaultBounds();
    }

    public int cellX() {
        return cellX;
    }

    public int cellZ() {
        return cellZ;
    }

    public AABB getBounds() {
        return cellBounds;
    }

    public Collection<DecorationRecord> getRecords() {
        return Collections.unmodifiableCollection(records.values());
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public int size() {
        return records.size();
    }

    public void addOrUpdate(DecorationRecord record) {
        records.put(record.id(), record);
        recalculateBounds();
    }

    public void remove(DecorationId id) {
        if (records.remove(id) != null) {
            recalculateBounds();
        }
    }

    public void clear() {
        records.clear();
        this.cellBounds = calculateDefaultBounds();
    }

    private AABB calculateDefaultBounds() {
        double minX = (double) (cellX * BLOCKS_PER_CELL_AXIS);
        double minZ = (double) (cellZ * BLOCKS_PER_CELL_AXIS);
        double maxX = minX + BLOCKS_PER_CELL_AXIS;
        double maxZ = minZ + BLOCKS_PER_CELL_AXIS;
        return new AABB(minX, -64.0, minZ, maxX, 320.0, maxZ);
    }

    private void recalculateBounds() {
        if (records.isEmpty()) {
            this.cellBounds = calculateDefaultBounds();
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (DecorationRecord record : records.values()) {
            AABB b = record.bounds();
            if (b.minX < minX) minX = b.minX;
            if (b.minY < minY) minY = b.minY;
            if (b.minZ < minZ) minZ = b.minZ;
            if (b.maxX > maxX) maxX = b.maxX;
            if (b.maxY > maxY) maxY = b.maxY;
            if (b.maxZ > maxZ) maxZ = b.maxZ;
        }

        this.cellBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
