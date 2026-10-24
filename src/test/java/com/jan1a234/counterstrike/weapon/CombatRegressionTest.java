package com.jan1a234.counterstrike.weapon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CombatRegressionTest {
    @Test
    void clientControlledMovementStillPenalizesAccuracy() {
        assertTrue(CombatMath.isMoving(0, 0.04));
        assertTrue(CombatMath.isMoving(0.04, 0));
        assertFalse(CombatMath.isMoving(0, 0));
    }

    @Test
    void crouchingAimsInsteadOfReloadingALoadedWeapon() {
        assertFalse(CombatRules.reloadInsteadOfFire(true, false, 12));
        assertFalse(CombatRules.reloadInsteadOfFire(true, true, 4));
        assertTrue(CombatRules.reloadInsteadOfFire(false, false, 0));
    }

    @Test
    void switchedReloadingOrStoppedBurstsMustCancel() {
        assertFalse(CombatRules.canContinue(true, true, true, false, false));
        assertFalse(CombatRules.canContinue(false, true, true, true, false));
        assertFalse(CombatRules.canContinue(true, false, true, true, false));
        assertFalse(CombatRules.canContinue(true, true, true, true, true));
        assertTrue(CombatRules.canContinue(true, true, true, true, false));
    }

    @Test
    void friendlyBodiesBlockRaysWithoutReceivingDamage() {
        assertTrue(CombatRules.blocksRay(true, true, false, false, true));
        assertFalse(CombatRules.canDamage(true, "CT", "CT", false));
        assertFalse(CombatRules.blocksRay(true, true, true, false, false));
    }

    @Test
    void deathCallbackCanCancelAnotherShootersPendingBurst() {
        ShotQueue<String> shots = new ShotQueue<>();
        shots.add("killer");
        shots.add("victim");
        shots.add("other");
        List<String> fired = new ArrayList<>();
        assertDoesNotThrow(
                () ->
                        shots.tick(
                                s -> true,
                                s -> {
                                    fired.add(s);
                                    if (s.equals("killer")) shots.removeIf("victim"::equals);
                                }));
        assertEquals(List.of("killer", "other"), fired);
    }

    @Test
    void stoppingRoundInsideCallbackCancelsRemainingShots() {
        ShotQueue<Integer> shots = new ShotQueue<>();
        shots.add(1);
        shots.add(2);
        shots.add(3);
        List<Integer> fired = new ArrayList<>();
        assertDoesNotThrow(
                () ->
                        shots.tick(
                                s -> true,
                                s -> {
                                    fired.add(s);
                                    shots.clear();
                                }));
        assertEquals(List.of(1), fired);
    }

    @Test
    void callbackAddedShotsWaitUntilNextTick() {
        ShotQueue<Integer> shots = new ShotQueue<>();
        shots.add(1);
        assertDoesNotThrow(() -> shots.tick(s -> true, s -> shots.add(2)));
        assertEquals(1, shots.size());
    }

    @Test
    void futureShotsRemainQueued() {
        ShotQueue<Integer> shots = new ShotQueue<>();
        shots.add(10);
        shots.tick(s -> s <= 5, s -> fail("too early"));
        assertEquals(1, shots.size());
    }

    @Test
    void nonparticipantsCannotReceiveOrDealDamage() {
        assertFalse(CombatRules.canDamage(true, "CT", null, false));
        assertFalse(CombatRules.canDamage(true, null, "T", false));
    }

    @Test
    void stoppedMatchAndSpawnProtectionRejectDamage() {
        assertFalse(CombatRules.canDamage(false, "CT", "T", false));
        assertFalse(CombatRules.canDamage(true, "CT", "T", true));
    }

    @Test
    void teammatesRejectDamageAndOpponentsAcceptIt() {
        assertFalse(CombatRules.canDamage(true, "CT", "CT", false));
        assertTrue(CombatRules.canDamage(true, "CT", "T", false));
    }

    @Test
    void movingScopedSniperIsNotLaserAccurate() {
        assertTrue(CombatMath.spread(6, true, true, true, true, 0) >= 2);
        assertEquals(0.05f, CombatMath.spread(6, true, true, false, true, 0), 0.001f);
        assertTrue(CombatMath.spread(6, true, false, false, true, 0) >= 6);
    }

    @Test
    void jumpingHasAnAbsoluteAccuracyPenalty() {
        assertTrue(CombatMath.spread(6, true, true, false, false, 0) >= 5);
    }

    @Test
    void sustainedFireBloomsAndRestRecovers() {
        assertTrue(CombatMath.spread(1.6f, false, false, false, true, 3) > 4);
        assertEquals(0, CombatMath.recoverBloom(3, 40), 0.001f);
        assertEquals(3, CombatMath.recoverBloom(3, 0), 0.001f);
    }

    @Test
    void distanceFalloffIsContinuousAndBounded() {
        assertEquals(1, CombatMath.damageMultiplier(0, 90, 0.6f), 0.001f);
        assertEquals(0.6f, CombatMath.damageMultiplier(90, 90, 0.6f), 0.001f);
        float previous = 1;
        for (int d = 0; d < 200; d++) {
            float value = CombatMath.damageMultiplier(d, 90, 0.6f);
            assertTrue(value <= previous + 0.0001f && value >= 0.6f);
            previous = value;
        }
    }

    @Test
    void reloadConservesRoundsAndNeverTransfersNegativeAmmo() {
        assertEquals(7, CombatMath.reloadTake(23, 90, 30));
        assertEquals(3, CombatMath.reloadTake(0, 3, 30));
        assertEquals(0, CombatMath.reloadTake(40, 90, 30));
        assertEquals(0, CombatMath.reloadTake(0, -2, 30));
    }
}
