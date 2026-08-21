package me.justbecause.distantdecorations.provider;

import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationRegistry;
import me.justbecause.distantdecorations.api.DecorationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ProviderTest {

    public record TestPaintingData(
        Identifier variant,
        Direction facing,
        int width,
        int height,
        int packedLight
    ) {}

    public static final DecorationType<TestPaintingData> TEST_PAINTING_TYPE = new DecorationType<>(
        Identifier.fromNamespaceAndPath("test", "painting"),
        (data, buf) -> {
            buf.writeIdentifier(data.variant());
            buf.writeEnum(data.facing());
            buf.writeVarInt(data.width());
            buf.writeVarInt(data.height());
            buf.writeInt(data.packedLight());
        },
        buf -> new TestPaintingData(
            buf.readIdentifier(),
            buf.readEnum(Direction.class),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readInt()
        )
    );

    public record TestPictureFrameData(
        UUID pictureId,
        Direction facing,
        int width,
        int height,
        int rotation,
        boolean glow,
        int packedLight
    ) {}

    public static final DecorationType<TestPictureFrameData> TEST_FRAME_TYPE = new DecorationType<>(
        Identifier.fromNamespaceAndPath("test", "picture_frame"),
        (data, buf) -> {
            buf.writeUUID(data.pictureId());
            buf.writeEnum(data.facing());
            buf.writeVarInt(data.width());
            buf.writeVarInt(data.height());
            buf.writeVarInt(data.rotation());
            buf.writeBoolean(data.glow());
            buf.writeInt(data.packedLight());
        },
        buf -> new TestPictureFrameData(
            buf.readUUID(),
            buf.readEnum(Direction.class),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readBoolean(),
            buf.readInt()
        )
    );

    @Test
    public void testPaintingDataSerializationRoundTrip() {
        TestPaintingData data = new TestPaintingData(
            Identifier.fromNamespaceAndPath("minecraft", "kebab"),
            Direction.SOUTH,
            2,
            1,
            0x00F000F0
        );

        byte[] bytes = TEST_PAINTING_TYPE.toBytes(data);
        assertNotNull(bytes);

        TestPaintingData decoded = TEST_PAINTING_TYPE.fromBytes(bytes);
        assertEquals(data.variant(), decoded.variant());
        assertEquals(data.facing(), decoded.facing());
        assertEquals(data.width(), decoded.width());
        assertEquals(data.height(), decoded.height());
        assertEquals(data.packedLight(), decoded.packedLight());
    }

    @Test
    public void testPictureFrameDataSerializationRoundTrip() {
        UUID pictureId = UUID.randomUUID();
        TestPictureFrameData data = new TestPictureFrameData(
            pictureId,
            Direction.EAST,
            3,
            2,
            1,
            true,
            0x00F000F0
        );

        byte[] bytes = TEST_FRAME_TYPE.toBytes(data);
        assertNotNull(bytes);

        TestPictureFrameData decoded = TEST_FRAME_TYPE.fromBytes(bytes);
        assertEquals(data.pictureId(), decoded.pictureId());
        assertEquals(data.facing(), decoded.facing());
        assertEquals(data.width(), decoded.width());
        assertEquals(data.height(), decoded.height());
        assertEquals(data.rotation(), decoded.rotation());
        assertTrue(decoded.glow());
        assertEquals(data.packedLight(), decoded.packedLight());
    }

    @Test
    public void testDecorationRegistryRegistration() {
        DecorationRegistry.registerType(TEST_PAINTING_TYPE);
        assertEquals(TEST_PAINTING_TYPE, DecorationRegistry.getType(TEST_PAINTING_TYPE.id()));
    }
}

