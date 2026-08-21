package me.justbecause.distantdecorations.network.s2c;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record S2CRegionSnapshot(
    int regionX,
    int regionZ,
    long revision,
    int partIndex,
    int partCount,
    List<DecorationRecord> records
) implements CustomPacketPayload {
    public static final Type<S2CRegionSnapshot> TYPE = new Type<>(DistantDecorations.id("region_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRegionSnapshot> CODEC = StreamCodec.of(
        (buf, packet) -> {
            buf.writeVarInt(packet.regionX);
            buf.writeVarInt(packet.regionZ);
            buf.writeVarLong(packet.revision);
            buf.writeVarInt(packet.partIndex);
            buf.writeVarInt(packet.partCount);
            buf.writeVarInt(packet.records.size());
            for (DecorationRecord record : packet.records) {
                record.writeToNetwork(buf);
            }
        },
        buf -> {
            int rx = buf.readVarInt();
            int rz = buf.readVarInt();
            long rev = buf.readVarLong();
            int partIndex = buf.readVarInt();
            int partCount = buf.readVarInt();
            int count = buf.readVarInt();
            List<DecorationRecord> records = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                records.add(DecorationRecord.readFromNetwork(buf));
            }
            return new S2CRegionSnapshot(rx, rz, rev, partIndex, partCount, records);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
