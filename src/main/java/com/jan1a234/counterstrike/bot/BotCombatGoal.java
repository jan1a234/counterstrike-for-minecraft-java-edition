package com.jan1a234.counterstrike.bot;

import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.weapon.WeaponHandler;
import com.jan1a234.counterstrike.weapon.WeaponType;
import com.jan1a234.counterstrike.weapon.Weapons;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Random;

/**
 * Die gesamte Bot-KI: Gegner suchen, hinlaufen, zielen, schiessen und nachladen. Ohne Sichtkontakt
 * laeuft der Bot zum naechsten Gegner, damit es immer zu Gefechten kommt.
 */
public class BotCombatGoal extends Goal {
    private static final double VIEW_RANGE = 64;
    private static final Random RANDOM = new Random();

    private final CsBot bot;
    private LivingEntity target;
    private int retargetCooldown;
    private int repathCooldown;
    private final BotReaction reaction = new BotReaction();
    private int strafeTicks;
    private float strafeDirection = 1f;

    public BotCombatGoal(CsBot bot) {
        this.bot = bot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Match match = Match.get();
        return match != null && match.isRunning() && bot.slot() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        target = null;
        reaction.reset();
        bot.getNavigation().stop();
        bot.setShiftKeyDown(false);
    }

    @Override
    public void tick() {
        Match match = Match.get();
        CsTeam team = bot.slot().team;

        if (--retargetCooldown <= 0 || target == null || !target.isAlive()) {
            retargetCooldown = 10;
            LivingEntity visible = match.nearestEnemy(bot, team, VIEW_RANGE, true);
            target = visible;
        }

        ItemStack weapon = bot.getMainHandItem();
        WeaponType type = Weapons.typeOf(weapon);

        if (target == null) {
            reaction.reset();
            bot.setShiftKeyDown(false);
            hunt(match, team);
            return;
        }

        double distance = bot.distanceTo(target);
        double preferred = type == WeaponType.SNIPER ? 30 : type == WeaponType.SHOTGUN ? 7 : 14;
        if (distance > preferred) {
            if (--repathCooldown <= 0) {
                repathCooldown = 10;
                bot.getNavigation().moveTo(target, 1.0);
            }
        } else {
            bot.getNavigation().stop();
            if (--strafeTicks <= 0) {
                strafeTicks = 20 + RANDOM.nextInt(30);
                strafeDirection = RANDOM.nextBoolean() ? 1f : -1f;
            }
            if (type != WeaponType.SNIPER) {
                bot.getMoveControl()
                        .strafe(distance < preferred * 0.5 ? -0.5f : 0f, 0.5f * strafeDirection);
            }
        }

        aimAt(target);
        if (!reaction.ready(
                        target,
                        bot.hasLineOfSight(target),
                        match.config().botDifficulty().reactionTicks)
                || type == null) {
            return;
        }
        boolean aiming = type.needsAiming();
        bot.setShiftKeyDown(aiming);
        if (Weapons.reserve(weapon) < type.magazine) {
            Weapons.setAmmo(weapon, Weapons.ammo(weapon), type.reserve);
        }
        if (bot.hasLineOfSight(target)) {
            WeaponHandler.tryFire(bot, weapon, type);
        }
    }

    private void hunt(Match match, CsTeam team) {
        if (--repathCooldown > 0) {
            return;
        }
        repathCooldown = 40;
        LivingEntity anyEnemy = match.nearestEnemy(bot, team, 512, false);
        if (anyEnemy != null) {
            bot.getNavigation().moveTo(anyEnemy, 1.0);
            return;
        }
        Vec3 spawn = match.randomSpawn(team.opponent());
        if (spawn != null) {
            bot.getNavigation().moveTo(spawn.x, spawn.y, spawn.z, 1.0);
        }
    }

    private void aimAt(LivingEntity target) {
        float error = Match.get().config().botDifficulty().aimErrorDegrees;
        double dx = target.getX() - bot.getX();
        double dy = target.getEyeY() - 0.25 - bot.getEyeY();
        double dz = target.getZ() - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw =
                (float) Math.toDegrees(Math.atan2(dz, dx))
                        - 90f
                        + (float) RANDOM.nextGaussian() * error;
        float pitch =
                (float) -Math.toDegrees(Math.atan2(dy, horizontal))
                        + (float) RANDOM.nextGaussian() * error * 0.6f;
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setXRot(pitch);
        bot.getLookControl().setLookAt(target, 60f, 60f);
    }

    static ServerLevel level(CsBot bot) {
        return (ServerLevel) bot.level();
    }
}
