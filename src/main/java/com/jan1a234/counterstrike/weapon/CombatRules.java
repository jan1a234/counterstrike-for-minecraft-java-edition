package com.jan1a234.counterstrike.weapon;

/** Team boundary shared by hitscan and radial damage. */
final class CombatRules {
    private CombatRules() {}

    static boolean reloadInsteadOfFire(boolean crouching, boolean sniper, int ammo) {
        return ammo <= 0;
    }

    static boolean canContinue(
            boolean running,
            boolean participant,
            boolean alive,
            boolean sameWeapon,
            boolean reloading) {
        return running && participant && alive && sameWeapon && !reloading;
    }

    static boolean blocksRay(
            boolean living, boolean alive, boolean spectator, boolean shooter, boolean sameTeam) {
        return living && alive && !spectator && !shooter;
    }

    static boolean canDamage(
            boolean running, Object attackerTeam, Object victimTeam, boolean protectedSpawn) {
        return running
                && attackerTeam != null
                && victimTeam != null
                && !attackerTeam.equals(victimTeam)
                && !protectedSpawn;
    }
}
