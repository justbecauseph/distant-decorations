package me.justbecause.distantdecorations.network.c2s;

import me.justbecause.distantdecorations.DistantDecorations;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record C2SSubscriptionUpdate(
    int centerChunkX,
    int centerChunkZ,
    int requestedRadiusChunks
) implements CustomPacketPayload {
    public static final Type<C2SSubscriptionUpdate> TYPE = new Type<>(DistantDecorations.id("subscription_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSubscriptionUpdate> CODEC = StreamCodec.of(
        (buf, packet) -> {
            buf.writeVarInt(packet.centerChunkX);
            buf.writeVarInt(packet.centerChunkZ);
            buf.writeVarInt(packet.requestedRadiusChunks);
        },
        buf -> {
            int cx = buf.readVarInt();
            int cz = buf.readVarInt();
            int radius = buf.readVarInt();
            return new C2SSubscriptionUpdate(cx, cz, radius);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
