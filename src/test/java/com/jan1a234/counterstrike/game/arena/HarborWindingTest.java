package com.jan1a234.counterstrike.game.arena;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Quantitative gameplay geometry contract, runnable without Minecraft or JUnit. */
public final class HarborWindingTest {
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static void main(String[] args) {
        verify(new HarborLayout());
    }

    static void verify(HarborLayout map) {
        legacyPlanRemainsFrozen();
        check(HarborLayout.MAX_X - HarborLayout.MIN_X + 1 == 81, "81-block width");
        check(HarborLayout.MAX_Z - HarborLayout.MIN_Z + 1 == 61, "61-block depth");
        // Each route must work independently, without detouring through a different lane.
        route(map, "container yard", -26, -29, -10, 56, 84);
        route(map, "warehouse", 0, -8, 8, 48, 76);
        route(map, "quayside", 24, 10, 26, 54, 80);
        int clear = 0;
        int sampled = 0;
        for (int z = -28; z <= 25; z++) {
            if (!map.walkable(-18, z) || !map.walkable(18, z)) continue;
            sampled++;
            if (visible(map, -18, z, 18, z)) clear++;
        }
        check(clear <= 3, "at most 3 full-width sightlines, actual=" + clear + "/" + sampled);
        check(sampled >= 45, "LOS sample must cover all three lanes");
        // The existing central north/south doors remain short, usable crosslinks.
        check(distance(map, 0, -12, 0, 0, -14, 2) <= 18, "short north crosslink");
        check(distance(map, 0, 0, 0, 12, -2, 14) <= 18, "short south crosslink");
        for (boolean east : new boolean[] {false, true}) {
            int sign = east ? 1 : -1;
            for (int z : new int[] {-9, 9}) {
                for (int dz = -1; dz <= 1; dz++) {
                    check(map.walkable(sign * 25, z + dz), "three-wide spawn exit");
                    check(map.walkable(sign * 24, z + dz), "exit landing");
                }
                int length = distance(map, sign * 30, z, sign * 18, z, z - 5, z + 5);
                check(
                        length >= 14 && length <= 24,
                        "screened spawn exit remains usable: " + length);
            }
        }
        // Entire floor is one component (including all fixed spawns), with no trapped pockets.
        Map<HarborLayout.Point, Integer> connected = distances(map, -36, -6, -29, 26);
        int walkable = 0;
        for (int x = -39; x <= 39; x++) {
            for (int z = -29; z <= 26; z++) {
                if (map.walkable(x, z)) {
                    walkable++;
                    check(
                            connected.containsKey(new HarborLayout.Point(x, z)),
                            "trapped cell " + x + "," + z);
                }
            }
        }
        check(walkable >= 2500, "not a dense maze: usable cells=" + walkable);
        HarborLayoutTest.fixedSpawnsAreSafeAndSeparated(map);
        HarborLayoutTest.compoundsBlockSpawnToSpawnFire(map);
        HarborLayoutTest.perimeterContainsPlayers(map);
        System.out.println(
                "HarborWindingTest PASS: connected="
                        + walkable
                        + ", clear LOS="
                        + clear
                        + "/"
                        + sampled);
    }

    private static void route(
            HarborLayout map, String name, int z, int minZ, int maxZ, int min, int max) {
        int length = distance(map, -18, z, 18, z, minZ, maxZ);
        System.out.println(
                name
                        + " shortest route="
                        + length
                        + " (straight=36, required="
                        + min
                        + ".."
                        + max
                        + ")");
        check(length >= min && length <= max, name + " needs a navigable dogleg, length=" + length);
        check(!visible(map, -18, z, 18, z), name + " must interrupt end-to-end fire");
        int wide =
                distances(map, -18, z, minZ, maxZ, true)
                        .getOrDefault(new HarborLayout.Point(18, z), Integer.MAX_VALUE);
        check(wide <= 100, name + " needs an uninterrupted 3x3-clear bot route, length=" + wide);
        System.out.println(name + " three-wide route=" + wide);
    }

    private static int distance(
            HarborLayout map, int x, int z, int tx, int tz, int minZ, int maxZ) {
        return distances(map, x, z, minZ, maxZ)
                .getOrDefault(new HarborLayout.Point(tx, tz), Integer.MAX_VALUE);
    }

    private static Map<HarborLayout.Point, Integer> distances(
            HarborLayout map, int x, int z, int minZ, int maxZ) {
        return distances(map, x, z, minZ, maxZ, false);
    }

    private static Map<HarborLayout.Point, Integer> distances(
            HarborLayout map, int x, int z, int minZ, int maxZ, boolean wide) {
        Map<HarborLayout.Point, Integer> seen = new HashMap<>();
        ArrayDeque<HarborLayout.Point> queue = new ArrayDeque<>();
        HarborLayout.Point start = new HarborLayout.Point(x, z);
        if (!map.walkable(x, z)) return seen;
        seen.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            HarborLayout.Point p = queue.remove();
            for (int[] step : STEPS) {
                HarborLayout.Point next = new HarborLayout.Point(p.x() + step[0], p.z() + step[1]);
                if (next.z() < minZ
                        || next.z() > maxZ
                        || !map.walkable(next.x(), next.z())
                        || seen.containsKey(next)) continue;
                if (wide && !clearance(map, next.x(), next.z())) continue;
                seen.put(next, seen.get(p) + 1);
                queue.add(next);
            }
        }
        return seen;
    }

    private static boolean clearance(HarborLayout map, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!map.walkable(x + dx, z + dz)) return false;
            }
        }
        return true;
    }

    private static boolean visible(HarborLayout map, int x, int z, int tx, int tz) {
        for (int i = 0; i <= 720; i++) {
            double t = i / 720.0;
            int px = (int) Math.floor(x + .5 + (tx - x) * t);
            int pz = (int) Math.floor(z + .5 + (tz - z) * t);
            if (map.blockAt(px, HarborLayout.FEET_Y + 1, pz).solid()) return false;
        }
        return true;
    }

    /** Digest measured from unmodified main 30427c9, ordered x/z/y and material ordinal. */
    private static void legacyPlanRemainsFrozen() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            HarborLayout legacy = HarborLayout.legacyV1();
            for (int x = -40; x <= 40; x++) {
                for (int z = -30; z <= 30; z++) {
                    for (int y = 62; y <= 80; y++) {
                        digest.update((byte) legacy.blockAt(x, y, z).ordinal());
                    }
                }
            }
            check(
                    java.util.HexFormat.of()
                            .formatHex(digest.digest())
                            .equals(
                                    "8dcf8aadaba3d23f8a325e66d2c7d7de3b94cf6e4474bde98e6ac4ac8bf0acee"),
                    "migration must compare against the exact released v1 geometry");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
