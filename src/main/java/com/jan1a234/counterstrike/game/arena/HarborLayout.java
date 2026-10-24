package com.jan1a234.counterstrike.game.arena;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Harbor 17: an authored, deterministic 81 x 61 block tactical arena. North: container yard;
 * center: covered warehouse; south: quayside. All combat routes share one level, with no doors,
 * ladders, jumps or water crossings. This model deliberately has no Minecraft dependencies: the
 * builder and tests consume exactly the same block plan, rather than maintaining a second
 * navigation sketch.
 */
public final class HarborLayout {
    public static final int MIN_X = -40, MAX_X = 40, MIN_Z = -30, MAX_Z = 30;
    public static final int BASE_Y = 62, FLOOR_Y = 63, FEET_Y = 64, TOP_Y = 80;
    private static final int WIDTH = MAX_X - MIN_X + 1;
    private static final int DEPTH = MAX_Z - MIN_Z + 1;
    private final Material[] blocks = new Material[WIDTH * DEPTH * (TOP_Y - BASE_Y + 1)];

    /** Only full cubes are used, so geometry tests agree with Minecraft collision. */
    public enum Material {
        AIR(false),
        FOUNDATION(true),
        ASPHALT(true),
        CONCRETE(true),
        BRICK(true),
        STEEL(true),
        BLUE(true),
        ORANGE(true),
        TEAL(true),
        RED(true),
        CRATE(true),
        TIMBER(true),
        YELLOW(true),
        WHITE(true),
        GLASS(true),
        LIGHT(true),
        WATER(false),
        BARRIER(true);

        private final boolean solid;

        Material(boolean solid) {
            this.solid = solid;
        }

        public boolean solid() {
            return solid;
        }
    }

    public record Point(int x, int z) {}

    public HarborLayout() {
        this(true);
    }

    /** Frozen generated v1 plan, used only for conservative per-block upgrades. */
    public static HarborLayout legacyV1() {
        return new HarborLayout(false);
    }

    private HarborLayout(boolean winding) {
        Arrays.fill(blocks, Material.AIR);
        ground();
        warehouse();
        containerYard();
        quay();
        compound(false);
        compound(true);
        if (winding) windingRoutes();
        boundaries();
    }

    public Material blockAt(int x, int y, int z) {
        if (x < MIN_X || x > MAX_X || z < MIN_Z || z > MAX_Z || y < BASE_Y || y > TOP_Y) {
            return Material.AIR;
        }
        return blocks[index(x, y, z)];
    }

    public boolean contains(double x, double y, double z) {
        return x >= MIN_X + 1 && x < MAX_X && z >= MIN_Z + 1 && z < 27 && y >= FEET_Y && y < TOP_Y;
    }

    public boolean walkable(int x, int z) {
        return contains(x + .5, FEET_Y, z + .5)
                && blockAt(x, FLOOR_Y, z).solid()
                && blockAt(x, FEET_Y, z) == Material.AIR
                && blockAt(x, FEET_Y + 1, z) == Material.AIR;
    }

    /** Immutable coordinates, returned in stable deployment order. East is team T. */
    public List<Point> spawns(boolean east) {
        List<Point> result = new ArrayList<>(16);
        for (int x : new int[] {-36, -34, -32, -30}) {
            for (int z : new int[] {-6, -2, 2, 6}) result.add(new Point(east ? -x : x, z));
        }
        return List.copyOf(result);
    }

    private void ground() {
        fill(MIN_X, BASE_Y, MIN_Z, MAX_X, BASE_Y, MAX_Z, Material.FOUNDATION);
        fill(MIN_X, FLOOR_Y, MIN_Z, MAX_X, FLOOR_Y, MAX_Z, Material.ASPHALT);
        // Pale cross streets make the three choices legible immediately after leaving spawn.
        fill(-24, FLOOR_Y, -28, -17, FLOOR_Y, 26, Material.CONCRETE);
        fill(17, FLOOR_Y, -28, 24, FLOOR_Y, 26, Material.CONCRETE);
        for (int x = -36; x <= 36; x += 6) {
            fill(x, FLOOR_Y, -26, x + 2, FLOOR_Y, -26, Material.YELLOW);
            fill(x, FLOOR_Y, 23, x + 2, FLOOR_Y, 23, Material.WHITE);
        }
        // Recessed lights cannot obstruct bot navigation.
        for (int x = -36; x <= 36; x += 8) {
            for (int z : new int[] {-27, -11, 11, 25}) put(x, FLOOR_Y, z, Material.LIGHT);
        }
    }

    private void warehouse() {
        fill(-14, FLOOR_Y, -9, 14, FLOOR_Y, 9, Material.CONCRETE);
        fill(-14, FEET_Y, -9, 14, 71, -9, Material.BRICK);
        fill(-14, FEET_Y, 9, 14, 71, 9, Material.BRICK);
        fill(-14, FEET_Y, -9, -14, 71, 9, Material.BRICK);
        fill(14, FEET_Y, -9, 14, 71, 9, Material.BRICK);
        // Seven-wide loading bays and seven-wide cross-route doors; no door AI required.
        fill(-14, FEET_Y, -3, -14, 69, 3, Material.AIR);
        fill(14, FEET_Y, -3, 14, 69, 3, Material.AIR);
        fill(-3, FEET_Y, -9, 3, 68, -9, Material.AIR);
        fill(-3, FEET_Y, 9, 3, 68, 9, Material.AIR);
        fill(-15, 72, -10, 15, 72, 10, Material.STEEL);
        // Glazed roof strips and brick pilasters give the hall an industrial silhouette.
        for (int x : new int[] {-10, -5, 5, 10}) {
            fill(x, 72, -7, x + 1, 72, 7, Material.GLASS);
            fill(x, 71, -6, x, 71, 6, Material.LIGHT);
        }
        for (int x : new int[] {-13, -7, 7, 13}) {
            fill(x, FEET_Y, -9, x, 71, -9, Material.STEEL);
            fill(x, FEET_Y, 9, x, 71, 9, Material.STEEL);
        }
        // Staggered work stacks: full-height cover, a broad central bot corridor.
        crate(-10, -6, 4, 3);
        crate(7, 4, 4, 3);
        crate(-2, -5, 3, 2);
        crate(0, 4, 3, 2);
        fill(-13, FLOOR_Y, -3, -13, FLOOR_Y, 3, Material.YELLOW);
        fill(13, FLOOR_Y, -3, 13, FLOOR_Y, 3, Material.YELLOW);
    }

    /**
     * Three broad doglegs rather than a maze. Alternating walls meet route edges so neither the
     * waterfront nor the north perimeter remains a full-width firing lane. All gaps are at least
     * three blocks wide; the central cross-route doors and both compound exits stay unchanged. Keep
     * the original builders above frozen: migration compares against that exact v1 plan.
     */
    private void windingRoutes() {
        // Yard: pass south of the first screen, then north of the second.
        fill(-10, FEET_Y, -29, -9, 68, -17, Material.TEAL);
        fill(9, FEET_Y, -21, 10, 68, -10, Material.RED);
        // Clear a broad turning aisle between the existing red container and the second screen.
        fill(4, FEET_Y, -19, 8, 68, -15, Material.AIR);

        // Warehouse loading bays are deliberately offset; interior partitions alternate openings.
        fill(-14, FEET_Y, -3, -14, 69, 3, Material.BRICK);
        fill(14, FEET_Y, -3, 14, 69, 3, Material.BRICK);
        fill(-14, FEET_Y, -6, -14, 69, -2, Material.AIR);
        fill(14, FEET_Y, 2, 14, 69, 6, Material.AIR);
        fill(-6, FEET_Y, -8, -6, 70, 2, Material.BRICK);
        fill(6, FEET_Y, -2, 6, 70, 8, Material.BRICK);
        // Move work stacks away from the inside of the new loading bays.
        fill(-10, FEET_Y, -6, -7, 66, -4, Material.AIR);
        fill(7, FEET_Y, 4, 10, 66, 6, Material.AIR);
        fill(-13, FLOOR_Y, -6, -13, FLOOR_Y, -2, Material.YELLOW);
        fill(13, FLOOR_Y, 2, 13, FLOOR_Y, 6, Material.YELLOW);

        // Quay: first turn waterside, then back inland. The seawall still seals off the water.
        fill(-8, FEET_Y, 10, -7, 68, 20, Material.STEEL);
        fill(7, FEET_Y, 16, 8, 68, 26, Material.STEEL);
    }

    private void containerYard() {
        container(-12, -23, Material.TEAL);
        container(4, -19, Material.RED);
        // Secondary boxes break long diagonal fire without closing the outer lane.
        crate(-23, -18, 3, 3);
        crate(21, -22, 3, 3);
        // A static gantry crane is only scenery: no entities, physics or moving parts.
        fill(-16, FEET_Y, -29, -15, 76, -28, Material.YELLOW);
        fill(15, FEET_Y, -29, 16, 76, -28, Material.YELLOW);
        fill(-16, 76, -29, 16, 77, -28, Material.YELLOW);
        fill(-2, 73, -28, -2, 75, -28, Material.STEEL);
        fill(-3, 73, -28, -1, 73, -28, Material.STEEL);
    }

    private void container(int x, int z, Material color) {
        fill(x, FEET_Y, z, x + 8, 68, z + 4, color);
        for (int rib = x; rib <= x + 8; rib += 2) {
            fill(rib, FEET_Y, z, rib, 68, z, Material.STEEL);
            fill(rib, FEET_Y, z + 4, rib, 68, z + 4, Material.STEEL);
        }
        fill(x, 68, z, x + 8, 68, z + 4, color);
        fill(x + 8, 65, z + 1, x + 8, 67, z + 3, Material.WHITE);
    }

    private void quay() {
        fill(-24, FLOOR_Y, 14, 24, FLOOR_Y, 26, Material.TIMBER);
        for (int x = -24; x <= 24; x += 6) fill(x, FLOOR_Y, 14, x, FLOOR_Y, 26, Material.STEEL);
        crate(-12, 16, 5, 3);
        crate(8, 17, 5, 3);
        crate(-2, 19, 4, 2);
        // Water is scenic, behind a continuous glazed seawall; never on a bot route.
        fill(-39, FLOOR_Y, 28, 39, FLOOR_Y, 29, Material.WATER);
        fill(-39, FEET_Y, 27, 39, 66, 27, Material.GLASS);
        fill(-39, 67, 27, 39, TOP_Y, 27, Material.BARRIER);
        for (int x = -36; x <= 36; x += 12) {
            fill(x, FEET_Y, 27, x, 68, 27, Material.STEEL);
            put(x, 69, 27, Material.LIGHT);
        }
    }

    private void compound(boolean east) {
        int min = east ? 25 : -39, max = east ? 39 : -25;
        Material color = east ? Material.ORANGE : Material.BLUE;
        fill(min, FLOOR_Y, -12, max, FLOOR_Y, 12, Material.CONCRETE);
        fill(min, FEET_Y, -12, max, 70, -12, color);
        fill(min, FEET_Y, 12, max, 70, 12, color);
        int front = east ? 25 : -25;
        fill(front, FEET_Y, -12, front, 70, 12, color);
        // Two separated exits, both outside the spawn rows and screened by blast walls.
        fill(front, FEET_Y, -10, front, 68, -8, Material.AIR);
        fill(front, FEET_Y, 8, front, 68, 10, Material.AIR);
        int screen = east ? 21 : -21;
        fill(screen, FEET_Y, -12, screen, 68, -7, Material.CONCRETE);
        fill(screen, FEET_Y, 7, screen, 68, 12, Material.CONCRETE);
        fill(min, 71, -12, max, 71, 12, Material.STEEL);
        for (int z : new int[] {-6, 0, 6}) fill(min + 2, 70, z, max - 2, 70, z, Material.LIGHT);
        for (Point spawn : spawns(east)) put(spawn.x(), FLOOR_Y, spawn.z(), color);
        // Distinct team-colored entry lintels, visible from every lane.
        fill(front, 69, -10, front, 70, -8, Material.WHITE);
        fill(front, 69, 8, front, 70, 10, Material.WHITE);
    }

    private void boundaries() {
        fill(MIN_X, FEET_Y, MIN_Z, MAX_X, 68, MIN_Z, Material.BRICK);
        fill(MIN_X, FEET_Y, MAX_Z, MAX_X, 66, MAX_Z, Material.STEEL);
        fill(MIN_X, FEET_Y, MIN_Z, MIN_X, 70, MAX_Z, Material.BRICK);
        fill(MAX_X, FEET_Y, MIN_Z, MAX_X, 70, MAX_Z, Material.BRICK);
        fill(MIN_X, 69, MIN_Z, MAX_X, TOP_Y, MIN_Z, Material.BARRIER);
        fill(MIN_X, 67, MAX_Z, MAX_X, TOP_Y, MAX_Z, Material.BARRIER);
        fill(MIN_X, 71, MIN_Z, MIN_X, TOP_Y, MAX_Z, Material.BARRIER);
        fill(MAX_X, 71, MIN_Z, MAX_X, TOP_Y, MAX_Z, Material.BARRIER);
        fill(MIN_X, TOP_Y, MIN_Z, MAX_X, TOP_Y, MAX_Z, Material.BARRIER);
    }

    private void crate(int x, int z, int width, int depth) {
        fill(x, FEET_Y, z, x + width - 1, 66, z + depth - 1, Material.CRATE);
        fill(x, 66, z, x + width - 1, 66, z, Material.TIMBER);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) put(x, y, z, material);
            }
        }
    }

    private void put(int x, int y, int z, Material material) {
        blocks[index(x, y, z)] = material;
    }

    private static int index(int x, int y, int z) {
        return ((y - BASE_Y) * DEPTH + z - MIN_Z) * WIDTH + x - MIN_X;
    }
}
