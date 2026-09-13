package me.justbecause.distantdecorations.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.client.DistantDecorationsClient;
import me.justbecause.distantdecorations.config.DistantDecorationsConfig;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CommandAuthorizationRegressionTest {

    private CommandDispatcher<CommandSourceStack> serverDispatcher;
    private CommandDispatcher<FabricClientCommandSource> clientDispatcher;
    private FabricClientCommandSource clientSource;
    private List<Component> clientFeedbackMessages;

    private boolean originalMasterEnabled;
    private boolean originalClientRenderingEnabled;

    @BeforeAll
    public static void initMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    public void setUp() {
        serverDispatcher = new CommandDispatcher<>();
        DistantDecorations.registerCommands(serverDispatcher);

        clientDispatcher = new CommandDispatcher<>();
        DistantDecorationsClient.registerClientCommands(clientDispatcher);

        clientFeedbackMessages = new ArrayList<>();
        clientSource = (FabricClientCommandSource) Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[]{FabricClientCommandSource.class},
            (proxy, method, args) -> {
                if (("sendFeedback".equals(method.getName()) || "sendError".equals(method.getName())) && args != null && args.length > 0) {
                    clientFeedbackMessages.add((Component) args[0]);
                }
                return null;
            }
        );

        originalMasterEnabled = DistantDecorationsConfig.isMasterEnabled();
        originalClientRenderingEnabled = DistantDecorationsConfig.isClientRenderingEnabled();
    }

    @AfterEach
    public void tearDown() {
        DistantDecorationsConfig.setMasterEnabled(originalMasterEnabled);
        DistantDecorationsConfig.setClientRenderingEnabled(originalClientRenderingEnabled);
    }

    private CommandSourceStack createSource(PermissionSet permissions) {
        return new CommandSourceStack(
            CommandSource.NULL,
            Vec3.ZERO,
            Vec2.ZERO,
            null,                           // ServerLevel: unused by this fixture
            permissions,
            Component.literal("TestUser"),
            null                            // MinecraftServer: unused by this fixture
        );
    }

    @Test
    public void testNonOperatorCannotExecuteServerToggle() {
        CommandSourceStack nonOp = createSource(PermissionSet.NO_PERMISSIONS);
        assertThrows(CommandSyntaxException.class, () -> serverDispatcher.execute("dd toggle", nonOp),
            "Non-operator player must not be permitted to execute /dd toggle");
    }

    @Test
    public void testModeratorCannotExecuteServerToggle() {
        // Moderator (level 1) must be rejected because LEVEL_GAMEMASTERS (level 2) is required
        PermissionSet modPermissions = new PermissionSet() {
            @Override
            public boolean hasPermission(Permission permission) {
                return permission.equals(Permissions.COMMANDS_MODERATOR);
            }
        };
        CommandSourceStack mod = createSource(modPermissions);
        assertThrows(CommandSyntaxException.class, () -> serverDispatcher.execute("dd toggle", mod),
            "Moderator (level 1) must not be permitted to execute /dd toggle; level 2 (GAMEMASTER) is required");
    }

    @Test
    public void testGamemasterCanExecuteServerToggle() throws Exception {
        // Gamemaster (level 2) must be permitted
        PermissionSet gmPermissions = new PermissionSet() {
            @Override
            public boolean hasPermission(Permission permission) {
                return permission.equals(Permissions.COMMANDS_GAMEMASTER);
            }
        };
        CommandSourceStack gm = createSource(gmPermissions);

        boolean initial = DistantDecorationsConfig.isMasterEnabled();
        int result = serverDispatcher.execute("dd toggle", gm);
        assertEquals(1, result);
        assertEquals(!initial, DistantDecorationsConfig.isMasterEnabled(),
            "/dd toggle should flip masterEnabled for gamemaster");
    }

    @Test
    public void testOrdinaryPlayerCanExecuteStatsCommand() throws Exception {
        CommandSourceStack nonOp = createSource(PermissionSet.NO_PERMISSIONS);
        int result = serverDispatcher.execute("dd stats", nonOp);
        assertEquals(1, result, "/dd stats should be publicly readable without operator permissions");
    }

    @Test
    public void testClientToggleExecutedViaBrigadierWithServerSwitchInitiallyTrue() throws Exception {
        DistantDecorationsConfig.setMasterEnabled(true);
        DistantDecorationsConfig.setClientRenderingEnabled(true);

        int result = clientDispatcher.execute("ddc toggle", clientSource);
        assertEquals(1, result);
        assertFalse(DistantDecorationsConfig.isClientRenderingEnabled(),
            "Client rendering should be disabled after /ddc toggle");
        assertTrue(DistantDecorationsConfig.isMasterEnabled(),
            "Server master switch must remain true when client executes /ddc toggle");
        assertFalse(clientFeedbackMessages.isEmpty(), "Client feedback should be sent");
    }

    @Test
    public void testClientToggleExecutedViaBrigadierWithServerSwitchInitiallyFalse() throws Exception {
        DistantDecorationsConfig.setMasterEnabled(false);
        DistantDecorationsConfig.setClientRenderingEnabled(false);

        int result = clientDispatcher.execute("ddc toggle", clientSource);
        assertEquals(1, result);
        assertTrue(DistantDecorationsConfig.isClientRenderingEnabled(),
            "Client rendering should be enabled after /ddc toggle");
        assertFalse(DistantDecorationsConfig.isMasterEnabled(),
            "Server master switch must remain false when client executes /ddc toggle");
        assertFalse(clientFeedbackMessages.isEmpty(), "Client feedback should be sent");
    }
}
