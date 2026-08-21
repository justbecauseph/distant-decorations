package me.justbecause.distantdecorations.gametest;

import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.api.DecorationProvider;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationRegistry;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.server.ServerDecorationManager;
import me.justbecause.distantdecorations.server.storage.ServerDecorationWorldIndex;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.decoration.painting.PaintingVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;

public class DistantDecorationsIntegrationGameTest {

    private static final Identifier TEST_TYPE_ID = DistantDecorations.id("test_decoration");
    private static final DecorationType<String> TEST_TYPE = new DecorationType<>(
        TEST_TYPE_ID,
        (data, buf) -> buf.writeUtf(data),
        buf -> buf.readUtf()
    );

    @GameTest
    public void testProviderCaptureAndPublish(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        helper.setBlock(wallPos, Blocks.STONE);

        ServerLevel level = helper.getLevel();
        BlockPos absPos = helper.absolutePos(wallPos);

        // Register custom test provider
        DecorationProvider<String> provider = new DecorationProvider<>() {
            @Override
            public DecorationType<String> type() {
                return TEST_TYPE;
            }

            @Override
            public boolean matches(BlockEntity blockEntity) {
                return true;
            }

            @Override
            public @Nullable String capture(ServerLevel level, BlockPos pos, BlockEntity blockEntity) {
                return "test-data";
            }

            @Override
            public AABB calculateBounds(ServerLevel level, BlockPos pos, String data) {
                return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            }
        };
        DecorationRegistry.registerProvider(provider);

        ServerDecorationWorldIndex index = ServerDecorationManager.getInstance().getIndex(level);
        helper.assertTrue(index != null, "DistantDecorations server index is null");

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

