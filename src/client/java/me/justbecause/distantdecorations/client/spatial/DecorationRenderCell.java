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
    private final Map<DecorationId, ClientDecoration> decorations = new ConcurrentHashMap<>();
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

    public Collection<ClientDecoration> getDecorations() {
        return Collections.unmodifiableCollection(decorations.values());
    }

    public boolean isEmpty() {
        return decorations.isEmpty();
    }

    public int size() {
        return decorations.size();
    }

    public void addWithoutBoundsRecalc(ClientDecoration decoration) {
        decorations.put(decoration.id(), decoration);
    }

    public void addOrUpdate(DecorationRecord record) {
        decorations.put(record.id(), new ClientDecoration(record));
        recalculateBounds();
    }

    public void addOrUpdate(ClientDecoration decoration) {
        decorations.put(decoration.id(), decoration);
        recalculateBounds();
    }

    public void remove(DecorationId id) {
        if (decorations.remove(id) != null) {
            recalculateBounds();
        }
    }

    public void clear() {
        decorations.clear();
        this.cellBounds = calculateDefaultBounds();
    }

    private AABB calculateDefaultBounds() {
        double minX = (double) (cellX * BLOCKS_PER_CELL_AXIS);
        double minZ = (double) (cellZ * BLOCKS_PER_CELL_AXIS);
        double maxX = minX + BLOCKS_PER_CELL_AXIS;
        double maxZ = minZ + BLOCKS_PER_CELL_AXIS;
        return new AABB(minX, -64.0, minZ, maxX, 320.0, maxZ);
    }

    public void recalculateBounds() {
        if (decorations.isEmpty()) {
            this.cellBounds = calculateDefaultBounds();
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (ClientDecoration deco : decorations.values()) {
            AABB b = deco.bounds();
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

