package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.game.SpawnPoint;
import com.jan1a234.counterstrike.game.arena.ArenaManager;
import com.jan1a234.counterstrike.game.arena.HarborLayout;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One serialized scenario: independent simultaneous tests cannot share Match's singleton. */
public final class HarborRuntimeGameTest {
    @GameTest(maxTicks = 2400)
    public void harborGenerationAndLiveMatch(GameTestHelper test) {
        var server = test.getLevel().getServer();
        new InventoryIsolationGameTest().verifyIsolation(test);
        HarborUpgradeGameTest.verifyUpgrade(test);
        Match match = Match.get();
        test.assertTrue(match != null, "Real SERVER_STARTED must initialize Match");
        test.assertTrue(match.state() == Match.State.LOBBY, "Fresh test world starts in lobby");
        // Fabric can retain mod world config between launches; make this fixture explicit.
        match.config().arena = "harbor";
        // No fake tick calls, mock server/player, artificial hits or teleported opponents.
        BlockPos overworldProbe = new BlockPos(0, 64, 0);
        BlockState originalOverworld = server.overworld().getBlockState(overworldProbe);
        ServerLevel harbor = ArenaManager.ensureReady(server);
        test.assertTrue(
                harbor != server.overworld(), "Harbor must be an isolated registered dimension");
        test.assertTrue(
                harbor == ArenaManager.level(server), "level() resolves the generated dimension");
        test.assertTrue(
                originalOverworld.equals(server.overworld().getBlockState(overworldProbe)),
                "Generation must not modify overworld geometry");
        verifyGeometry(test, harbor);
        verifySpawns(test, harbor);
        verifyIdempotenceAndDamageDetection(test, harbor);

        match.config().ctBots = 4;
        match.config().tBots = 4;
        match.config().botDifficulty = "schwer";
        String error = match.start();
        test.assertTrue(error == null, "Harbor match must start without manual spawns: " + error);
        test.assertTrue(
                match.state() == Match.State.COUNTDOWN, "start enters COUNTDOWN, not RUNNING");
        test.assertTrue(match.start() != null, "Duplicate start must be rejected");
        List<CsBot> originalBots = bots(harbor);
        test.assertTrue(
                originalBots.size() == 8, "Exactly eight real bot entities must be created");
        Set<UUID> firstIds = new HashSet<>();
        Set<BlockPos> occupied = new HashSet<>();
        for (CsBot bot : originalBots) {
            firstIds.add(bot.getUUID());
            test.assertTrue(match.teamOf(bot) != null, "Bots must be real match members");
            test.assertTrue(ArenaManager.contains(bot), "Bot must spawn inside harbor");
            test.assertTrue(
                    occupied.add(bot.blockPosition()), "Initial bot deployment must not stack");
            test.assertTrue(
                    isFixedSpawn(bot, match.teamOf(bot)), "Bot must use an authored fixed spawn");
            test.assertTrue(
                    harbor.noCollision(bot), "Spawned bot must not intersect world collision");
        }
        var originalDifficulty = server.getWorldData().getDifficulty();
        test.runBeforeTestEnd(
                () -> {
                    server.setDifficulty(originalDifficulty, true);
                    match.stop();
                    match.config().arena = "harbor";
                    match.config().ctSpawns.clear();
                    match.config().tSpawns.clear();
                });
        test.startSequence()
                .thenIdle(40)
                .thenExecute(
                        () -> {
                            test.assertTrue(
                                    match.state() == Match.State.COUNTDOWN,
                                    "Countdown must not end early");
                            test.assertTrue(
                                    "CT 0 : 0 T".equals(match.scoreLine().getString()),
                                    "No countdown kills");
                            for (CsBot bot : originalBots) {
                                test.assertTrue(
                                        bot.getHealth() == bot.getMaxHealth(),
                                        "No countdown combat damage");
                                test.assertTrue(
                                        isFixedSpawn(bot, match.teamOf(bot)),
                                        "Bots must remain at fixed countdown positions: "
                                                + bot.position()
                                                + " ticks="
                                                + bot.tickCount);
                            }
                            CsBot protectedBot = originalBots.getFirst();
                            boolean damaged =
                                    protectedBot.hurtServer(
                                            harbor, harbor.damageSources().generic(), 3);
                            test.assertFalse(
                                    damaged, "Actual damage events must reject countdown damage");
                            test.assertTrue(
                                    protectedBot.getHealth() == protectedBot.getMaxHealth(),
                                    "Countdown damage protection must preserve health");
                        })
                .thenWaitUntil(
                        () ->
                                test.assertTrue(
                                        match.state() == Match.State.RUNNING,
                                        "Real server ticks must advance countdown to RUNNING"))
                .thenIdle(20)
                .thenExecute(
                        () -> {
                            for (CsBot bot : originalBots)
                                test.assertTrue(
                                        bot.tickCount > 0,
                                        "Bots must actually tick in the player-free harbor"
                                                + " dimension; entity ticks="
                                                + bot.tickCount);
                        })
                .thenWaitUntil(
                        () ->
                                test.assertTrue(
                                        !"CT 0 : 0 T".equals(match.scoreLine().getString()),
                                        "Bots must navigate from protected spawns and score a real"
                                                + " combat kill"))
                .thenExecute(
                        () -> {
                            test.assertTrue(
                                    match.isRunning(), "Combat must occur before match ends");
                            System.out.println(
                                    "COUNTERSTRIKE_RUNTIME_EVIDENCE combat tick="
                                            + test.getTick()
                                            + " score="
                                            + match.scoreLine().getString());
                            match.stop();
                            test.assertTrue(
                                    match.state() == Match.State.LOBBY, "Stop returns to lobby");
                            test.assertTrue(
                                    bots(harbor).isEmpty(), "Stop removes all bot entities");
                            test.assertTrue(
                                    server.getScoreboard().getObjective("cs_kills") == null,
                                    "Stop removes match sidebar");
                            for (CsTeam team : CsTeam.values()) {
                                test.assertTrue(
                                        match.memberNames(team).isEmpty(),
                                        "Stop removes bot memberships");
                            }
                            server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                            match.config().arena = "custom";
                            match.config().ctSpawns =
                                    new java.util.ArrayList<>(ArenaManager.spawns(CsTeam.CT));
                            match.config().tSpawns =
                                    new java.util.ArrayList<>(ArenaManager.spawns(CsTeam.T));
                            String restartError = match.start();
                            test.assertTrue(
                                    restartError == null,
                                    "Restart after stop must succeed: " + restartError);
                            test.assertTrue(
                                    match.state() == Match.State.COUNTDOWN,
                                    "Restart begins new countdown");
                            test.assertTrue(
                                    "CT 0 : 0 T".equals(match.scoreLine().getString()),
                                    "Restart resets score");
                            test.assertTrue(
                                    bots(harbor).size() == 8,
                                    "Restart recreates the configured bot count");
                            for (CsBot bot : bots(harbor)) {
                                test.assertTrue(
                                        !firstIds.contains(bot.getUUID()),
                                        "Restart must not resurrect stale bot slots");
                            }
                        })
                .thenWaitUntil(() -> test.assertTrue(match.isRunning(), "Custom round must run"))
                .thenWaitUntil(
                        () ->
                                test.assertTrue(
                                        !"CT 0 : 0 T".equals(match.scoreLine().getString()),
                                        "Player-free custom match must tick bots across intervening"
                                                + " non-spawn chunks"))
                .thenExecute(
                        () -> {
                            System.out.println(
                                    "COUNTERSTRIKE_RUNTIME_EVIDENCE custom combat tick="
                                            + test.getTick());
                            match.stop();
                            test.assertTrue(bots(harbor).isEmpty(), "Second stop also cleans up");
                        })
                .thenSucceed();
    }

    private static void verifyGeometry(GameTestHelper test, ServerLevel harbor) {
        HarborLayout layout = new HarborLayout();
        int checked = 0;
        for (int x = HarborLayout.MIN_X; x <= HarborLayout.MAX_X; x++) {
            for (int z = HarborLayout.MIN_Z; z <= HarborLayout.MAX_Z; z++) {
                for (int y = HarborLayout.FLOOR_Y; y <= HarborLayout.TOP_Y; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    HarborLayout.Material material = layout.blockAt(x, y, z);
                    BlockState actual = harbor.getBlockState(pos);
                    test.assertTrue(
                            actual.isCollisionShapeFullBlock(harbor, pos) == material.solid(),
                            "Generated collision disagrees with authored map at "
                                    + pos
                                    + ": "
                                    + actual);
                    if (material == HarborLayout.Material.AIR) {
                        test.assertTrue(
                                actual.isAir(), "Authored clear space obstructed at " + pos);
                    } else if (material == HarborLayout.Material.WATER) {
                        test.assertTrue(
                                !harbor.getFluidState(pos).isEmpty(),
                                "Quay water missing at " + pos);
                    }
                    checked++;
                }
            }
        }
        System.out.println("COUNTERSTRIKE_RUNTIME_EVIDENCE real generated block checks=" + checked);
    }

    private static void verifySpawns(GameTestHelper test, ServerLevel harbor) {
        Set<BlockPos> distinct = new HashSet<>();
        for (CsTeam team : CsTeam.values()) {
            List<SpawnPoint> points = ArenaManager.spawns(team);
            test.assertTrue(
                    points.size() == 16, "Each team must have 16 fixed safe deployment positions");
            for (SpawnPoint spawn : points) {
                BlockPos feet = BlockPos.containing(spawn.x, spawn.y, spawn.z);
                test.assertTrue(distinct.add(feet), "Team spawn positions must be distinct");
                test.assertTrue(
                        spawn.dimension.equals(harbor.dimension().toString()),
                        "Spawn dimension must resolve");
                test.assertTrue(
                        team == CsTeam.CT ? spawn.x < -25 : spawn.x > 25,
                        "Team spawns must stay inside their protected compound");
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = feet.offset(dx, 0, dz);
                        test.assertTrue(
                                harbor.getBlockState(p.below())
                                        .isCollisionShapeFullBlock(harbor, p.below()),
                                "Spawn needs a solid safe floor at " + p);
                        test.assertTrue(
                                harbor.getBlockState(p).isAir()
                                        && harbor.getBlockState(p.above()).isAir(),
                                "Spawn needs two blocks of headroom at " + p);
                        test.assertTrue(
                                harbor.getFluidState(p).isEmpty(), "Spawn must not contain fluid");
                    }
                }
            }
            double originalX = points.getFirst().x;
            points.getFirst().x = 9999;
            test.assertTrue(
                    ArenaManager.spawns(team).getFirst().x == originalX,
                    "Callers must not mutate authored fixed spawn coordinates");
        }
    }

    private static void verifyIdempotenceAndDamageDetection(
            GameTestHelper test, ServerLevel harbor) {
        BlockPos decoration = new BlockPos(0, 74, 0);
        BlockState previous = harbor.getBlockState(decoration);
        harbor.setBlockAndUpdate(decoration, Blocks.GOLD_BLOCK.defaultBlockState());
        ArenaManager.ensureReady(harbor.getServer());
        test.assertTrue(
                harbor.getBlockState(decoration).is(Blocks.GOLD_BLOCK),
                "Repeated ensureReady must not erase a completed arena");
        harbor.setBlockAndUpdate(decoration, previous);
        SpawnPoint spawn = ArenaManager.spawns(CsTeam.CT).getFirst();
        BlockPos feet = BlockPos.containing(spawn.x, spawn.y, spawn.z);
        harbor.setBlockAndUpdate(feet, Blocks.STONE.defaultBlockState());
        boolean rejected = false;
        try {
            ArenaManager.ensureReady(harbor.getServer());
        } catch (IllegalStateException expected) {
            rejected = true;
        } finally {
            harbor.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        }
        test.assertTrue(
                rejected, "Completed but obstructed spawn must fail instead of unsafe teleport");
        ArenaManager.ensureReady(harbor.getServer());
    }

    private static boolean isFixedSpawn(CsBot bot, CsTeam team) {
        return ArenaManager.spawns(team).stream()
                .anyMatch(
                        p ->
                                Math.abs(p.x - bot.getX()) < .001
                                        && Math.abs(p.y - bot.getY()) < .001
                                        && Math.abs(p.z - bot.getZ()) < .001);
    }

    private static List<CsBot> bots(ServerLevel harbor) {
        List<CsBot> result = new ArrayList<>();
        for (Entity entity : harbor.getEntities(null, new AABB(-64, 0, -64, 64, 128, 64))) {
            if (entity instanceof CsBot bot && !bot.isRemoved()) result.add(bot);
        }
        return result;
    }
}
