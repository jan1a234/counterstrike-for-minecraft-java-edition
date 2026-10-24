package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.game.arena.ArenaManager;
import com.jan1a234.counterstrike.weapon.WeaponHandler;
import com.jan1a234.counterstrike.weapon.WeaponType;
import com.jan1a234.counterstrike.weapon.Weapons;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;

import java.util.Set;
import java.util.function.Consumer;

/** Actual input scenarios on the disposable range created by WeaponInputClientTest. */
final class WeaponInteractionScenarios {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT,
            RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;

    static void verify(ClientGameTestContext context, TestSingleplayerContext world) {
        for (var type : WeaponType.values()) {
            for (int button : new int[] {RIGHT, LEFT}) {
                for (boolean block : new boolean[] {false, true}) {
                    equip(context, world, type);
                    context.getInput().lookAt(-90, block ? 80 : 0);
                    context.waitTicks(3);
                    context.runOnClient(
                            client ->
                                    check(
                                            client.hitResult != null
                                                    && client.hitResult.getType()
                                                            == (block
                                                                    ? HitResult.Type.BLOCK
                                                                    : HitResult.Type.MISS),
                                            "Crosshair fixture must be "
                                                    + (block ? "BLOCK" : "MISS")));
                    click(context, button);
                    check(
                            ammo(world) == type.magazine - type.burst,
                            "One mouse trigger must fire one burst: type="
                                    + type.id
                                    + " button="
                                    + button
                                    + " block="
                                    + block
                                    + " ammo="
                                    + ammo(world));
                    player(
                            world,
                            p ->
                                    check(
                                            p.level()
                                                    .getBlockState(new BlockPos(-35, 74, 0))
                                                    .is(Blocks.STONE),
                                            "Gun must not mine the range floor"));
                }
            }
        }
        System.out.println(
                "COUNTERSTRIKE_INPUT_SINGLE_PASS all five weapons, both buttons, air and blocks");
        heldFire(context, world);
        reboundAttack(context, world);
        reload(context, world);
        closeTargets(context, world);
        guiAndNormalItems(context, world);
        System.out.println("COUNTERSTRIKE_INPUT_MATRIX_PASS all input scenarios");
    }

    private static void heldFire(ClientGameTestContext context, TestSingleplayerContext world) {
        context.getInput().lookAt(-90, 0);
        for (int button : new int[] {LEFT, RIGHT}) {
            equip(context, world, WeaponType.RIFLE);
            int start = world.getServer().computeOnServer(server -> server.getTickCount());
            context.getInput().holdMouseFor(button, 24);
            context.waitTicks(5);
            int elapsed =
                    world.getServer().computeOnServer(server -> server.getTickCount()) - start;
            int spent = WeaponType.RIFLE.magazine - ammo(world);
            check(
                    spent >= 4 && spent <= 1 + elapsed / WeaponType.RIFLE.cooldownTicks,
                    "Held trigger must repeat at server cadence; spent="
                            + spent
                            + " elapsed="
                            + elapsed
                            + " button="
                            + button);
            int releasedAmmo = ammo(world);
            context.waitTicks(12);
            check(ammo(world) == releasedAmmo, "Releasing the trigger must stop firing");
        }
        equip(context, world, WeaponType.RIFLE);
        int start = world.getServer().computeOnServer(server -> server.getTickCount());
        context.getInput().holdMouse(LEFT);
        context.getInput().holdMouse(RIGHT);
        try {
            context.waitTicks(24);
        } finally {
            context.getInput().releaseMouse(LEFT);
            context.getInput().releaseMouse(RIGHT);
        }
        context.waitTicks(5);
        int elapsed = world.getServer().computeOnServer(server -> server.getTickCount()) - start;
        check(
                WeaponType.RIFLE.magazine - ammo(world)
                        <= 1 + elapsed / WeaponType.RIFLE.cooldownTicks,
                "Both buttons cannot double server fire rate");
        System.out.println("COUNTERSTRIKE_INPUT_HELD_PASS repeat, release and dual-button cadence");
    }

    private static void reboundAttack(
            ClientGameTestContext context, TestSingleplayerContext world) {
        equip(context, world, WeaponType.RIFLE);
        String oldKey = context.computeOnClient(client -> client.options.keyAttack.saveString());
        context.runOnClient(
                client -> {
                    client.options.keyAttack.setKey(
                            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(
                                    GLFW.GLFW_KEY_G));
                    net.minecraft.client.KeyMapping.resetMapping();
                });
        try {
            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.waitTicks(10);
            check(ammo(world) == 29, "Rebound attack key must fire without a mouse button");
        } finally {
            context.runOnClient(
                    client -> {
                        client.options.keyAttack.setKey(
                                com.mojang.blaze3d.platform.InputConstants.getKey(oldKey));
                        net.minecraft.client.KeyMapping.resetMapping();
                    });
        }
        System.out.println("COUNTERSTRIKE_INPUT_REBIND_PASS actual G key invokes rebound attack");
    }

    private static void reload(ClientGameTestContext context, TestSingleplayerContext world) {
        equip(context, world, WeaponType.RIFLE);
        player(world, p -> Weapons.setAmmo(p.getMainHandItem(), 12, 60));
        context.waitTicks(5);
        context.getInput().pressKey(GLFW.GLFW_KEY_R);
        world.getServer()
                .waitFor(
                        server ->
                                WeaponHandler.isReloading(
                                        server.getPlayerList().getPlayers().getFirst()),
                        20);
        context.getInput().holdMouseFor(LEFT, 8);
        check(ammo(world) == 12, "Firing cannot consume ammunition while reloading");
        world.getServer()
                .waitFor(
                        server ->
                                !WeaponHandler.isReloading(
                                        server.getPlayerList().getPlayers().getFirst()),
                        160);
        player(
                world,
                p ->
                        check(
                                Weapons.ammo(p.getMainHandItem()) == 30
                                        && Weapons.reserve(p.getMainHandItem()) == 42,
                                "R key must refill magazine and debit the exact reserve"));
        player(world, p -> Weapons.setAmmo(p.getMainHandItem(), 0, 35));
        context.waitTicks(5);
        context.getInput().pressMouse(LEFT);
        world.getServer()
                .waitFor(
                        server ->
                                WeaponHandler.isReloading(
                                        server.getPlayerList().getPlayers().getFirst()),
                        20);
        world.getServer()
                .waitFor(
                        server ->
                                !WeaponHandler.isReloading(
                                        server.getPlayerList().getPlayers().getFirst()),
                        160);
        player(
                world,
                p ->
                        check(
                                Weapons.ammo(p.getMainHandItem()) == 30
                                        && Weapons.reserve(p.getMainHandItem()) == 5,
                                "Empty trigger must auto-reload without creating ammo"));
        click(context, LEFT);
        check(ammo(world) == 29, "Gun must fire again after reload completes");
        player(
                world,
                p -> {
                    Weapons.setAmmo(p.getMainHandItem(), 10, 60);
                    p.getInventory().setItem(1, Weapons.create(WeaponType.PISTOL));
                });
        context.waitTicks(5);
        context.getInput().pressKey(GLFW.GLFW_KEY_R);
        world.getServer()
                .waitFor(
                        server ->
                                WeaponHandler.isReloading(
                                        server.getPlayerList().getPlayers().getFirst()),
                        20);
        context.getInput().pressKey(GLFW.GLFW_KEY_2);
        world.getServer()
                .waitFor(
                        server -> {
                            var p = server.getPlayerList().getPlayers().getFirst();
                            return p.getInventory().getSelectedSlot() == 1
                                    && !WeaponHandler.isReloading(p);
                        },
                        20);
        context.waitTicks(60);
        player(
                world,
                p ->
                        check(
                                Weapons.ammo(p.getInventory().getItem(0)) == 10
                                        && Weapons.reserve(p.getInventory().getItem(0)) == 60,
                                "Switching slots cancels old magazine reload without consuming"
                                    + " reserve"));
        click(context, LEFT);
        check(ammo(world) == WeaponType.PISTOL.magazine - 1, "Switched weapon must fire");
        context.getInput().pressKey(GLFW.GLFW_KEY_1);
        context.waitTicks(5);
        System.out.println(
                "COUNTERSTRIKE_INPUT_RELOAD_PASS R, empty auto-reload, no reload fire, slot switch"
                    + " cancellation");
    }

    private static void closeTargets(ClientGameTestContext context, TestSingleplayerContext world) {
        var targetId =
                world.getServer()
                        .computeOnServer(
                                server -> {
                                    for (var e : ArenaManager.level(server).getAllEntities())
                                        if (e instanceof CsBot bot) {
                                            bot.setNoAi(true);
                                            bot.setNoGravity(true);
                                            bot.setDeltaMovement(Vec3.ZERO);
                                            bot.snapTo(-33, 75, .5, 90, 0);
                                            Match.get().breakSpawnProtection(bot);
                                            return bot.getUUID();
                                        }
                                    throw new AssertionError("Enemy fixture missing");
                                });
        context.getInput().holdKey(options -> options.keyShift);
        context.waitTicks(5);
        for (var type : WeaponType.values()) {
            for (int button : new int[] {LEFT, RIGHT}) {
                equip(context, world, type);
                world.getServer()
                        .runOnServer(
                                server -> {
                                    var bot =
                                            (CsBot) ArenaManager.level(server).getEntity(targetId);
                                    check(bot != null && bot.isAlive(), "Target remains alive");
                                    bot.snapTo(-33, 75, .5, 90, 0);
                                    bot.setDeltaMovement(Vec3.ZERO);
                                    bot.getAttribute(
                                                    net.minecraft.world.entity.ai.attributes
                                                            .Attributes.MAX_HEALTH)
                                            .setBaseValue(200);
                                    bot.setHealth(200);
                                    check(
                                            bot.getHealth() == 200,
                                            "Target health control must initialize to 200");
                                });
                context.getInput().lookAt(-90, 0);
                context.waitTicks(3);
                context.runOnClient(
                        client ->
                                check(
                                        client.hitResult != null
                                                && client.hitResult.getType()
                                                        == HitResult.Type.ENTITY,
                                        "Close enemy must be a real entity crosshair target"));
                click(context, button);
                check(
                        ammo(world) == type.magazine - type.burst,
                        "Close-target shot must consume magazine round: " + type.id);
                world.getServer()
                        .runOnServer(
                                server -> {
                                    var bot =
                                            (CsBot) ArenaManager.level(server).getEntity(targetId);
                                    check(
                                            bot != null && bot.getHealth() < 200,
                                            "Real mouse shot must damage enemy: " + type.id);
                                });
            }
        }
        player(world, p -> Weapons.setAmmo(p.getMainHandItem(), 0, 0));
        float before =
                world.getServer()
                        .computeOnServer(
                                server ->
                                        ((CsBot) ArenaManager.level(server).getEntity(targetId))
                                                .getHealth());
        click(context, LEFT);
        world.getServer()
                .runOnServer(
                        server ->
                                check(
                                        ((CsBot) ArenaManager.level(server).getEntity(targetId))
                                                        .getHealth()
                                                == before,
                                        "An empty gun cannot secretly melee the close enemy"));
        context.getInput().releaseKey(options -> options.keyShift);
        System.out.println(
                "COUNTERSTRIKE_INPUT_DAMAGE_PASS all five guns, both buttons, actual enemy damage,"
                    + " no empty-gun melee");
    }

    private static void guiAndNormalItems(
            ClientGameTestContext context, TestSingleplayerContext world) {
        equip(context, world, WeaponType.RIFLE);
        context.getInput().pressKey(GLFW.GLFW_KEY_E);
        context.waitFor(client -> client.gui.screen() != null, 20);
        context.getInput().holdMouseFor(LEFT, 8);
        context.getInput().holdMouseFor(RIGHT, 8);
        check(ammo(world) == 30, "Inventory UI clicks must not fire");
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null, 20);
        world.getServer().runOnServer(server -> Match.get().stop());
        player(
                world,
                p -> {
                    p.setGameMode(GameType.CREATIVE);
                    p.teleportTo(
                            ArenaManager.level(p.level().getServer()),
                            -35.5,
                            75,
                            .5,
                            Set.<Relative>of(),
                            -90,
                            0,
                            true);
                    p.getInventory().setItem(0, Weapons.create(WeaponType.RIFLE));
                    p.getInventory().setSelectedSlot(0);
                });
        context.getInput().pressKey(GLFW.GLFW_KEY_1);
        context.waitTicks(10);
        click(context, LEFT);
        click(context, RIGHT);
        check(ammo(world) == 30, "Nonparticipant guns must be rejected by server");
        BlockPos control = new BlockPos(-33, 76, 0);
        player(
                world,
                p -> {
                    p.getInventory().setItem(0, new ItemStack(Items.STICK));
                    p.level().setBlockAndUpdate(control, Blocks.STONE.defaultBlockState());
                });
        context.waitTicks(10);
        context.getInput().lookAt(-90, 0);
        context.waitTicks(3);
        click(context, LEFT);
        player(
                world,
                p ->
                        check(
                                p.level().getBlockState(control).isAir(),
                                "Ordinary items retain vanilla creative block breaking"));
        player(world, p -> p.level().setBlockAndUpdate(control, Blocks.CHEST.defaultBlockState()));
        context.waitTicks(8);
        context.getInput().pressMouse(RIGHT);
        context.waitFor(
                client ->
                        client.gui.screen()
                                instanceof
                                net.minecraft.client.gui.screens.inventory.ContainerScreen,
                40);
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null, 20);
        System.out.println(
                "COUNTERSTRIKE_INPUT_CONTROLS_PASS UI, nonparticipant rejection, vanilla mining and"
                    + " container use");
    }

    private static void equip(
            ClientGameTestContext context, TestSingleplayerContext world, WeaponType type) {
        player(
                world,
                p -> {
                    p.getInventory().setItem(0, Weapons.create(type));
                    p.getInventory().setSelectedSlot(0);
                });
        context.getInput().pressKey(GLFW.GLFW_KEY_1);
        context.waitTicks(35);
    }

    private static void click(ClientGameTestContext context, int button) {
        context.getInput().pressMouse(button);
        context.waitTicks(10);
    }

    private static int ammo(TestSingleplayerContext world) {
        return world.getServer()
                .computeOnServer(
                        server ->
                                Weapons.ammo(
                                        server.getPlayerList()
                                                .getPlayers()
                                                .getFirst()
                                                .getMainHandItem()));
    }

    private static void player(TestSingleplayerContext world, Consumer<ServerPlayer> action) {
        world.getServer()
                .runOnServer(
                        server -> action.accept(server.getPlayerList().getPlayers().getFirst()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
