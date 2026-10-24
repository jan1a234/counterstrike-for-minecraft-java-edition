package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.game.SpawnPoint;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Run separately from HarborRuntimeGameTest: both deliberately exercise the real Match singleton.
 */
public final class InventoryIsolationGameTest {
    // Called synchronously before the Harbor lifecycle; never run singleton-mutating
    // scenarios concurrently just because GameTest permits parallel test functions.
    public void verifyIsolation(GameTestHelper test) {
        Match match = Match.get();
        test.assertTrue(match != null && !match.isActive(), "Requires an idle real server Match");
        var level = test.getLevel();
        var config = match.config();
        String oldArena = config.arena;
        int oldCtBots = config.ctBots;
        int oldTBots = config.tBots;
        var oldCtSpawns = config.ctSpawns;
        var oldTSpawns = config.tSpawns;
        List<EmbeddedChannel> channels = new ArrayList<>();
        ServerPlayer participant = player(test, "isolation-player", channels);
        ServerPlayer outsider = player(test, "isolation-other", channels);
        BlockPos feet = test.absolutePos(new BlockPos(1, 2, 1));
        BlockPos chestPos = feet.offset(2, 0, 0);
        BlockPos enderPos = feet.offset(3, 0, 0);
        var oldFloor = level.getBlockState(feet.below());
        var oldFeet = level.getBlockState(feet);
        var oldHead = level.getBlockState(feet.above());
        var oldChest = level.getBlockState(chestPos);
        var oldEnder = level.getBlockState(enderPos);
        List<ItemEntity> fixtureItems = new ArrayList<>();
        try {
            level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(enderPos, Blocks.ENDER_CHEST.defaultBlockState());
            participant.teleportTo(
                    level,
                    feet.getX() + .5,
                    feet.getY(),
                    feet.getZ() + .5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
            outsider.teleportTo(
                    level,
                    feet.getX() + .5,
                    feet.getY(),
                    feet.getZ() + .5,
                    java.util.Set.of(),
                    0,
                    0,
                    true);
            config.arena = "custom";
            config.ctBots = config.tBots = 0;
            SpawnPoint spawn =
                    new SpawnPoint(
                            Match.dimensionId(level),
                            feet.getX() + .5,
                            feet.getY(),
                            feet.getZ() + .5,
                            0,
                            0);
            config.ctSpawns = new ArrayList<>(List.of(spawn));
            config.tSpawns = new ArrayList<>(List.of(spawn));
            participant.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 11));
            participant.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
            chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 13));
            match.join(participant, CsTeam.CT);
            // Membership alone in the lobby must not block ordinary survival transactions.
            test.assertTrue(
                    participant.openMenu(chest).isPresent(), "Lobby team member can open chest");
            participant.closeContainer();
            String error = match.start();
            test.assertTrue(
                    error == null && match.isActive(), "Real custom match starts: " + error);
            test.assertTrue(match.teamOf(outsider) == null, "Control player is not a participant");
            List<String> failures = new ArrayList<>();
            List<ItemStack> issued = inventory(participant);

            // Use the exact server-side block methods, with an EMPTY hand, not Fabric callbacks.
            participant.getInventory().setSelectedSlot(8);
            useBlock(test, participant, chestPos);
            check(
                    failures,
                    participant.containerMenu == participant.inventoryMenu,
                    "empty-hand chest interaction must not open a menu");
            participant.closeContainer();
            useBlock(test, participant, enderPos);
            check(
                    failures,
                    participant.containerMenu == participant.inventoryMenu,
                    "empty-hand ender chest interaction must not open a menu");
            participant.closeContainer();
            check(
                    failures,
                    participant.openMenu(chest).isEmpty(),
                    "direct server openMenu bypass rejected");
            participant.closeContainer();
            Horse horse = new Horse(EntityTypes.HORSE, level);
            participant.openHorseInventory(horse, new SimpleContainer(2));
            check(
                    failures,
                    participant.containerMenu == participant.inventoryMenu,
                    "horse inventory uses a separate open path and must be blocked");
            participant.closeContainer();

            // A stale/forged external menu cannot import or export even if it reaches clicked().
            ChestMenu stale = ChestMenu.threeRows(91, participant.getInventory(), chest);
            stale.clicked(0, 0, ContainerInput.QUICK_MOVE, participant);
            check(
                    failures,
                    chest.getItem(0).getCount() == 13,
                    "stale menu cannot import survival items");
            stale.clicked(54, 0, ContainerInput.QUICK_MOVE, participant);
            check(
                    failures,
                    sameInventory(issued, participant),
                    "stale menu cannot export match equipment");
            stale.removed(participant);

            participant.getInventory().setSelectedSlot(0);
            int beforeDrops = groundItems(test);
            participant.drop(false);
            participant.drop(true);
            participant.connection.handlePlayerAction(
                    new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
                            BlockPos.ZERO,
                            Direction.DOWN));
            check(
                    failures,
                    sameInventory(issued, participant),
                    "Q, Ctrl-Q and packet drop preserve every slot");
            check(
                    failures,
                    participant.drop(issued.getFirst().copy(), false, true) == null,
                    "direct three-argument drop cannot export equipment");
            check(
                    failures,
                    participant.drop(issued.getFirst().copy(), false) == null,
                    "inherited two-argument drop cannot export equipment");
            participant.inventoryMenu.clicked(36, 0, ContainerInput.THROW, participant);
            check(
                    failures,
                    sameInventory(issued, participant),
                    "inventory THROW preserves source slot");
            ItemStack carried = new ItemStack(Items.IRON_SWORD);
            participant.inventoryMenu.setCarried(carried);
            participant.inventoryMenu.clicked(-999, 0, ContainerInput.PICKUP, participant);
            check(
                    failures,
                    ItemStack.matches(carried, participant.inventoryMenu.getCarried()),
                    "outside cursor drop preserves carried stack");
            participant.inventoryMenu.setCarried(ItemStack.EMPTY);
            check(failures, groundItems(test) == beforeDrops, "no drop path creates world items");

            ItemEntity survivalDrop =
                    new ItemEntity(
                            level,
                            participant.getX(),
                            participant.getY(),
                            participant.getZ(),
                            new ItemStack(Items.NETHERITE_INGOT, 5));
            survivalDrop.setNoPickUpDelay();
            level.addFreshEntity(survivalDrop);
            fixtureItems.add(survivalDrop);
            survivalDrop.playerTouch(participant);
            check(
                    failures,
                    !survivalDrop.isRemoved() && survivalDrop.getItem().getCount() == 5,
                    "pickup must leave preexisting survival item entity intact");
            check(
                    failures,
                    sameInventory(issued, participant),
                    "pickup must not change inventory total or slots");
            var arrow =
                    new net.minecraft.world.entity.projectile.arrow.Arrow(EntityTypes.ARROW, level);
            arrow.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.ALLOWED;
            arrow.setNoPhysics(true); // vanilla's settled/no-physics pickup eligibility branch
            int arrowsBefore = participant.getInventory().countItem(Items.ARROW);
            arrow.playerTouch(participant);
            check(
                    failures,
                    !arrow.isRemoved()
                            && participant.getInventory().countItem(Items.ARROW) == arrowsBefore,
                    "projectile pickup must not insert into inventory or remove the world"
                        + " projectile");
            arrow.playerTouch(outsider);
            check(
                    failures,
                    arrow.isRemoved() && outsider.getInventory().countItem(Items.ARROW) == 1,
                    "outsider can still pick up the preserved projectile");
            arrow.discard();

            var orb =
                    new net.minecraft.world.entity.ExperienceOrb(
                            level, participant.getX(), participant.getY(), participant.getZ(), 5);
            level.addFreshEntity(orb);
            try {
                int experience = participant.totalExperience;
                orb.playerTouch(participant);
                check(
                        failures,
                        !orb.isRemoved() && participant.totalExperience == experience,
                        "Survival XP orb must not be consumed then lost at snapshot restoration");
                int outsiderExperience = outsider.totalExperience;
                orb.playerTouch(outsider);
                check(
                        failures,
                        orb.isRemoved() && outsider.totalExperience == outsiderExperience + 5,
                        "Outsider can pick up the preserved XP orb");
            } finally {
                orb.discard();
            }

            ArmorStand stand =
                    new ArmorStand(
                            level, participant.getX(), participant.getY(), participant.getZ());
            stand.setShowArms(true);
            participant.getInventory().setSelectedSlot(0);
            participant.getInventory().setItem(0, issued.getFirst().copy());
            ItemStack heldBefore = participant.getMainHandItem().copy();
            participant.interactOn(stand, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0));
            check(
                    failures,
                    ItemStack.matches(heldBefore, participant.getMainHandItem())
                            && stand.getMainHandItem().isEmpty(),
                    "armor stand cannot receive issued equipment");
            participant.getInventory().setSelectedSlot(8);
            stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            participant.interactOn(stand, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0));
            check(
                    failures,
                    stand.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)
                            && participant.getMainHandItem().isEmpty(),
                    "armor stand cannot import survival armor");

            // Normal inventory movement is still allowed (not a blanket inventory-input ban).
            participant.inventoryMenu.clicked(36, 0, ContainerInput.PICKUP, participant);
            check(
                    failures,
                    !participant.inventoryMenu.getCarried().isEmpty(),
                    "participant can rearrange inventory");
            participant.inventoryMenu.clicked(36, 0, ContainerInput.PICKUP, participant);
            check(
                    failures,
                    participant.inventoryMenu.getCarried().isEmpty(),
                    "cursor can return to its source slot");

            check(
                    failures,
                    outsider.openMenu(chest).isPresent(),
                    "active match does not block outsider menus");
            outsider.closeContainer();
            survivalDrop.playerTouch(outsider);
            check(
                    failures,
                    survivalDrop.isRemoved()
                            && outsider.getInventory().countItem(Items.NETHERITE_INGOT) == 5,
                    "outsider can still pick up the unchanged survival drop");
            outsider.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            outsider.getInventory().setSelectedSlot(0);
            outsider.drop(false);
            check(
                    failures,
                    outsider.getInventory().getItem(0).getCount() == 2,
                    "outsider can still drop one item");

            match.leave(participant);
            check(
                    failures,
                    participant.getInventory().countItem(Items.DIAMOND) == 11,
                    "leave restores original survival inventory");
            check(
                    failures,
                    participant.getInventory().countItem(Items.GOLD_INGOT) == 0
                            && participant.getInventory().countItem(Items.NETHERITE_INGOT) == 0,
                    "no imported survival items cross the restore boundary");
            check(
                    failures,
                    chest.getItem(0).is(Items.GOLD_INGOT) && chest.getItem(0).getCount() == 13,
                    "chest survival total remains unchanged after leave");
            check(
                    failures,
                    participant.getEnderChestInventory().getItem(0).getCount() == 7,
                    "ender chest survival total remains unchanged after leave");
            check(
                    failures,
                    participant.openMenu(chest).isPresent(),
                    "leaver is immediately unrestricted");
            participant.closeContainer();
            match.stop();
            match.join(participant, CsTeam.CT);
            test.assertTrue(match.start() == null, "second lifecycle starts");
            match.stop();
            check(
                    failures,
                    match.teamOf(participant) != null && participant.openMenu(chest).isPresent(),
                    "stale team membership after stop does not restrict survival");
            participant.closeContainer();
            test.assertTrue(failures.isEmpty(), String.join("; ", failures));
            System.out.println(
                    "COUNTERSTRIKE_INVENTORY_EVIDENCE storage, drops, pickups, equipment and"
                        + " restoration verified");
        } finally {
            participant.inventoryMenu.setCarried(ItemStack.EMPTY);
            participant.closeContainer();
            outsider.closeContainer();
            match.leave(participant);
            match.stop();
            config.arena = oldArena;
            config.ctBots = oldCtBots;
            config.tBots = oldTBots;
            config.ctSpawns = oldCtSpawns;
            config.tSpawns = oldTSpawns;
            fixtureItems.forEach(ItemEntity::discard);
            level.getServer().getPlayerList().remove(participant);
            level.getServer().getPlayerList().remove(outsider);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            level.setBlockAndUpdate(feet.below(), oldFloor);
            level.setBlockAndUpdate(feet, oldFeet);
            level.setBlockAndUpdate(feet.above(), oldHead);
            level.setBlockAndUpdate(chestPos, oldChest);
            level.setBlockAndUpdate(enderPos, oldEnder);
        }
    }

    private static ServerPlayer player(
            GameTestHelper test, String name, List<EmbeddedChannel> channels) {
        var cookie =
                CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        var player =
                new ServerPlayer(
                        test.getLevel().getServer(),
                        test.getLevel(),
                        cookie.gameProfile(),
                        cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));
        test.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void useBlock(GameTestHelper test, ServerPlayer player, BlockPos position) {
        test.getLevel()
                .getBlockState(position)
                .useWithoutItem(
                        test.getLevel(),
                        player,
                        new BlockHitResult(
                                Vec3.atCenterOf(position), Direction.UP, position, false));
    }

    private static List<ItemStack> inventory(ServerPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            result.add(player.getInventory().getItem(slot).copy());
        }
        return result;
    }

    private static boolean sameInventory(List<ItemStack> expected, ServerPlayer player) {
        for (int slot = 0; slot < expected.size(); slot++) {
            if (!ItemStack.matches(expected.get(slot), player.getInventory().getItem(slot)))
                return false;
        }
        return true;
    }

    private static int groundItems(GameTestHelper test) {
        int total = 0;
        for (var entity : test.getLevel().getAllEntities()) {
            if (entity instanceof ItemEntity item && !item.isRemoved())
                total += item.getItem().getCount();
        }
        return total;
    }

    private static void check(List<String> failures, boolean condition, String message) {
        if (!condition) failures.add(message);
    }
}
