package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.bot.BotSlot;
import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.game.CsClass;
import com.jan1a234.counterstrike.game.CsTeam;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.UUID;

/** Real MC 26.2 entity calls, with vanilla controls; no Match singleton or mocked world. */
public final class BotSafetyGameTest {
    @GameTest
    public void peacefulDespawnKeepsBotButNotVanillaZombie(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        var server = level.getServer();
        Difficulty previous = level.getDifficulty();
        CsBot bot = newBot(level);
        Zombie ordinary = new Zombie(level);
        ordinary.setPersistenceRequired();
        try {
            // Synchronous scope: no server tick can see the temporary test difficulty.
            server.setDifficulty(Difficulty.PEACEFUL, true);
            test.assertTrue(
                    level.getDifficulty() == Difficulty.PEACEFUL,
                    "Regression must actually exercise Peaceful, not the server default");
            test.assertTrue(bot.isPersistenceRequired(), "Bot begins persistent in memory");
            ordinary.checkDespawn();
            bot.checkDespawn();
            System.out.println(
                    "COUNTERSTRIKE_BOT_SAFETY peaceful botRemoved="
                            + bot.isRemoved()
                            + " vanillaRemoved="
                            + ordinary.isRemoved());
            test.assertTrue(
                    ordinary.isRemoved(),
                    "Vanilla persistent zombie control must still despawn in Peaceful");
            test.assertFalse(
                    bot.isRemoved(), "Match-owned bot must survive real checkDespawn in Peaceful");
            test.assertTrue(
                    level.getDifficulty() == Difficulty.PEACEFUL,
                    "Bot must not change the world's difficulty to avoid despawning");
        } finally {
            bot.discard();
            ordinary.discard();
            server.setDifficulty(previous, true);
        }
        test.succeed();
    }

    @GameTest
    public void chunkSaveGateRejectsLiveBot(GameTestHelper test) {
        CsBot bot = newBot(test.getLevel());
        Zombie ordinary = new Zombie(test.getLevel());
        try {
            test.assertFalse(bot.isRemoved(), "Save regression requires a live bot");
            test.assertTrue(ordinary.shouldBeSaved(), "Vanilla zombie control remains saveable");
            boolean saveable = bot.shouldBeSaved();
            System.out.println("COUNTERSTRIKE_BOT_SAFETY shouldBeSaved=" + saveable);
            test.assertFalse(saveable, "Live match bot must never enter chunk entity storage");
        } finally {
            bot.discard();
            ordinary.discard();
        }
        test.succeed();
    }

    @GameTest
    public void directSaveRejectsLiveBotWithoutWritingZombieData(GameTestHelper test) {
        CsBot bot = newBot(test.getLevel());
        Zombie ordinary = new Zombie(test.getLevel());
        try {
            TagValueOutput control = output(test.getLevel());
            test.assertTrue(ordinary.save(control), "Vanilla direct save control must succeed");
            test.assertTrue(control.buildResult().contains("id"), "Control writes its entity type");
            TagValueOutput data = output(test.getLevel());
            boolean saved = bot.save(data);
            System.out.println("COUNTERSTRIKE_BOT_SAFETY save=" + saved);
            test.assertFalse(saved, "Direct save must not serialize a bot as an unowned zombie");
            test.assertTrue(
                    data.buildResult().isEmpty(), "Rejected bot save must not write entity data");
        } finally {
            bot.discard();
            ordinary.discard();
        }
        test.succeed();
    }

    @GameTest
    public void passengerSaveCannotBypassBotSaveProtection(GameTestHelper test) {
        CsBot bot = newBot(test.getLevel());
        Zombie ordinary = new Zombie(test.getLevel());
        try {
            TagValueOutput control = output(test.getLevel());
            test.assertTrue(
                    ordinary.saveAsPassenger(control),
                    "Vanilla passenger save control must succeed");
            TagValueOutput data = output(test.getLevel());
            boolean saved = bot.saveAsPassenger(data);
            System.out.println("COUNTERSTRIKE_BOT_SAFETY saveAsPassenger=" + saved);
            test.assertFalse(
                    saved, "Passenger serialization must not bypass ephemeral bot protection");
            test.assertTrue(
                    data.buildResult().isEmpty(), "Rejected passenger save must write no data");
        } finally {
            bot.discard();
            ordinary.discard();
        }
        test.succeed();
    }

    @GameTest
    public void ownershipMarkerIsExclusiveToNewBots(GameTestHelper test) {
        CsBot bot = newBot(test.getLevel());
        Zombie ordinary = new Zombie(test.getLevel());
        try {
            test.assertFalse(
                    ordinary.entityTags().contains("counterstrike:ephemeral_bot"),
                    "Ordinary zombies must not acquire the mod ownership marker");
            test.assertTrue(
                    bot.entityTags().contains("counterstrike:ephemeral_bot"),
                    "New bots must carry an explicit namespaced ownership marker");
        } finally {
            bot.discard();
            ordinary.discard();
        }
        test.succeed();
    }

    @GameTest
    public void deathCannotExportIssuedEquipment(GameTestHelper test) {
        var level = test.getLevel();
        var feet = test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        CsBot bot = newBot(level);
        bot.snapTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
        bot.setItemSlot(
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.DIAMOND_SWORD));
        bot.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2.0f);
        var box = bot.getBoundingBox().inflate(3);
        int before =
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)
                        .size();
        level.addFreshEntity(bot);
        try {
            test.assertTrue(
                    bot.hurtServer(level, level.damageSources().genericKill(), 1000),
                    "Actual bot death must occur");
            test.assertTrue(
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)
                                    .size()
                            == before,
                    "Dead bots must not export equipment or vanilla loot into survival");
        } finally {
            bot.discard();
        }
        test.succeed();
    }

    private static CsBot newBot(ServerLevel level) {
        return new CsBot(
                level, new BotSlot(UUID.randomUUID(), "SafetyTestBot", CsTeam.CT, CsClass.ASSAULT));
    }

    private static TagValueOutput output(ServerLevel level) {
        return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
    }
}
