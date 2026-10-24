package com.jan1a234.counterstrike.weapon;

/** Pure ballistic calculations, independent of Minecraft bootstrap. */
final class CombatMath {
    private CombatMath() {}

    static boolean isMoving(double velocitySquared, double observedSquared) {
        // Server players report client-controlled movement separately from velocity.
        return Math.max(velocitySquared, observedSquared) > 0.0025;
    }

    static float spread(
            float base,
            boolean sniper,
            boolean aiming,
            boolean moving,
            boolean grounded,
            float bloom) {
        float spread = sniper && aiming ? 0.05f : aiming ? base * 0.5f : base;
        if (moving) spread += sniper ? 2.5f : 1.4f;
        if (!grounded) spread += sniper ? 6f : 3f;
        return spread + Math.max(0, bloom);
    }

    static float recoverBloom(float bloom, long elapsedTicks) {
        return Math.max(0, bloom - Math.max(0, elapsedTicks) * 0.12f);
    }

    static float damageMultiplier(double distance, double range, float minimum) {
        double start = range * 0.2;
        double progress = Math.max(0, Math.min(1, (distance - start) / Math.max(1, range - start)));
        return (float) (1 - progress * (1 - minimum));
    }

    static int reloadTake(int ammo, int reserve, int magazine) {
        return Math.max(0, Math.min(magazine - Math.max(0, ammo), reserve));
    }
}
