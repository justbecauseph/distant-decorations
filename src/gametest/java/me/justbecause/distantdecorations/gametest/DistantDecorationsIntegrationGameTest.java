package me.justbecause.distantdecorations.gametest;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationRegistry;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.server.ServerDecorationManager;
import me.justbecause.distantdecorations.server.storage.ServerDecorationRegion;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public class DistantDecorationsIntegrationGameTest {

    private static final Identifier BARREL_TEST_TYPE_ID = DistantDecorations.id("test_barrel_decoration");
    private static final DecorationType<String> BARREL_TEST_TYPE = new DecorationType<>(
        BARREL_TEST_TYPE_ID,
        (data, buf) -> buf.writeUtf(data),
        buf -> buf.readUtf()
    );

    @GameTest
    public void testProviderCaptureAndPublish(GameTestHelper helper) {
        BlockPos barrelPos = new BlockPos(2, 2, 2);
        helper.setBlock(barrelPos, Blocks.BARREL);

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(barrelPos);
        BlockEntity barrelBe = level.getBlockEntity(absPos);
        helper.assertTrue(barrelBe != null, "Barrel block entity was not created");

        // Register fixture provider that genuinely matches BarrelBlockEntity
        DecorationProvider<String> provider = new DecorationProvider<>() {
            @Override
            public DecorationType<String> type() {
                return BARREL_TEST_TYPE;
            }

            @Override
            public boolean matches(BlockEntity blockEntity) {
                return blockEntity.getBlockState().is(Blocks.BARREL);
            }

            @Override
            public @Nullable String capture(ServerLevel lvl, BlockPos pos, BlockEntity be) {
                return "barrel-data-payload";
            }

            @Override
            public AABB calculateBounds(ServerLevel lvl, BlockPos pos, String data) {
                return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            }
        };
        DecorationRegistry.registerProvider(provider);

        try {
            ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
            helper.assertTrue(index != null, "DistantDecorations server index is null");

            // 1. Publish captures and indexes the real barrel decoration
            DecorationRecord record = index.publish(absPos, barrelBe);
            helper.assertTrue(record != null, "Decoration record was null after publish");
            helper.assertTrue(record.id().anchor().equals(absPos), "Decoration anchor mismatch");
            helper.assertTrue(record.id().type().equals(BARREL_TEST_TYPE_ID), "Decoration type mismatch");

            // Assert exact bounds and decoded payload
            AABB expectedBounds = new AABB(absPos.getX(), absPos.getY(), absPos.getZ(), absPos.getX() + 1, absPos.getY() + 1, absPos.getZ() + 1);
            helper.assertTrue(expectedBounds.equals(record.bounds()), "Decoration bounds mismatch: expected " + expectedBounds + ", got " + record.bounds());
            helper.assertTrue(record.payload() != null, "Decoration payload must not be null");
            String decodedPayload = BARREL_TEST_TYPE.fromBytes(record.payload());
            helper.assertTrue("barrel-data-payload".equals(decodedPayload), "Decoded payload mismatch: expected 'barrel-data-payload', got: " + decodedPayload);

            int rx = ServerDecorationWorldIndex.chunkToRegionCoord(absPos.getX() >> 4);
            int rz = ServerDecorationWorldIndex.chunkToRegionCoord(absPos.getZ() >> 4);
            ServerDecorationRegion region = index.getRegion(rx, rz);
            helper.assertTrue(region != null, "Region was not created for published decoration");
            helper.assertTrue(region.getRecord(record.id()) != null, "Record missing from region");
            long firstRevision = record.revision();
            long regionRevisionBefore = region.revision();

            // 2. Unchanged capture is a no-op (same record, revision and region revision not incremented)
            DecorationRecord unchanged = index.publish(absPos, barrelBe);
            helper.assertTrue(unchanged == record, "Unchanged publish did not return identical record instance");
            helper.assertTrue(unchanged.revision() == firstRevision, "Unchanged publish incremented record revision");
            helper.assertTrue(region.revision() == regionRevisionBefore, "Unchanged publish incremented region revision");

            // 3. Removal via index removes the decoration and increments revision
            boolean removed = index.remove(absPos);
            helper.assertTrue(removed, "index.remove() failed for existing decoration");
            helper.assertTrue(region.getRecord(record.id()) == null, "Record not removed from region");
            helper.assertTrue(region.revision() > firstRevision, "Removal did not increment region revision");

            helper.succeed();
        } finally {
            DecorationRegistry.unregisterProvider(BARREL_TEST_TYPE_ID);
        }
    }

    @GameTest
    public void testBlockEntityLifecycleRemovalDoesNotDeletePersistentRecord(GameTestHelper helper) {
        BlockPos relativePos = new BlockPos(2, 2, 2);
        helper.setBlock(relativePos, Blocks.CHEST);

        ServerLevel level = helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(relativePos);
        BlockEntity blockEntity = level.getBlockEntity(absolutePos);
        helper.assertTrue(blockEntity != null, "Chest block entity was not created");

        Identifier typeId = DistantDecorations.id("unload_persistence_test");
        DecorationType<String> type = new DecorationType<>(
            typeId,
            (data, buf) -> buf.writeUtf(data),
            buf -> buf.readUtf()
        );
        DecorationRegistry.registerProvider(new DecorationProvider<String>() {
            @Override
            public DecorationType<String> type() {
                return type;
            }

            @Override
            public boolean matches(BlockEntity candidate) {
                return candidate.getBlockState().is(Blocks.CHEST);
            }

            @Override
            public String capture(ServerLevel serverLevel, BlockPos pos, BlockEntity candidate) {
                return "persistent";
            }

            @Override
            public AABB calculateBounds(ServerLevel serverLevel, BlockPos pos, String data) {
                return new AABB(pos);
            }
        });

        try {
            ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
            helper.assertTrue(index != null, "DistantDecorations server index is null");
            DecorationRecord record = index.publish(absolutePos, blockEntity);
            helper.assertTrue(record != null, "Test decoration was not published");

            // Chunk unloading marks its block entities removed without meaning the backing block was destroyed.
            blockEntity.setRemoved();
            int regionX = ServerDecorationWorldIndex.chunkToRegionCoord(absolutePos.getX() >> 4);
            int regionZ = ServerDecorationWorldIndex.chunkToRegionCoord(absolutePos.getZ() >> 4);
            helper.assertTrue(
                index.getRegion(regionX, regionZ).getRecord(record.id()) != null,
                "BlockEntity.setRemoved() deleted a persistent decoration record"
            );
            blockEntity.clearRemoved();

            helper.succeed();
        } finally {
            DecorationRegistry.unregisterProvider(typeId);
        }
    }

    @GameTest
    public void testWorldIndexInitialization(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
        helper.assertTrue(index != null, "DistantDecorations server index is null");

        // Verify world index initialization for server level
        helper.assertTrue(level.dimension() != null, "ServerLevel dimension is null");
        helper.succeed();
    }
}
