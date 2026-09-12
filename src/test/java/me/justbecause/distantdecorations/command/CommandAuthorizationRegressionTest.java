package me.justbecause.distantdecorations.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.justbecause.distantdecorations.DistantDecorations;
import me.justbecause.distantdecorations.config.DistantDecorationsConfig;
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

import static org.junit.jupiter.api.Assertions.*;

public class CommandAuthorizationRegressionTest {

    private CommandDispatcher<CommandSourceStack> dispatcher;
    private boolean originalMasterEnabled;
    private boolean originalClientRenderingEnabled;

    @BeforeAll
    public static void initMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    public void setUp() {
        dispatcher = new CommandDispatcher<>();
        DistantDecorations.registerCommands(dispatcher);
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
            null,
            permissions,
            "TestUser",
            Component.literal("TestUser"),
            null,
            null
        );
    }

    @Test
    public void testNonOperatorCannotExecuteServerToggle() {
        CommandSourceStack nonOp = createSource(PermissionSet.NO_PERMISSIONS);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dd toggle", nonOp),
            "Non-operator player must not be permitted to execute /dd toggle");
    }

    @Test
    public void testOperatorCanExecuteServerToggle() throws Exception {
        PermissionSet opPermissions = new PermissionSet() {
            @Override
            public boolean hasPermission(Permission permission) {
                return permission.equals(Permissions.COMMANDS_GAMEMASTER)
                    || permission.equals(Permissions.COMMANDS_ADMIN)
                    || permission.equals(Permissions.COMMANDS_OWNER);
            }
        };
        CommandSourceStack op = createSource(opPermissions);

        boolean initial = DistantDecorationsConfig.isMasterEnabled();
        int result = dispatcher.execute("dd toggle", op);
        assertEquals(1, result);
        assertEquals(!initial, DistantDecorationsConfig.isMasterEnabled(),
            "/dd toggle should flip masterEnabled for operator");
    }

    @Test
    public void testOrdinaryPlayerCanExecuteStatsCommand() throws Exception {
        CommandSourceStack nonOp = createSource(PermissionSet.NO_PERMISSIONS);
        int result = dispatcher.execute("dd stats", nonOp);
        assertEquals(1, result, "/dd stats should be publicly readable without operator permissions");
    }

    @Test
    public void testClientToggleIsIndependentOfServerMasterSwitch() {
        DistantDecorationsConfig.setMasterEnabled(true);
        DistantDecorationsConfig.setClientRenderingEnabled(true);

        // Toggle client rendering
        DistantDecorationsConfig.setClientRenderingEnabled(false);

        // Verify client toggle did not modify server master switch
        assertTrue(DistantDecorationsConfig.isMasterEnabled(),
            "Client rendering toggle must not change server master switch state");
        assertFalse(DistantDecorationsConfig.isClientRenderingEnabled(),
            "Client rendering should be disabled");

        // Toggle back
        DistantDecorationsConfig.setClientRenderingEnabled(true);
        assertTrue(DistantDecorationsConfig.isMasterEnabled());
        assertTrue(DistantDecorationsConfig.isClientRenderingEnabled());
    }
}
