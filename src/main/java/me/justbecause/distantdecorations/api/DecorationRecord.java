package me.justbecause.distantdecorations.api;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public record DecorationRecord(
    DecorationId id,
    AABB bounds,
    long revision,
    byte[] payload
) {
    public static final int MAX_PAYLOAD_BYTES = 16384; // 16 KiB ceiling across capture, storage, and networking

    public DecorationRecord {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(bounds, "bounds cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload size " + payload.length + " exceeds maximum allowed " + MAX_PAYLOAD_BYTES + " bytes");
        }
    }

    public BlockPos pos() {
        return id.anchor();
    }

    public void writeToNetwork(FriendlyByteBuf buf) {
        id.writeToNetwork(buf);
        buf.writeDouble(bounds.minX);
        buf.writeDouble(bounds.minY);
        buf.writeDouble(bounds.minZ);
        buf.writeDouble(bounds.maxX);
        buf.writeDouble(bounds.maxY);
        buf.writeDouble(bounds.maxZ);
        buf.writeVarLong(revision);
        buf.writeByteArray(payload);
    }

    public static DecorationRecord readFromNetwork(FriendlyByteBuf buf) {
        DecorationId id = DecorationId.readFromNetwork(buf);
        double minX = buf.readDouble();
        double minY = buf.readDouble();
        double minZ = buf.readDouble();
        double maxX = buf.readDouble();
        double maxY = buf.readDouble();
        double maxZ = buf.readDouble();
        AABB bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        long revision = buf.readVarLong();
        byte[] payload = buf.readByteArray(MAX_PAYLOAD_BYTES);
        return new DecorationRecord(id, bounds, revision, payload);
    }

    public void writeToStream(DataOutput out) throws IOException {
        id.writeToStream(out);
        out.writeDouble(bounds.minX);
        out.writeDouble(bounds.minY);
        out.writeDouble(bounds.minZ);
        out.writeDouble(bounds.maxX);
        out.writeDouble(bounds.maxY);
        out.writeDouble(bounds.maxZ);
        out.writeLong(revision);
        out.writeInt(payload.length);
        out.write(payload);
    }

    public static DecorationRecord readFromStream(DataInput in) throws IOException {
        DecorationId id = DecorationId.readFromStream(in);
        double minX = in.readDouble();
        double minY = in.readDouble();
        double minZ = in.readDouble();
        double maxX = in.readDouble();
        double maxY = in.readDouble();
        double maxZ = in.readDouble();
        AABB bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        long revision = in.readLong();
        int payloadLength = in.readInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid or corrupted decoration payload length: " + payloadLength + " (max allowed: " + MAX_PAYLOAD_BYTES + ")");
        }
        byte[] payload = new byte[payloadLength];
        in.readFully(payload);
        return new DecorationRecord(id, bounds, revision, payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DecorationRecord that = (DecorationRecord) o;
        return revision == that.revision &&
               id.equals(that.id) &&
               bounds.equals(that.bounds) &&
               Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, bounds, revision);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
