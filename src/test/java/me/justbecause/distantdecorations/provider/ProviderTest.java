package me.justbecause.distantdecorations.provider;

import me.justbecause.distantdecorations.provider.camerapture.CameraptureProvider;
import me.justbecause.distantdecorations.provider.camerapture.DistantPictureFrameData;
import me.justbecause.distantdecorations.provider.painting.DistantPaintingData;
import me.justbecause.distantdecorations.provider.painting.FastPaintingsProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ProviderTest {

    @Test
    public void testFastPaintingDataSerializationRoundTrip() {
        DistantPaintingData data = new DistantPaintingData(
            Identifier.fromNamespaceAndPath("minecraft", "kebab"),
            Direction.SOUTH,
            2,
            1,
            0x00F000F0
        );

        byte[] bytes = FastPaintingsProvider.TYPE.toBytes(data);
        assertNotNull(bytes);

        DistantPaintingData decoded = FastPaintingsProvider.TYPE.fromBytes(bytes);
        assertEquals(data.variant(), decoded.variant());
        assertEquals(data.facing(), decoded.facing());
        assertEquals(data.width(), decoded.width());
        assertEquals(data.height(), decoded.height());
        assertEquals(data.packedLight(), decoded.packedLight());
    }

    @Test
    public void testFastPaintingBoundingBox() {
        BlockPos pos = new BlockPos(100, 64, 200);
        AABB aabb = FastPaintingsProvider.calculateBoundingBox(pos, Direction.NORTH, 2, 2);

        assertNotNull(aabb);
        assertEquals(2.0, aabb.getXsize(), 0.001);
        assertEquals(2.0, aabb.getYsize(), 0.001);
        assertEquals(0.0625, aabb.getZsize(), 0.001);
    }

    @Test
    public void testCameraptureDataSerializationRoundTrip() {
        UUID pictureId = UUID.randomUUID();
        DistantPictureFrameData data = new DistantPictureFrameData(
            pictureId,
            Direction.EAST,
            3,
            2,
            1,
            true,
            0x00F000F0
        );

        byte[] bytes = CameraptureProvider.TYPE.toBytes(data);
        assertNotNull(bytes);

        DistantPictureFrameData decoded = CameraptureProvider.TYPE.fromBytes(bytes);
        assertEquals(data.pictureId(), decoded.pictureId());
        assertEquals(data.facing(), decoded.facing());
        assertEquals(data.width(), decoded.width());
        assertEquals(data.height(), decoded.height());
        assertEquals(data.rotation(), decoded.rotation());
        assertTrue(decoded.glow());
        assertEquals(data.packedLight(), decoded.packedLight());
    }

    @Test
    public void testCameraptureBoundingBox() {
        BlockPos pos = new BlockPos(50, 70, 80);
        AABB aabb = CameraptureProvider.calculateBounds(pos, Direction.EAST, 4, 3);

        assertNotNull(aabb);
        assertEquals(0.0625, aabb.getXsize(), 0.001);
        assertEquals(3.0, aabb.getYsize(), 0.001);
        assertEquals(4.0, aabb.getZsize(), 0.001);
    }
}
