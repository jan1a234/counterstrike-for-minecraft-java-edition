package com.jan1a234.counterstrike.game.arena;

import org.junit.jupiter.api.Test;

/** Runs the Minecraft-free geometry contract under the project's normal Gradle test task. */
class HarborLayoutJUnitTest {
    private final HarborLayout map = new HarborLayout();

    @Test
    void fixedSpawnsAreSafeAndSeparated() {
        HarborLayoutTest.fixedSpawnsAreSafeAndSeparated(map);
    }

    @Test
    void routesConnectAllSpawnsAndLanes() {
        HarborLayoutTest.routesConnectAllSpawnsAndLanes(map);
    }

    @Test
    void compoundsBlockSpawnToSpawnFire() {
        HarborLayoutTest.compoundsBlockSpawnToSpawnFire(map);
    }

    @Test
    void perimeterContainsPlayers() {
        HarborLayoutTest.perimeterContainsPlayers(map);
    }

    @Test
    void authoredGeometryIsDeterministic() {
        HarborLayoutTest.authoredGeometryIsDeterministic(map);
    }
}
