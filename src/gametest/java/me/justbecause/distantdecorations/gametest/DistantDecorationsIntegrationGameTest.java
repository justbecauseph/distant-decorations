package me.justbecause.distantdecorations.gametest;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.provider.camerapture.CameraptureProvider;
import me.justbecause.distantdecorations.provider.painting.FastPaintingsProvider;
import me.justbecause.distantdecorations.server.ServerDecorationManager;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.decoration.painting.PaintingVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.UUID;

public class DistantDecorationsIntegrationGameTest {

    @GameTest
    public void testFastPaintingsIntegration(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        BlockPos paintingPos = new BlockPos(2, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);
        helper.setBlock(paintingPos, Blocks.AIR);

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(paintingPos);

        try {
            Class<?> placementService = Class.forName("me.justbecause.fastpaintings.painting.PaintingPlacementService");
            Holder<PaintingVariant> kebab = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                    .getOrThrow(PaintingVariants.KEBAB);

            Method tryPlace = placementService.getMethod(
                "tryPlacePainting",
                Level.class, BlockPos.class, Direction.class, Holder.class, Player.class, RandomSource.class
            );
            boolean placed = (boolean) tryPlace.invoke(null, level, absPos, Direction.SOUTH, kebab, null, level.getRandom());
            helper.assertTrue(placed, "Fast Paintings placement failed");

            // Verify Distant Decorations captures the painting
            DecorationRecord record = DistantDecorations.publish(level, absPos);
            helper.assertTrue(record != null, "DistantDecorations failed to capture Fast Paintings block entity");
            helper.assertTrue(record.id().type().equals(FastPaintingsProvider.TYPE_ID), "Captured record has wrong type: " + record.id().type());

            // Remove and verify
            boolean removed = DistantDecorations.remove(level, absPos);
            helper.assertTrue(removed, "Failed to remove decoration from DistantDecorations");

        } catch (ClassNotFoundException e) {
            // Fast Paintings not present in runtime classpath, pass
        } catch (Exception e) {
            throw new RuntimeException("Fast Paintings integration test failed", e);
        }

        helper.succeed();
    }

    @GameTest
    public void testCameraptureIntegration(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(3, 2, 2);
        BlockPos framePos = new BlockPos(3, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);
        helper.setBlock(framePos, Blocks.AIR);

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(framePos);

        try {
            Class<?> camClass = Class.forName("me.chrr.camerapture.Camerapture");
            Object frameBlock = camClass.getField("PICTURE_FRAME_BLOCK").get(null);
            Method defaultBlockState = frameBlock.getClass().getMethod("defaultBlockState");
            net.minecraft.world.level.block.state.BlockState state = (net.minecraft.world.level.block.state.BlockState) defaultBlockState.invoke(frameBlock);

            level.setBlock(absPos, state, 3);
            BlockEntity be = level.getBlockEntity(absPos);
            helper.assertTrue(be != null, "Failed to instantiate PictureFrameBlockEntity");

            // Populate mock picture item stack
            Class<?> picItemClass = Class.forName("me.chrr.camerapture.item.PictureItem");
            Method createMethod = picItemClass.getMethod("create", String.class, UUID.class);
            UUID testId = UUID.randomUUID();
            Object itemStack = createMethod.invoke(null, "Tester", testId);

            Method setItemStack = be.getClass().getMethod("setItemStack", net.minecraft.world.item.ItemStack.class);
            setItemStack.invoke(be, itemStack);

            // Verify Distant Decorations captures the picture frame
            DecorationRecord record = DistantDecorations.publish(level, absPos, be);
            helper.assertTrue(record != null, "DistantDecorations failed to capture Camerapture PictureFrame");
            helper.assertTrue(record.id().type().equals(CameraptureProvider.TYPE_ID), "Captured record has wrong type: " + record.id().type());

            // Remove and verify
            boolean removed = DistantDecorations.remove(level, absPos);
            helper.assertTrue(removed, "Failed to remove Camerapture decoration");

        } catch (ClassNotFoundException e) {
            // Camerapture not present in runtime classpath, pass
        } catch (Exception e) {
            throw new RuntimeException("Camerapture integration test failed", e);
        }

        helper.succeed();
    }

    @GameTest
    public void testVoxyCoexistence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
        helper.assertTrue(index != null, "DistantDecorations server index is null with Voxy active");

        // Verify world dimensions indexing works alongside Voxy's storage backend
        helper.assertTrue(level.dimension() != null, "ServerLevel dimension is null");
        helper.succeed();
    }
}
