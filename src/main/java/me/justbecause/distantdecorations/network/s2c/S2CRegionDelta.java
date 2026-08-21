package me.justbecause.distantdecorations.network.s2c;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationId;
import me.justbecause.distantdecorations.api.DecorationRecord;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record S2CRegionDelta(
    int regionX,
    int regionZ,
    long revision,
    List<DecorationRecord> additionsAndUpdates,
    List<DecorationId> removals
) implements CustomPacketPayload {
    public static final Type<S2CRegionDelta> TYPE = new Type<>(DistantDecorations.id("region_delta"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRegionDelta> CODEC = StreamCodec.of(
        (buf, packet) -> {
            buf.writeVarInt(packet.regionX);
            buf.writeVarInt(packet.regionZ);
            buf.writeVarLong(packet.revision);
            buf.writeVarInt(packet.additionsAndUpdates.size());
            for (DecorationRecord record : packet.additionsAndUpdates) {
                record.writeToNetwork(buf);
            }
            buf.writeVarInt(packet.removals.size());
            for (DecorationId id : packet.removals) {
                id.writeToNetwork(buf);
            }
        },
        buf -> {
            int rx = buf.readVarInt();
            int rz = buf.readVarInt();
            long rev = buf.readVarLong();
            int addCount = buf.readVarInt();
            List<DecorationRecord> adds = new ArrayList<>(addCount);
            for (int i = 0; i < addCount; i++) {
                adds.add(DecorationRecord.readFromNetwork(buf));
            }
            int remCount = buf.readVarInt();
            List<DecorationId> rems = new ArrayList<>(remCount);
            for (int i = 0; i < remCount; i++) {
                rems.add(DecorationId.readFromNetwork(buf));
            }
            return new S2CRegionDelta(rx, rz, rev, adds, rems);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
