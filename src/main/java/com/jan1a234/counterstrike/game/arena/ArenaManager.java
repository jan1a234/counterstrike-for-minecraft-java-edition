package com.jan1a234.counterstrike.game.arena;

import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.SpawnPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;

/** Server-thread-only, bounded construction of the mod-owned Harbor 17 dimension. */
public final class ArenaManager {
    public static final String NAME = "Harbor 17";
    public static final Identifier DIMENSION_ID =
            Identifier.fromNamespaceAndPath("counterstrike", "harbor");
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, DIMENSION_ID);

    /** Compatible with the existing persisted SpawnPoint / Match dimension representation. */
    public static final String DIMENSION_STRING = DIMENSION.toString();

    private static final HarborLayout LAYOUT = new HarborLayout();
    private static final HarborLayout LEGACY_V1 = HarborLayout.legacyV1();
    // Per-chunk markers commit only after geometry, in the same saved chunk. Keep a v1 marker
    // intact during its delta upgrade so an interrupted upgrade can safely resume.
    private static final BlockState COMPLETE_V1 = Blocks.EMERALD_BLOCK.defaultBlockState();
    private static final BlockState COMPLETE_V2 = Blocks.LAPIS_BLOCK.defaultBlockState();
    private static final BlockState[] PALETTE = palette();

    private ArenaManager() {}

    public static String name() {
        return NAME;
    }

    /** No overworld fallback: missing datapack registration is a visible configuration failure. */
    public static ServerLevel level(MinecraftServer server) {
        ServerLevel level = server.getLevel(DIMENSION);
        if (level == null) {
            throw new IllegalStateException(
                    "Harbor-Dimension fehlt. Mod-Datenpaket aktivieren und Server neu starten.");
        }
        if (!DIMENSION.equals(level.dimension())) {
            throw new IllegalStateException(
                    "Arena-Bau ausserhalb der Harbor-Dimension verweigert.");
        }
        return level;
    }

    /**
     * Call on the server thread before teleporting participants. Generates at most 24 chunks and
     * only the owned x/z rectangle, y=62..80; never clears or modifies another dimension. Completed
     * v1 chunks receive only v1-to-v2 geometry deltas whose current block still equals the old
     * generated block. Other edits (including removed blocks) are preserved. Placed blocks
     * identical to the old generated state cannot be distinguished; back up customized Harbor
     * worlds before upgrading. This never targets custom-map dimensions or the overworld. Unmarked
     * chunks are initial construction and replace the owned volume as before. Completed v2 chunks
     * are untouched. Every call verifies fixed spawn volumes; damage is rejected rather than
     * silently repaired or teleporting participants into a wall.
     */
    public static ServerLevel ensureReady(MinecraftServer server) {
        if (!server.isSameThread())
            throw new IllegalStateException("Arena muss auf dem Server-Thread gebaut werden.");
        ServerLevel level = level(server);
        for (int cx = Math.floorDiv(HarborLayout.MIN_X, 16);
                cx <= Math.floorDiv(HarborLayout.MAX_X, 16);
                cx++) {
            for (int cz = Math.floorDiv(HarborLayout.MIN_Z, 16);
                    cz <= Math.floorDiv(HarborLayout.MAX_Z, 16);
                    cz++) {
                buildChunk(level, cx, cz);
            }
        }
        verifySpawns(level);
        return level;
    }

    /** Fresh mutable SpawnPoint instances prevent callers from mutating the authored layout. */
    public static List<SpawnPoint> spawns(CsTeam team) {
        Objects.requireNonNull(team, "team");
        boolean east = team == CsTeam.T;
        return LAYOUT.spawns(east).stream()
                .map(
                        p ->
                                new SpawnPoint(
                                        DIMENSION_STRING,
                                        p.x() + .5,
                                        HarborLayout.FEET_Y,
                                        p.z() + .5,
                                        east ? 90f : -90f,
                                        0f))
                .toList();
    }

    public static boolean contains(Entity entity) {
        return DIMENSION.equals(entity.level().dimension())
                && contains(entity.getX(), entity.getY(), entity.getZ());
    }

    /** Coordinates only; callers handling entities should use the dimension-aware overload. */
    public static boolean contains(double x, double y, double z) {
        return LAYOUT.contains(x, y, z);
    }

    private static void buildChunk(ServerLevel level, int cx, int cz) {
        level.getChunk(cx, cz);
        int x1 = Math.max(HarborLayout.MIN_X, cx * 16),
                x2 = Math.min(HarborLayout.MAX_X, cx * 16 + 15);
        int z1 = Math.max(HarborLayout.MIN_Z, cz * 16),
                z2 = Math.min(HarborLayout.MAX_Z, cz * 16 + 15);
        BlockPos marker = new BlockPos(x1, HarborLayout.BASE_Y, z1);
        BlockState completion = level.getBlockState(marker);
        if (completion.equals(COMPLETE_V2)) return;
        boolean upgrading = completion.equals(COMPLETE_V1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // No neighbor churn, drops, block entities or scheduled fluid updates. Lighting still
        // updates.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        for (int y = HarborLayout.BASE_Y; y <= HarborLayout.TOP_Y; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    pos.set(x, y, z);
                    // Never erase the old commit marker before the upgrade is complete.
                    if (pos.equals(marker)) continue;
                    BlockState desired = PALETTE[LAYOUT.blockAt(x, y, z).ordinal()];
                    BlockState actual = level.getBlockState(pos);
                    if (upgrading) {
                        BlockState old = PALETTE[LEGACY_V1.blockAt(x, y, z).ordinal()];
                        if (old.equals(desired) || !actual.equals(old)) continue;
                    }
                    if (!actual.equals(desired) && !level.setBlock(pos, desired, flags)) {
                        throw new IllegalStateException(
                                "Harbor-Block konnte nicht gebaut werden: " + pos);
                    }
                }
            }
        }
        if (!level.setBlock(marker, COMPLETE_V2, flags)
                || !level.getBlockState(marker).equals(COMPLETE_V2)) {
            throw new IllegalStateException(
                    "Harbor-Bau konnte nicht abgeschlossen werden: " + marker);
        }
    }

    private static void verifySpawns(ServerLevel level) {
        for (CsTeam team : CsTeam.values()) {
            for (HarborLayout.Point spawn : LAYOUT.spawns(team == CsTeam.T)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos feet =
                                new BlockPos(spawn.x() + dx, HarborLayout.FEET_Y, spawn.z() + dz);
                        BlockPos floor = feet.below();
                        if (!level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)
                                || !level.getBlockState(feet).isAir()
                                || !level.getBlockState(feet.above()).isAir()
                                || !level.getFluidState(feet).isEmpty()) {
                            throw new IllegalStateException(
                                    "Harbor-Spawn beschaedigt bei "
                                            + feet
                                            + ". Hindernis entfernen; fertige Arenen werden nicht"
                                            + " automatisch ueberschrieben.");
                        }
                    }
                }
            }
        }
    }

    private static BlockState[] palette() {
        BlockState[] states = new BlockState[HarborLayout.Material.values().length];
        for (HarborLayout.Material material : HarborLayout.Material.values()) {
            states[material.ordinal()] =
                    switch (material) {
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
        return states;
    }
}
