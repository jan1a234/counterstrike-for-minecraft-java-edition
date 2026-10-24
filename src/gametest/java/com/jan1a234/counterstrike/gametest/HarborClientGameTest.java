package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.game.arena.ArenaManager;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Actual renderer + logged-in player; Fabric creates an isolated disposable test save. */
public final class HarborClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        PlayerState beforeRestart;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            save = world.getWorldSave();
            PlayerState original =
                    world.getServer()
                            .computeOnServer(
                                    server -> {
                                        ServerPlayer player =
                                                server.getPlayerList().getPlayers().getFirst();
                                        fixture(player);
                                        var survivalTeam =
                                                server.getScoreboard().addPlayerTeam("survival");
                                        server.getScoreboard()
                                                .addPlayerToTeam(
                                                        player.getScoreboardName(), survivalTeam);
                                        command(player, "cs join ct");
                                        player.getInventory()
                                                .getItem(4)
                                                .setCount(8); // Simulate normal lobby item
                                        // usage/transfer.
                                        PlayerState lobbyState = PlayerState.capture(player);
                                        command(player, "cs leave");
                                        lobbyState.verify(
                                                player,
                                                "lobby membership is not a survival-state"
                                                    + " rollback");
                                        check(
                                                server.getScoreboard()
                                                                .getPlayersTeam(
                                                                        player.getScoreboardName())
                                                        == survivalTeam,
                                                "Lobby join/leave must preserve the original"
                                                    + " scoreboard team");
                                        PlayerState state = fixture(player);
                                        Match.get().config().ctBots = 3;
                                        Match.get().config().tBots = 3;
                                        command(player, "cs play");
                                        check(
                                                Match.get().state() == Match.State.COUNTDOWN,
                                                "/cs play must start countdown");
                                        check(
                                                Match.get().teamOf(player) != null,
                                                "/cs play must join the player");
                                        check(
                                                ArenaManager.contains(player),
                                                "/cs play must teleport to harbor");
                                        check(
                                                player.gameMode.getGameModeForPlayer()
                                                        == GameType.ADVENTURE,
                                                "Participants must use adventure mode");
                                        return state;
                                    });
            world.getServer().waitFor(server -> Match.get().state() == Match.State.RUNNING, 600);
            world.getServer()
                    .runOnServer(
                            server -> {
                                var player = server.getPlayerList().getPlayers().getFirst();
                                var outsider =
                                        new ServerPlayer(
                                                server,
                                                player.level(),
                                                new com.mojang.authlib.GameProfile(
                                                        java.util.UUID.randomUUID(), "Outsider"),
                                                net.minecraft.server.level.ClientInformation
                                                        .createDefault());
                                Match.get().breakSpawnProtection(player);
                                float health = player.getHealth();
                                check(
                                        !player.hurtServer(
                                                player.level(),
                                                player.damageSources().playerAttack(outsider),
                                                4),
                                        "Outsider melee must not damage an active participant");
                                check(
                                        player.getHealth() == health,
                                        "Outsider damage must not change participant health");
                                check(
                                        !player.hurtServer(
                                                player.level(),
                                                player.damageSources().arrow(null, outsider),
                                                4),
                                        "Outsider-owned projectiles must not damage an active"
                                            + " participant");
                            });
            world.getConnection().waitForChunksRender();
            context.waitTicks(10);
            context.takeScreenshot("harbor-participant-running");
            for (var type : com.jan1a234.counterstrike.weapon.WeaponType.values()) {
                world.getServer()
                        .runOnServer(
                                server -> {
                                    var player = server.getPlayerList().getPlayers().getFirst();
                                    player.getInventory()
                                            .setItem(
                                                    0,
                                                    com.jan1a234.counterstrike.weapon.Weapons
                                                            .create(type));
                                    player.getInventory().setSelectedSlot(0);
                                });
                context.waitTicks(8);
                context.takeScreenshot("weapon-" + type.id);
            }
            world.getServer()
                    .runOnServer(
                            server -> {
                                ServerPlayer player =
                                        server.getPlayerList().getPlayers().getFirst();
                                command(player, "cs leave");
                                original.verify(player, "leave");
                                check(
                                        Match.get().teamOf(player) == null,
                                        "Leaving removes team membership");
                                Match.get().stop();
                            });

            // An overview is still rendered by Minecraft, never a schematic or fabricated image.
            world.getServer()
                    .runOnServer(
                            server -> {
                                ServerPlayer player =
                                        server.getPlayerList().getPlayers().getFirst();
                                player.setGameMode(GameType.SPECTATOR);
                                player.teleportTo(
                                        ArenaManager.level(server),
                                        0.5,
                                        110,
                                        54.5,
                                        Set.<Relative>of(),
                                        180,
                                        38,
                                        true);
                            });
            world.getConnection().waitForChunksRender();
            context.waitTicks(10);
            context.runOnClient(
                    client -> {
                        if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                    });
            context.waitTicks(140);
            context.takeScreenshot("harbor-real-render-overview");
            context.runOnClient(
                    client -> {
                        if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                    });

            world.getServer()
                    .runOnServer(
                            server -> {
                                ServerPlayer player =
                                        server.getPlayerList().getPlayers().getFirst();
                                PlayerState beforeStop = fixture(player);
                                command(player, "cs play");
                                check(Match.get().isActive(), "Second play must start");
                                Match.get().stop();
                                beforeStop.verify(player, "stop during countdown");
                                check(
                                        Match.get()
                                                .allowDamage(
                                                        player,
                                                        player.damageSources().generic(),
                                                        1),
                                        "Former participants must not remain invulnerable outside"
                                            + " an active round");
                                // Keep no stale membership while setting up the independent restart
                                // case.
                                Match.get().leave(player);
                            });
            beforeRestart =
                    world.getServer()
                            .computeOnServer(
                                    server -> {
                                        ServerPlayer player =
                                                server.getPlayerList().getPlayers().getFirst();
                                        PlayerState snapshot = fixture(player);
                                        command(player, "cs play");
                                        check(
                                                Match.get().isActive(),
                                                "Restart case must begin in an active match");
                                        return snapshot;
                                    });
            // Closing a live integrated world fires actual disconnect and SERVER_STOPPING events.
        }
        try (TestSingleplayerContext reopened = save.open()) {
            reopened.getConnection().waitForChunksRender();
            reopened.getServer()
                    .runOnServer(
                            server -> {
                                ServerPlayer player =
                                        server.getPlayerList().getPlayers().getFirst();
                                beforeRestart.verify(
                                        player, "graceful disconnect and server restart");
                                check(
                                        Match.get().state() == Match.State.LOBBY,
                                        "Restart must not revive active match");
                                check(
                                        Match.get().teamOf(player) == null,
                                        "Restart must not restore stale match membership");
                                for (CsTeam team : CsTeam.values()) {
                                    check(
                                            Match.get().memberNames(team).isEmpty(),
                                            "Restart must not restore stale bot membership");
                                }
                            });
            context.takeScreenshot("harbor-restored-after-restart");
        }
        verifyDisconnectWithoutServerRestart(context);
        System.out.println(
                "COUNTERSTRIKE_CLIENT_EVIDENCE snapshot restored after leave, stop, disconnect and"
                    + " restart");
    }

    private static void verifyDisconnectWithoutServerRestart(ClientGameTestContext context) {
        try (var server = context.worldBuilder().createServer()) {
            PlayerState original;
            try (var connection = server.connect()) {
                connection.waitForChunksRender();
                original =
                        server.computeOnServer(
                                mc -> {
                                    ServerPlayer player =
                                            mc.getPlayerList().getPlayers().getFirst();
                                    PlayerState snapshot = fixture(player);
                                    Match.get().config().ctBots = 2;
                                    Match.get().config().tBots = 2;
                                    command(player, "cs play");
                                    check(
                                            Match.get().isActive(),
                                            "Disconnect case starts in an active match");
                                    return snapshot;
                                });
            }
            server.waitFor(mc -> mc.getPlayerList().getPlayers().isEmpty());
            try (var connection = server.connect()) {
                connection.waitForChunksRender();
                server.runOnServer(
                        mc -> {
                            ServerPlayer player = mc.getPlayerList().getPlayers().getFirst();
                            original.verify(
                                    player, "disconnect/reconnect with server still running");
                            check(
                                    Match.get().teamOf(player) == null,
                                    "Reconnecting must not silently rejoin match");
                            Match.get().stop();
                        });
            }
        }
    }

    private static void command(ServerPlayer player, String command) {
        player.level()
                .getServer()
                .getCommands()
                .performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static PlayerState fixture(ServerPlayer player) {
        var server = player.level().getServer();
        player.setGameMode(GameType.CREATIVE);
        for (int x = 126; x <= 130; x++) {
            for (int z = 126; z <= 130; z++) {
                server.overworld()
                        .setBlockAndUpdate(
                                new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
                server.overworld()
                        .setBlockAndUpdate(new BlockPos(x, 80, z), Blocks.AIR.defaultBlockState());
                server.overworld()
                        .setBlockAndUpdate(new BlockPos(x, 81, z), Blocks.AIR.defaultBlockState());
            }
        }
        player.teleportTo(server.overworld(), 128.5, 80, 128.5, Set.<Relative>of(), 37, 12, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.getInventory().clearContent();
        ItemStack named = new ItemStack(Items.DIAMOND, 17);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Runtime snapshot sentinel"));
        player.getInventory().setItem(4, named);
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.setDamageValue(37);
        player.getInventory().setItem(12, tool);
        player.getInventory().setItem(40, new ItemStack(Items.GOLDEN_CARROT, 9));
        player.getInventory().setSelectedSlot(4);
        player.setHealth(13);
        player.getFoodData().setFoodLevel(7);
        player.getFoodData().setSaturation(2);
        player.experienceLevel = 12;
        player.experienceProgress = .25f;
        player.totalExperience = 250;
        return PlayerState.capture(player);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record PlayerState(
            List<net.minecraft.nbt.Tag> inventory,
            int selected,
            GameType mode,
            String dimension,
            Vec3 position,
            float yaw,
            float pitch,
            float health,
            int food,
            float saturation,
            int xpLevel,
            float xpProgress,
            int xpTotal) {
        static PlayerState capture(ServerPlayer p) {
            List<net.minecraft.nbt.Tag> items = new ArrayList<>();
            for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
                items.add(encodedStack(p, p.getInventory().getItem(slot)));
            }
            return new PlayerState(
                    items,
                    p.getInventory().getSelectedSlot(),
                    p.gameMode.getGameModeForPlayer(),
                    p.level().dimension().toString(),
                    p.position(),
                    p.getYRot(),
                    p.getXRot(),
                    p.getHealth(),
                    p.getFoodData().getFoodLevel(),
                    p.getFoodData().getSaturationLevel(),
                    p.experienceLevel,
                    p.experienceProgress,
                    p.totalExperience);
        }

        private static net.minecraft.nbt.Tag encodedStack(ServerPlayer player, ItemStack stack) {
            // Registry-backed holder identity changes across integrated-server restarts. Compare
            // exact persisted item IDs/counts/components, not Java object identity from the old
            // world.
            return ItemStack.OPTIONAL_CODEC
                    .encodeStart(
                            player.registryAccess()
                                    .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                            stack)
                    .getOrThrow();
        }

        void verify(ServerPlayer p, String phase) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                check(
                        inventory.get(slot).equals(encodedStack(p, p.getInventory().getItem(slot))),
                        phase
                                + ": inventory/component mismatch at slot "
                                + slot
                                + " expected="
                                + inventory.get(slot)
                                + " actual="
                                + encodedStack(p, p.getInventory().getItem(slot)));
            }
            check(
                    selected == p.getInventory().getSelectedSlot(),
                    phase + ": selected slot changed");
            check(mode == p.gameMode.getGameModeForPlayer(), phase + ": gamemode not restored");
            check(
                    dimension.equals(p.level().dimension().toString()),
                    phase + ": dimension not restored");
            check(
                    position.distanceToSqr(p.position()) < .01,
                    phase + ": position not restored: " + p.position());
            check(
                    Math.abs(yaw - p.getYRot()) < .1 && Math.abs(pitch - p.getXRot()) < .1,
                    phase + ": view rotation not restored");
            check(Math.abs(health - p.getHealth()) < .01, phase + ": health not restored");
            check(food == p.getFoodData().getFoodLevel(), phase + ": food not restored");
            check(
                    Math.abs(saturation - p.getFoodData().getSaturationLevel()) < .01,
                    phase + ": saturation not restored");
            check(
                    xpLevel == p.experienceLevel
                            && xpTotal == p.totalExperience
                            && Math.abs(xpProgress - p.experienceProgress) < .001,
                    phase + ": experience not restored");
        }
    }
}
