package me.justbecause.distantdecorations.network.s2c;

import me.justbecause.distantdecorations.DistantDecorations;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CRegionUnload(
    int regionX,
    int regionZ
) implements CustomPacketPayload {
    public static final Type<S2CRegionUnload> TYPE = new Type<>(DistantDecorations.id("region_unload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRegionUnload> CODEC = StreamCodec.of(
        (buf, packet) -> {
            buf.writeVarInt(packet.regionX);
            buf.writeVarInt(packet.regionZ);
        },
        buf -> {
            int rx = buf.readVarInt();
            int rz = buf.readVarInt();
            return new S2CRegionUnload(rx, rz);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
