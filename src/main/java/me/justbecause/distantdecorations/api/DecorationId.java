package me.justbecause.distantdecorations.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

public record DecorationId(
    Identifier type,
    ResourceKey<Level> dimension,
    BlockPos anchor
) {
    public DecorationId {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(dimension, "dimension cannot be null");
        Objects.requireNonNull(anchor, "anchor cannot be null");
    }

    public void writeToNetwork(FriendlyByteBuf buf) {
        buf.writeIdentifier(type);
        buf.writeResourceKey(dimension);
        buf.writeBlockPos(anchor);
    }

    public static DecorationId readFromNetwork(FriendlyByteBuf buf) {
        Identifier type = buf.readIdentifier();
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        BlockPos anchor = buf.readBlockPos();
        return new DecorationId(type, dimension, anchor);
    }

    public void writeToStream(DataOutput out) throws IOException {
        out.writeUTF(type.toString());
        out.writeUTF(dimension.identifier().toString());
        out.writeInt(anchor.getX());
        out.writeInt(anchor.getY());
        out.writeInt(anchor.getZ());
    }

    public static DecorationId readFromStream(DataInput in) throws IOException {
        Identifier type = Identifier.parse(in.readUTF());
        Identifier dimLoc = Identifier.parse(in.readUTF());
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimLoc);
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        return new DecorationId(type, dimension, new BlockPos(x, y, z));
    }
}
