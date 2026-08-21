package me.justbecause.distantdecorations.client.spatial;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.world.phys.AABB;

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
    private final Map<DecorationId, DecorationRecord> allRecords = new ConcurrentHashMap<>();
    private volatile long revision;
    private final AABB regionBounds;

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

        double minX = (double) (regionX * BLOCKS_PER_REGION_AXIS);
        double minZ = (double) (regionZ * BLOCKS_PER_REGION_AXIS);
        this.regionBounds = new AABB(minX, -64.0, minZ, minX + BLOCKS_PER_REGION_AXIS, 320.0, minZ + BLOCKS_PER_REGION_AXIS);
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

    public AABB bounds() {
        return regionBounds;
    }

    public int size() {
        return allRecords.size();
    }

    public boolean isEmpty() {
        return allRecords.isEmpty();
    }

    public Collection<DecorationRecord> getAllRecords() {
        return allRecords.values();
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
        allRecords.put(record.id(), record);
        getCellForBlock(record.id().anchor().getX(), record.id().anchor().getZ()).addOrUpdate(record);
    }

    public void remove(DecorationId id) {
        DecorationRecord existing = allRecords.remove(id);
        if (existing != null) {
            getCellForBlock(id.anchor().getX(), id.anchor().getZ()).remove(id);
        }
    }

    public void clear() {
        allRecords.clear();
        for (DecorationRenderCell cell : cells) {
            cell.clear();
        }
    }

    public List<DecorationRenderCell> getNonEmptyCells() {
        List<DecorationRenderCell> result = new ArrayList<>();
        for (DecorationRenderCell cell : cells) {
            if (!cell.isEmpty()) {
                result.add(cell);
            }
        }
        return result;
    }
}
