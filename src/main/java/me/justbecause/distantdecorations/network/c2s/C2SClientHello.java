package me.justbecause.distantdecorations.network.c2s;

import me.justbecause.distantdecorations.DistantDecorations;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record C2SClientHello(
    int protocolVersion,
    List<Identifier> supportedTypes,
    int requestedRadiusChunks
) implements CustomPacketPayload {
    public static final Type<C2SClientHello> TYPE = new Type<>(DistantDecorations.id("client_hello"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SClientHello> CODEC = StreamCodec.of(
        (buf, packet) -> {
            buf.writeVarInt(packet.protocolVersion);
            buf.writeVarInt(packet.supportedTypes.size());
            for (Identifier type : packet.supportedTypes) {
                buf.writeIdentifier(type);
            }
            buf.writeVarInt(packet.requestedRadiusChunks);
        },
        buf -> {
            int version = buf.readVarInt();
            int count = buf.readVarInt();
            List<Identifier> types = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                types.add(buf.readIdentifier());
            }
            int radius = buf.readVarInt();
            return new C2SClientHello(version, types, radius);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
