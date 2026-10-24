package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.game.arena.ArenaManager;
import com.jan1a234.counterstrike.weapon.WeaponType;
import com.jan1a234.counterstrike.weapon.Weapons;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Set;

/** Review regressions: true same-tick selection/fire and the vanilla spectator camera action. */
final class WeaponEdgeInputTest {
    static void verify(ClientGameTestContext context, TestSingleplayerContext world) {
        var errors = new ArrayList<String>();
        context.getInput().lookAt(-90, 0);
        for (int button : new int[] {GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOUSE_BUTTON_RIGHT}) {
            world.getServer()
                    .runOnServer(
                            server -> {
                                var p = server.getPlayerList().getPlayers().getFirst();
                                p.getInventory().setItem(0, Weapons.create(WeaponType.RIFLE));
                                p.getInventory().setItem(1, Weapons.create(WeaponType.PISTOL));
                            });
            context.getInput().pressKey(GLFW.GLFW_KEY_1);
            context.waitTicks(35);
            int tick = context.computeOnClient(client -> client.player.tickCount);
            // holdKey/holdMouse enqueue the real callbacks without advancing a tick.
            context.getInput().holdKey(GLFW.GLFW_KEY_2);
            context.getInput().holdMouse(button);
            if (context.computeOnClient(client -> client.player.tickCount) != tick)
                throw new AssertionError("Same-tick fixture unexpectedly advanced the client");
            try {
                context.waitTick();
            } finally {
                context.getInput().releaseKey(GLFW.GLFW_KEY_2);
                context.getInput().releaseMouse(button);
            }
            context.waitTicks(10);
            String error =
                    world.getServer()
                            .computeOnServer(
                                    server -> {
                                        var p = server.getPlayerList().getPlayers().getFirst();
                                        int rifle = Weapons.ammo(p.getInventory().getItem(0));
                                        int pistol = Weapons.ammo(p.getInventory().getItem(1));
                                        return rifle == 30 && pistol == 11
                                                ? null
                                                : "same-tick slot/fire button="
                                                        + button
                                                        + " wrong ammunition: rifle="
                                                        + rifle
                                                        + " pistol="
                                                        + pistol;
                                    });
            if (error != null) errors.add(error);
        }
        var targetId =
                world.getServer()
                        .computeOnServer(
                                server -> {
                                    var p = server.getPlayerList().getPlayers().getFirst();
                                    p.getInventory().setItem(0, Weapons.create(WeaponType.RIFLE));
                                    p.setGameMode(GameType.SPECTATOR);
                                    for (var e : ArenaManager.level(server).getAllEntities())
                                        if (e instanceof CsBot bot) {
                                            bot.setNoAi(true);
                                            bot.setNoGravity(true);
                                            bot.setDeltaMovement(Vec3.ZERO);
                                            bot.snapTo(-33, 75, .5, 90, 0);
                                            return bot.getUUID();
                                        }
                                    throw new AssertionError("Spectator target missing");
                                });
        // Spectator number keys select hotbar UI; set held slot as fixture, not the action under
        // test.
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
        world.getServer()
                .runOnServer(
                        server ->
                                server.getPlayerList()
                                        .getPlayers()
                                        .getFirst()
                                        .getInventory()
                                        .setSelectedSlot(0));
        context.waitTicks(10);
        context.getInput().lookAt(-90, 0);
        context.waitTicks(3);
        context.runOnClient(
                client -> {
                    if (!client.player.isSpectator()
                            || client.hitResult == null
                            || client.hitResult.getType() != HitResult.Type.ENTITY)
                        throw new AssertionError("Spectator fixture must target a real entity");
                });
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTicks(10);
        boolean spectates =
                context.computeOnClient(
                        client ->
                                client.getCameraEntity() != null
                                        && client.getCameraEntity().getUUID().equals(targetId));
        if (!spectates) errors.add("spectator gun must retain vanilla entity-camera targeting");
        world.getServer()
                .runOnServer(
                        server -> {
                            var p = server.getPlayerList().getPlayers().getFirst();
                            if (Weapons.ammo(p.getInventory().getItem(0)) != 30)
                                throw new AssertionError("Spectator must not shoot");
                            p.setCamera(p);
                            p.setGameMode(GameType.ADVENTURE);
                            p.teleportTo(
                                    ArenaManager.level(server),
                                    -35.5,
                                    75,
                                    .5,
                                    Set.<Relative>of(),
                                    -90,
                                    0,
                                    true);
                            var target = ArenaManager.level(server).getEntity(targetId);
                            target.snapTo(-20.5, 75, 3.5, 90, 0);
                            target.setDeltaMovement(Vec3.ZERO);
                        });
        context.waitTicks(10);
        if (!errors.isEmpty()) throw new AssertionError("Input review regressions: " + errors);
        System.out.println(
                "COUNTERSTRIKE_INPUT_EDGES_PASS same-tick switch/fire both buttons, spectator"
                    + " camera preserved");
    }
}
