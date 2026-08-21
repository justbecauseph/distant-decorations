package me.justbecause.distantdecorations.client.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientDecorationRegion {
    public static final int CHUNKS_PER_REGION_AXIS = 32;
    public static final int CELLS_PER_REGION_AXIS = CHUNKS_PER_REGION_AXIS / DecorationRenderCell.CHUNKS_PER_CELL_AXIS; // 8 cells
    public static final int BLOCKS_PER_REGION_AXIS = CHUNKS_PER_REGION_AXIS * 16; // 512 blocks

    private final int regionX;
    private final int regionZ;
    private final DecorationRenderCell[] cells = new DecorationRenderCell[CELLS_PER_REGION_AXIS * CELLS_PER_REGION_AXIS];
    private final Map<DecorationId, ClientDecoration> allDecorations = new ConcurrentHashMap<>();
    private volatile long revision;
    private volatile @Nullable AABB dynamicBounds = null;

    public ClientDecorationRegion(int regionX, int regionZ, long revision) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.revision = revision;

        int baseCellX = regionX * CELLS_PER_REGION_AXIS;
        int baseCellZ = regionZ * CELLS_PER_REGION_AXIS;
        for (int cz = 0; cz < CELLS_PER_REGION_AXIS; cz++) {
            for (int cx = 0; cx < CELLS_PER_REGION_AXIS; cx++) {
                int index = cz * CELLS_PER_REGION_AXIS + cx;
                cells[index] = new DecorationRenderCell(baseCellX + cx, baseCellZ + cz);
            }
        }
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public long revision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    @Nullable
    public AABB bounds() {
        return dynamicBounds;
    }

    public int size() {
        return allDecorations.size();
    }

    public boolean isEmpty() {
        return allDecorations.isEmpty();
    }

    public Collection<ClientDecoration> getAllDecorations() {
        return allDecorations.values();
    }

    public Collection<DecorationRecord> getAllRecords() {
        List<DecorationRecord> list = new ArrayList<>(allDecorations.size());
        for (ClientDecoration deco : allDecorations.values()) {
            list.add(deco.record());
        }
        return list;
    }

    public DecorationRenderCell[] getCells() {
        return cells;
    }

    public DecorationRenderCell getCellForBlock(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        int cellX = Math.floorMod(chunkX >> 2, CELLS_PER_REGION_AXIS);
        int cellZ = Math.floorMod(chunkZ >> 2, CELLS_PER_REGION_AXIS);
        return cells[cellZ * CELLS_PER_REGION_AXIS + cellX];
    }

    public void addOrUpdate(DecorationRecord record) {
        ClientDecoration deco = new ClientDecoration(record);
        allDecorations.put(record.id(), deco);
        getCellForBlock(record.id().anchor().getX(), record.id().anchor().getZ()).addOrUpdate(deco);
        recalculateBounds();
    }

    public void remove(DecorationId id) {
        ClientDecoration existing = allDecorations.remove(id);
        if (existing != null) {
            getCellForBlock(id.anchor().getX(), id.anchor().getZ()).remove(id);
            recalculateBounds();
        }
    }

    public void clear() {
        allDecorations.clear();
        for (DecorationRenderCell cell : cells) {
            cell.clear();
        }
        dynamicBounds = null;
    }

    public void recalculateBounds() {
        if (allDecorations.isEmpty()) {
            dynamicBounds = null;
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (DecorationRenderCell cell : cells) {
            if (!cell.isEmpty()) {
                AABB cb = cell.getBounds();
                if (cb.minX < minX) minX = cb.minX;
                if (cb.minY < minY) minY = cb.minY;
                if (cb.minZ < minZ) minZ = cb.minZ;
                if (cb.maxX > maxX) maxX = cb.maxX;
                if (cb.maxY > maxY) maxY = cb.maxY;
                if (cb.maxZ > maxZ) maxZ = cb.maxZ;
            }
        }

        if (Double.isInfinite(minX)) {
            dynamicBounds = null;
        } else {
            dynamicBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
