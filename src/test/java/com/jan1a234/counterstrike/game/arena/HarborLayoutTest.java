package com.jan1a234.counterstrike.game.arena;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Dependency-free geometry regression suite; runnable with java -ea ...HarborLayoutTest. */
public final class HarborLayoutTest {
    private static int assertions;

    public static void main(String[] args) {
        HarborLayout map = new HarborLayout();
        fixedSpawnsAreSafeAndSeparated(map);
        routesConnectAllSpawnsAndLanes(map);
        compoundsBlockSpawnToSpawnFire(map);
        perimeterContainsPlayers(map);
        authoredGeometryIsDeterministic(map);
        System.out.println("HarborLayoutTest: " + assertions + " assertions passed");
    }

    static void fixedSpawnsAreSafeAndSeparated(HarborLayout map) {
        check(map.spawns(false).size() == 16, "16 fixed CT spawns");
        check(map.spawns(true).size() == 16, "16 fixed T spawns");
        Set<HarborLayout.Point> unique = new HashSet<>();
        for (boolean east : new boolean[] {false, true}) {
            for (HarborLayout.Point p : map.spawns(east)) {
                check(unique.add(p), "spawn cells never overlap");
                check(map.walkable(p.x(), p.z()), "spawn has solid floor and headroom: " + p);
                check(east ? p.x() >= 29 : p.x() <= -29, "spawn stays in its compound");
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        check(map.walkable(p.x() + dx, p.z() + dz), "spawn has clearance: " + p);
                    }
                }
            }
        }
        check(map.spawns(false).get(0).equals(new HarborLayout.Point(-36, -6)), "stable CT anchor");
        check(map.spawns(true).get(0).equals(new HarborLayout.Point(36, -6)), "stable T anchor");
    }

    static void routesConnectAllSpawnsAndLanes(HarborLayout map) {
        Set<HarborLayout.Point> seen = flood(map, map.spawns(false).get(0));
        for (boolean east : new boolean[] {false, true}) {
            for (HarborLayout.Point p : map.spawns(east))
                check(seen.contains(p), "spawn connected: " + p);
        }
        for (int z : new int[] {-26, 0, 24}) {
            for (int x : new int[] {-18, 0, 18}) {
                check(
                        seen.contains(new HarborLayout.Point(x, z)),
                        "three lanes connected at " + x + "," + z);
            }
        }
        for (int x = HarborLayout.MIN_X + 1; x < HarborLayout.MAX_X; x++) {
            for (int z = HarborLayout.MIN_Z + 1; z < HarborLayout.MAX_Z; z++) {
                if (map.walkable(x, z))
                    check(
                            seen.contains(new HarborLayout.Point(x, z)),
                            "no trapped walkable pocket");
            }
        }
        check(seen.size() > 2500, "substantial playable space");
    }

    private static Set<HarborLayout.Point> flood(HarborLayout map, HarborLayout.Point start) {
        Set<HarborLayout.Point> seen = new HashSet<>();
        ArrayDeque<HarborLayout.Point> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            HarborLayout.Point p = queue.remove();
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                HarborLayout.Point q = new HarborLayout.Point(p.x() + d[0], p.z() + d[1]);
                if (map.walkable(q.x(), q.z()) && seen.add(q)) queue.add(q);
            }
        }
        return seen;
    }

    static void compoundsBlockSpawnToSpawnFire(HarborLayout map) {
        for (HarborLayout.Point west : map.spawns(false)) {
            for (HarborLayout.Point east : map.spawns(true)) {
                boolean blocked = false;
                for (int i = 0; i <= 400; i++) {
                    double t = i / 400.0;
                    int x = (int) Math.floor(west.x() + .5 + (east.x() - west.x()) * t);
                    int z = (int) Math.floor(west.z() + .5 + (east.z() - west.z()) * t);
                    if (map.blockAt(x, HarborLayout.FEET_Y + 1, z).solid()) blocked = true;
                }
                check(blocked, "no direct spawn-to-spawn sightline");
            }
        }
    }

    static void perimeterContainsPlayers(HarborLayout map) {
        for (int y = HarborLayout.FEET_Y; y <= HarborLayout.TOP_Y; y++) {
            for (int x = HarborLayout.MIN_X; x <= HarborLayout.MAX_X; x++) {
                check(map.blockAt(x, y, HarborLayout.MIN_Z).solid(), "north wall");
                check(map.blockAt(x, y, HarborLayout.MAX_Z).solid(), "south wall");
            }
            for (int z = HarborLayout.MIN_Z; z <= HarborLayout.MAX_Z; z++) {
                check(map.blockAt(HarborLayout.MIN_X, y, z).solid(), "west wall");
                check(map.blockAt(HarborLayout.MAX_X, y, z).solid(), "east wall");
            }
        }
        check(!map.walkable(100, 100), "outside is not walkable");
        check(!map.contains(Double.NaN, 64, 0), "NaN rejected");
        check(!map.contains(0, 64, Double.POSITIVE_INFINITY), "infinity rejected");
        check(!map.contains(40, 64, 0), "wall excluded");
        check(map.contains(0.5, 64, 0.5), "center included");
    }

    static void authoredGeometryIsDeterministic(HarborLayout map) {
        HarborLayout second = new HarborLayout();
        Set<HarborLayout.Material> palette = new HashSet<>();
        int lightCount = 0;
        for (int x = HarborLayout.MIN_X; x <= HarborLayout.MAX_X; x++) {
            for (int z = HarborLayout.MIN_Z; z <= HarborLayout.MAX_Z; z++) {
                for (int y = HarborLayout.BASE_Y; y <= HarborLayout.TOP_Y; y++) {
                    HarborLayout.Material material = map.blockAt(x, y, z);
                    check(material == second.blockAt(x, y, z), "deterministic block plan");
                    palette.add(material);
                    if (material == HarborLayout.Material.LIGHT) lightCount++;
                }
            }
        }
        check(palette.size() >= 12, "authored material palette");
        check(lightCount >= 40, "working lighting throughout arena");
        check(map.blockAt(0, 72, 0).solid(), "warehouse roof");
    }

    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
