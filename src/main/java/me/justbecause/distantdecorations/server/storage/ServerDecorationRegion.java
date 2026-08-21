package me.justbecause.distantdecorations.server.storage;

import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerDecorationRegion {
    public static final int MAGIC = 0x4445434F; // "DECO"
    public static final int FORMAT_VERSION = 1;

    private final int regionX;
    private final int regionZ;
    private final Map<DecorationId, DecorationRecord> records = new ConcurrentHashMap<>();
    private final Map<Long, Set<DecorationId>> chunkToDecorations = new ConcurrentHashMap<>();
    private volatile long revision = 0L;
    private volatile boolean dirty = false;

    public ServerDecorationRegion(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
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

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        this.dirty = false;
    }

    public int size() {
        return records.size();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public Collection<DecorationRecord> getAllRecords() {
        return Collections.unmodifiableCollection(records.values());
    }

    public DecorationRecord addOrUpdate(DecorationRecord record) {
        DecorationRecord previous = records.put(record.id(), record);
        BlockPos pos = record.id().anchor();
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        chunkToDecorations.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(record.id());

        this.revision++;
        this.dirty = true;
        return record;
    }

    public DecorationRecord remove(DecorationId id) {
        DecorationRecord removed = records.remove(id);
        if (removed != null) {
            BlockPos pos = id.anchor();
            long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            Set<DecorationId> set = chunkToDecorations.get(chunkKey);
            if (set != null) {
                set.remove(id);
                if (set.isEmpty()) {
                    chunkToDecorations.remove(chunkKey);
                }
            }
            this.revision++;
            this.dirty = true;
        }
        return removed;
    }

    public List<DecorationRecord> getDecorationsInChunk(int chunkX, int chunkZ) {
        long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        Set<DecorationId> ids = chunkToDecorations.get(chunkKey);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<DecorationRecord> result = new ArrayList<>(ids.size());
        for (DecorationId id : ids) {
            DecorationRecord record = records.get(id);
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }

    public void writeToStream(DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(regionX);
        out.writeInt(regionZ);
        out.writeLong(revision);
        out.writeInt(records.size());
        for (DecorationRecord record : records.values()) {
            record.writeToStream(out);
        }
    }

    public static ServerDecorationRegion readFromStream(DataInput in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid region file magic: 0x" + Integer.toHexString(magic));
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported region format version: " + version);
        }
        int rx = in.readInt();
        int rz = in.readInt();
        long rev = in.readLong();
        int count = in.readInt();

        ServerDecorationRegion region = new ServerDecorationRegion(rx, rz);
        region.setRevision(rev);

        for (int i = 0; i < count; i++) {
            DecorationRecord record = DecorationRecord.readFromStream(in);
            region.records.put(record.id(), record);
            BlockPos pos = record.id().anchor();
            long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            region.chunkToDecorations.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(record.id());
        }

        region.dirty = false;
        return region;
    }
}
