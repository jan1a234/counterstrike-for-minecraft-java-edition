package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.game.arena.ArenaManager;
import com.jan1a234.counterstrike.weapon.WeaponType;
import com.jan1a234.counterstrike.weapon.Weapons;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;

import java.util.Set;

/**
 * Mouse -> Minecraft input -> network -> authoritative ammo/damage. Never invokes onUse/tryFire.
 */
public final class WeaponInputClientTest {
    public static void verify(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer()
                    .runOnServer(
                            server -> {
                                var p = server.getPlayerList().getPlayers().getFirst();
                                var match = Match.get();
                                match.config().arena = "harbor";
                                match.config().ctBots = 0;
                                match.config().tBots = 1;
                                match.join(p, CsTeam.CT);
                                String error = match.start();
                                check(error == null, "Input fixture could not start: " + error);
                            });
            world.getServer().waitFor(server -> Match.get().isRunning(), 600);
            world.getServer()
                    .runOnServer(
                            server -> {
                                var level = ArenaManager.level(server);
                                for (var entity : level.getAllEntities()) {
                                    if (entity instanceof CsBot bot) {
                                        bot.setNoAi(true);
                                        bot.setDeltaMovement(Vec3.ZERO);
                                    }
                                }
                                // Disposable shooting range above the authored geometry, inside
                                // arena bounds.
                                for (int x = -38; x <= -20; x++)
                                    for (int z = -4; z <= 4; z++) {
                                        level.setBlockAndUpdate(
                                                new BlockPos(x, 74, z),
                                                Blocks.STONE.defaultBlockState());
                                        for (int y = 75; y <= 79; y++)
                                            level.setBlockAndUpdate(
                                                    new BlockPos(x, y, z),
                                                    Blocks.AIR.defaultBlockState());
                                    }
                                var p = server.getPlayerList().getPlayers().getFirst();
                                p.teleportTo(
                                        level, -35.5, 75, .5, Set.<Relative>of(), -90, 0, true);
                                p.setDeltaMovement(Vec3.ZERO);
                            });
            world.getConnection().waitForChunksRender();
            context.waitTicks(10);
            for (var type : WeaponType.values()) {
                equip(context, world, type);
                context.getInput().lookAt(-90, 0);
                context.waitTicks(3);
                context.runOnClient(
                        client ->
                                check(
                                        client.hitResult != null
                                                && client.hitResult.getType()
                                                        == HitResult.Type.MISS,
                                        "Fixture must aim into air, not a nearby block/entity"));
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                context.waitTicks(10);
                world.getServer()
                        .runOnServer(
                                server -> {
                                    var p = server.getPlayerList().getPlayers().getFirst();
                                    check(
                                            Weapons.ammo(p.getMainHandItem())
                                                    == type.magazine - type.burst,
                                            "Right mouse into air must fire exactly one trigger for"
                                                    + " "
                                                    + type.id
                                                    + "; ammo="
                                                    + Weapons.ammo(p.getMainHandItem()));
                                });
            }
            System.out.println("COUNTERSTRIKE_INPUT_RIGHT_AIR_PASS all five weapons");
            equip(context, world, WeaponType.RIFLE);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitTicks(10);
            world.getServer()
                    .runOnServer(
                            server -> {
                                var p = server.getPlayerList().getPlayers().getFirst();
                                check(
                                        Weapons.ammo(p.getMainHandItem())
                                                == WeaponType.RIFLE.magazine - 1,
                                        "Left mouse into air must fire a bullet instead of"
                                                + " swinging; ammo="
                                                + Weapons.ammo(p.getMainHandItem()));
                            });
            WeaponEdgeInputTest.verify(context, world);
            WeaponInteractionScenarios.verify(context, world);
            world.getServer().runOnServer(server -> Match.get().stop());
        }
        System.out.println(
                "COUNTERSTRIKE_INPUT_EVIDENCE real mouse right/air reaches server for every"
                        + " weapon");
    }

    private static void equip(
            ClientGameTestContext context, TestSingleplayerContext world, WeaponType type) {
        world.getServer()
                .runOnServer(
                        server -> {
                            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                            p.getInventory().setItem(0, Weapons.create(type));
                            p.getInventory().setSelectedSlot(0);
                        });
        context.waitTicks(35);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
