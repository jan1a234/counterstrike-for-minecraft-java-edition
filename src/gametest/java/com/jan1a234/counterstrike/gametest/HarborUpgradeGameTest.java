package com.jan1a234.counterstrike.gametest;

import com.jan1a234.counterstrike.game.arena.ArenaManager;
import com.jan1a234.counterstrike.game.arena.HarborLayout;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Synchronous fixture helper, NOT a parallel registered GameTest. Call verifyUpgrade(test) before
 * starting the serialized live-match scenario. Only use in a disposable GameTest world: this
 * temporarily installs generated v1 geometry, then restores the original ready v2 arena in finally.
 */
public final class HarborUpgradeGameTest {
    private static final int FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private HarborUpgradeGameTest() {}

    public static void verifyUpgrade(GameTestHelper test) {
        var server = test.getLevel().getServer();
        ServerLevel harbor = ArenaManager.ensureReady(server);
        test.assertTrue(harbor != server.overworld(), "Upgrade fixture must use Harbor only");
        Map<BlockPos, BlockState> original = new HashMap<>();
        for (int x = HarborLayout.MIN_X; x <= HarborLayout.MAX_X; x++) {
            for (int z = HarborLayout.MIN_Z; z <= HarborLayout.MAX_Z; z++) {
                for (int y = HarborLayout.BASE_Y; y <= HarborLayout.TOP_Y; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    original.put(pos, harbor.getBlockState(pos));
                }
            }
        }
        BlockPos[] outside = {
            new BlockPos(-41, 65, 0), new BlockPos(41, 65, 0),
            new BlockPos(0, 65, -31), new BlockPos(0, 65, 31),
            new BlockPos(0, 61, 0), new BlockPos(0, 81, 0)
        };
        Map<BlockPos, BlockState> outsideOriginal = new HashMap<>();
        for (BlockPos pos : outside) outsideOriginal.put(pos, harbor.getBlockState(pos));
        BlockPos overworldProbe = new BlockPos(0, 65, 0);
        BlockState overworldOriginal = server.overworld().getBlockState(overworldProbe);
        try {
            HarborLayout legacy = HarborLayout.legacyV1();
            for (BlockPos pos : original.keySet()) {
                put(harbor, pos, state(legacy.blockAt(pos.getX(), pos.getY(), pos.getZ())));
            }
            markers(harbor, Blocks.EMERALD_BLOCK.defaultBlockState());
            for (BlockPos pos : outside) put(harbor, pos, Blocks.DIAMOND_BLOCK.defaultBlockState());

            // Both directions of a geometry delta must preserve distinguishable player edits.
            BlockPos addedInNewWall = new BlockPos(-6, 65, 0); // v1 air -> v2 partition
            BlockPos changedOldCrate = new BlockPos(-10, 65, -5); // v1 crate -> v2 air
            BlockPos decoration = new BlockPos(0, 74, 0); // unchanged air in both versions
            BlockPos damagedSpawn = new BlockPos(-36, 64, -6);
            Map<BlockPos, BlockState> edits = new HashMap<>();
            for (BlockPos pos : new BlockPos[] {addedInNewWall, changedOldCrate, decoration}) {
                edits.put(pos, Blocks.GOLD_BLOCK.defaultBlockState());
            }
            edits.put(new BlockPos(-8, 64, 16), Blocks.AIR.defaultBlockState());
            edits.put(damagedSpawn, Blocks.STONE.defaultBlockState());
            edits.forEach((pos, state) -> put(harbor, pos, state));
            expectDamagedSpawnRefusal(test, harbor);
            test.assertTrue(
                    harbor.getBlockState(damagedSpawn).is(Blocks.STONE),
                    "v1 upgrade must not repair or erase a damaged completed spawn");
            put(harbor, damagedSpawn, Blocks.AIR.defaultBlockState());
            edits.remove(damagedSpawn);
            ArenaManager.ensureReady(server);

            HarborLayout current = new HarborLayout();
            int checked = 0;
            for (BlockPos pos : original.keySet()) {
                BlockState expected =
                        edits.getOrDefault(
                                pos,
                                isMarker(pos)
                                        ? Blocks.LAPIS_BLOCK.defaultBlockState()
                                        : state(
                                                current.blockAt(
                                                        pos.getX(), pos.getY(), pos.getZ())));
                test.assertTrue(
                        expected.equals(harbor.getBlockState(pos)),
                        "v1 -> v2 exact block mismatch at " + pos);
                checked++;
            }
            // A completed v2 chunk is never rebuilt, even at a changed authored partition.
            BlockPos v2Edit = new BlockPos(6, 65, 0);
            put(harbor, v2Edit, Blocks.DIAMOND_BLOCK.defaultBlockState());
            ArenaManager.ensureReady(server);
            test.assertTrue(
                    harbor.getBlockState(v2Edit).is(Blocks.DIAMOND_BLOCK),
                    "v2 idempotence must preserve edits to generated geometry");

            // Simulate an interrupted chunk upgrade: some deltas applied, v1 marker still present.
            BlockPos pendingDelta = new BlockPos(6, 65, 1);
            put(
                    harbor,
                    new BlockPos(0, HarborLayout.BASE_Y, 0),
                    Blocks.EMERALD_BLOCK.defaultBlockState());
            put(harbor, pendingDelta, Blocks.AIR.defaultBlockState());
            ArenaManager.ensureReady(server);
            test.assertTrue(
                    harbor.getBlockState(pendingDelta).is(Blocks.BRICKS),
                    "mixed v1/v2 upgrade must resume remaining generated deltas");
            test.assertTrue(
                    harbor.getBlockState(v2Edit).is(Blocks.DIAMOND_BLOCK),
                    "resumed upgrade must preserve edits and already upgraded cells");
            test.assertTrue(
                    harbor.getBlockState(new BlockPos(0, HarborLayout.BASE_Y, 0))
                            .is(Blocks.LAPIS_BLOCK),
                    "resumed chunk must commit v2 marker");

            put(harbor, damagedSpawn, Blocks.STONE.defaultBlockState());
            expectDamagedSpawnRefusal(test, harbor);
            test.assertTrue(
                    harbor.getBlockState(damagedSpawn).is(Blocks.STONE),
                    "completed v2 damage refusal must not erase obstruction");
            put(harbor, damagedSpawn, Blocks.AIR.defaultBlockState());
            for (BlockPos pos : outside) {
                test.assertTrue(
                        harbor.getBlockState(pos).is(Blocks.DIAMOND_BLOCK),
                        "outside owned x/y/z bounds must be untouched at " + pos);
            }
            test.assertTrue(
                    overworldOriginal.equals(server.overworld().getBlockState(overworldProbe)),
                    "Harbor upgrade must never alter overworld");
            System.out.println(
                    "COUNTERSTRIKE_RUNTIME_EVIDENCE v1->v2 upgrade exact blocks="
                            + checked
                            + " preserved edits="
                            + edits.size()
                            + " outside probes="
                            + outside.length
                            + "; v2 idempotence, interrupted resume, spawn refusal PASS");
        } finally {
            original.forEach((pos, state) -> put(harbor, pos, state));
            outsideOriginal.forEach((pos, state) -> put(harbor, pos, state));
        }
        ArenaManager.ensureReady(server);
    }

    private static void expectDamagedSpawnRefusal(GameTestHelper test, ServerLevel harbor) {
        boolean rejected = false;
        try {
            ArenaManager.ensureReady(harbor.getServer());
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Harbor-Spawn beschaedigt");
        }
        test.assertTrue(rejected, "completed damaged spawn must be rejected, not repaired");
    }

    private static void markers(ServerLevel level, BlockState state) {
        for (int cx = Math.floorDiv(HarborLayout.MIN_X, 16);
                cx <= Math.floorDiv(HarborLayout.MAX_X, 16);
                cx++) {
            for (int cz = Math.floorDiv(HarborLayout.MIN_Z, 16);
                    cz <= Math.floorDiv(HarborLayout.MAX_Z, 16);
                    cz++) {
                put(
                        level,
                        new BlockPos(
                                Math.max(HarborLayout.MIN_X, cx * 16),
                                HarborLayout.BASE_Y,
                                Math.max(HarborLayout.MIN_Z, cz * 16)),
                        state);
            }
        }
    }

    private static boolean isMarker(BlockPos pos) {
        return pos.getY() == HarborLayout.BASE_Y
                && pos.getX() == Math.max(HarborLayout.MIN_X, Math.floorDiv(pos.getX(), 16) * 16)
                && pos.getZ() == Math.max(HarborLayout.MIN_Z, Math.floorDiv(pos.getZ(), 16) * 16);
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).equals(state) && !level.setBlock(pos, state, FLAGS)) {
            throw new IllegalStateException("Upgrade fixture could not write " + pos);
        }
    }

    private static BlockState state(HarborLayout.Material material) {
        return switch (material) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case FOUNDATION -> Blocks.BEDROCK.defaultBlockState();
            case ASPHALT -> Blocks.CONCRETE.gray().defaultBlockState();
            case CONCRETE -> Blocks.SMOOTH_STONE.defaultBlockState();
            case BRICK -> Blocks.BRICKS.defaultBlockState();
            case STEEL -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case BLUE -> Blocks.CONCRETE.blue().defaultBlockState();
            case ORANGE -> Blocks.CONCRETE.orange().defaultBlockState();
            case TEAL -> Blocks.CONCRETE.cyan().defaultBlockState();
            case RED -> Blocks.CONCRETE.red().defaultBlockState();
            case CRATE -> Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState();
            case TIMBER -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case YELLOW -> Blocks.CONCRETE.yellow().defaultBlockState();
            case WHITE -> Blocks.CONCRETE.white().defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case LIGHT -> Blocks.SEA_LANTERN.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case BARRIER -> Blocks.BARRIER.defaultBlockState();
        };
    }
}
