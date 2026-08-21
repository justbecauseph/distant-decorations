package me.justbecause.distantdecorations.api;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class CoreApiTest {

    @Test
    public void testDecorationIdEqualityAndHashing() {
        Identifier type = Identifier.fromNamespaceAndPath("test", "painting");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        BlockPos pos = new BlockPos(100, 64, -200);

        DecorationId id1 = new DecorationId(type, dim, pos);
        DecorationId id2 = new DecorationId(type, dim, new BlockPos(100, 64, -200));

        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    public void testDecorationRecordStreamRoundTrip() throws Exception {
        Identifier type = Identifier.fromNamespaceAndPath("test", "frame");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "the_nether"));
        BlockPos pos = new BlockPos(-50, 120, 300);
        DecorationId id = new DecorationId(type, dim, pos);
        AABB bounds = new AABB(-50.5, 120.0, 300.0, -48.5, 122.0, 301.0);
        byte[] payload = "test-payload-bytes".getBytes(StandardCharsets.UTF_8);

        DecorationRecord record = new DecorationRecord(id, bounds, 42L, payload);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        record.writeToStream(dos);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        DecorationRecord deserialized = DecorationRecord.readFromStream(dis);

        assertEquals(record, deserialized);
        assertEquals(42L, deserialized.revision());
        assertArrayEquals(payload, deserialized.payload());
    }

    @Test
    public void testDecorationRecordNetworkRoundTrip() {
        Identifier type = Identifier.fromNamespaceAndPath("test", "fast_painting");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        BlockPos pos = new BlockPos(1024, 70, 2048);
        DecorationId id = new DecorationId(type, dim, pos);
        AABB bounds = new AABB(1024.0, 70.0, 2048.0, 1026.0, 72.0, 2048.1);
        byte[] payload = new byte[]{1, 2, 3, 4, 5};

        DecorationRecord record = new DecorationRecord(id, bounds, 999L, payload);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        record.writeToNetwork(buf);

        DecorationRecord decoded = DecorationRecord.readFromNetwork(buf);
        buf.release();

        assertEquals(record, decoded);
    }

    @Test
    public void testDecorationTypeEncoding() {
        record SampleData(String name, int size) {}

        DecorationType<SampleData> type = new DecorationType<>(
            Identifier.fromNamespaceAndPath("test", "sample"),
            (data, buf) -> {
                buf.writeUtf(data.name());
                buf.writeVarInt(data.size());
            },
            buf -> new SampleData(buf.readUtf(), buf.readVarInt())
        );

        SampleData original = new SampleData("sunset", 128);
        byte[] bytes = type.toBytes(original);
        SampleData restored = type.fromBytes(bytes);

        assertEquals(original, restored);
    }
}
