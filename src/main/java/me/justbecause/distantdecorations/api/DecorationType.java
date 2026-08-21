package me.justbecause.distantdecorations.api;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class DecorationType<T> {
    private final Identifier id;
    private final BiConsumer<T, FriendlyByteBuf> serializer;
    private final Function<FriendlyByteBuf, T> deserializer;

    public DecorationType(
        Identifier id,
        BiConsumer<T, FriendlyByteBuf> serializer,
        Function<FriendlyByteBuf, T> deserializer
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer cannot be null");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer cannot be null");
    }

    public Identifier id() {
        return id;
    }

    public void encode(T data, FriendlyByteBuf buf) {
        serializer.accept(data, buf);
    }

    public T decode(FriendlyByteBuf buf) {
        return deserializer.apply(buf);
    }

    public byte[] toBytes(T data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(data, buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    public T fromBytes(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return decode(buf);
        } finally {
            buf.release();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DecorationType<?> that = (DecorationType<?>) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
