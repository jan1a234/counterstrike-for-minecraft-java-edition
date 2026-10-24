package com.jan1a234.counterstrike.weapon;

import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Schiesslogik: Hitscan-Schuesse mit Streuung, Kopftreffern, Magazinen, Nachladen und Granaten.
 * Funktioniert fuer Spieler und spaeter auch fuer Bots (jede LivingEntity kann schiessen).
 */
public final class WeaponHandler {
    private static final int GRENADE_FUSE_TICKS = 50;
    private static final float GRENADE_RADIUS = 3.5f;

    private static final Random RANDOM = new Random();
    private static final Map<UUID, Long> nextShotTick = new HashMap<>();
    private static final Map<UUID, Reload> reloads = new HashMap<>();
    private static final ShotQueue<PendingShot> pendingShots = new ShotQueue<>();
    private static final List<Grenade> grenades = new ArrayList<>();
    private static final Map<UUID, LastHit> lastHits = new HashMap<>();
    private static final Map<UUID, Recoil> recoil = new HashMap<>();

    private static MinecraftServer server;

    private record Reload(
            LivingEntity shooter, ItemStack stack, WeaponType type, int slot, long finishTick) {}

    private record PendingShot(
            LivingEntity shooter, ItemStack stack, WeaponType type, int slot, long fireTick) {}

    private record Grenade(ItemEntity entity, UUID thrower, CsTeam team, long explodeTick) {}

    private record LastHit(String weapon, long tick) {}

    private record Recoil(float bloom, long tick) {}

    private WeaponHandler() {}

    public static void init(MinecraftServer minecraftServer) {
        resetRound();
        server = minecraftServer;
    }

    /** Cancel all transient combat work, including physical grenade items. */
    public static void resetRound() {
        nextShotTick.clear();
        reloads.clear();
        pendingShots.clear();
        for (Grenade grenade : grenades) grenade.entity().discard();
        grenades.clear();
        lastHits.clear();
        recoil.clear();
    }

    private static long now() {
        return server == null ? 0 : server.getTickCount();
    }

    // ---------------------------------------------------------------- Eingabe

    /** Rechtsklick mit einem Item (in die Luft oder auf einen Block). */
    public static InteractionResult onUse(Player player, Level level, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String id = Weapons.weaponId(stack);
        if (id == null || hand != InteractionHand.MAIN_HAND) {
            return id == null ? InteractionResult.PASS : InteractionResult.FAIL;
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            // Fabric UseItemCallback sends its C2S packet only for SUCCESS, not CONSUME.
            // CONSUME looked handled locally but silently dropped every shot into open air.
            return InteractionResult.SUCCESS;
        }
        if (Match.get() == null
                || !Match.get().isRunning()
                || Match.get().teamOf(serverPlayer) == null) {
            serverPlayer.sendSystemMessage(
                    Component.literal("Waffen funktionieren nur waehrend eines Spiels.")
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return InteractionResult.CONSUME;
        }
        if (Weapons.GRENADE.equals(id)) {
            throwGrenade(serverPlayer, stack);
            return InteractionResult.CONSUME;
        }
        WeaponType type = WeaponType.byId(id);
        if (type == null) {
            return InteractionResult.CONSUME;
        }
        if (CombatRules.reloadInsteadOfFire(
                serverPlayer.isShiftKeyDown(), type.needsAiming(), Weapons.ammo(stack))) {
            startReload(serverPlayer, stack, type);
        } else {
            tryFire(serverPlayer, stack, type);
        }
        return InteractionResult.CONSUME;
    }

    // ---------------------------------------------------------------- Schiessen

    /** Versucht einen Abzug. Gibt true zurueck, wenn geschossen wurde. */
    public static boolean tryFire(LivingEntity shooter, ItemStack stack, WeaponType type) {
        long now = now();
        if (!canFire(shooter, stack, type)) {
            return false;
        }
        if (nextShotTick.getOrDefault(shooter.getUUID(), 0L) > now) {
            return false;
        }
        int ammo = Weapons.ammo(stack);
        if (ammo <= 0) {
            ServerLevel level = (ServerLevel) shooter.level();
            level.playSound(
                    null,
                    shooter.getX(),
                    shooter.getY(),
                    shooter.getZ(),
                    SoundEvents.DISPENSER_FAIL,
                    SoundSource.PLAYERS,
                    0.6f,
                    1.6f);
            startReload(shooter, stack, type);
            nextShotTick.put(shooter.getUUID(), now + 10);
            return false;
        }
        int shots = Math.min(type.burst, ammo);
        nextShotTick.put(shooter.getUUID(), now + type.cooldownTicks);
        if (shooter instanceof ServerPlayer player && type.cooldownTicks > 8) {
            player.getCooldowns().addCooldown(stack, type.cooldownTicks);
        }
        int slot = selectedSlot(shooter);
        // Queue first: synchronous death/round-end callbacks may cancel these.
        for (int i = 1; i < shots; i++) {
            pendingShots.add(new PendingShot(shooter, stack, type, slot, now + i * 2L));
        }
        fireRound(shooter, stack, type);
        return true;
    }

    private static int selectedSlot(LivingEntity shooter) {
        return shooter instanceof ServerPlayer player
                ? player.getInventory().getSelectedSlot()
                : -1;
    }

    private static boolean canFire(LivingEntity shooter, ItemStack stack, WeaponType type) {
        Match match = Match.get();
        return shooter.level() instanceof ServerLevel
                && CombatRules.canContinue(
                        match != null && match.isRunning(),
                        match != null && match.teamOf(shooter) != null,
                        shooter.isAlive() && !shooter.isRemoved() && !shooter.isSpectator(),
                        shooter.getMainHandItem() == stack
                                && type != null
                                && Weapons.typeOf(stack) == type,
                        reloads.containsKey(shooter.getUUID()));
    }

    private static void fireRound(LivingEntity shooter, ItemStack stack, WeaponType type) {
        if (!canFire(shooter, stack, type) || Weapons.ammo(stack) <= 0) return;
        // Cancelled burst rounds cost no ammo; every actual round costs exactly one.
        Weapons.setAmmo(stack, Weapons.ammo(stack) - 1, Weapons.reserve(stack));
        Match.get().breakSpawnProtection(shooter);
        shoot(shooter, type);
    }

    private static void shoot(LivingEntity shooter, WeaponType type) {
        if (!shooter.isAlive()) {
            return;
        }
        ServerLevel level = (ServerLevel) shooter.level();
        level.playSound(
                null,
                shooter.getX(),
                shooter.getEyeY(),
                shooter.getZ(),
                type.sound,
                SoundSource.PLAYERS,
                1.2f,
                type.pitch + RANDOM.nextFloat() * 0.1f);

        float spread = currentSpread(shooter, type);
        // Update before damage callbacks so round cleanup cannot be undone below.
        recoil.put(
                shooter.getUUID(),
                new Recoil(Math.min(6f, recoveredBloom(shooter) + type.bloomPerShot()), now()));
        Vec3 eye = shooter.getEyePosition();
        boolean anyHit = false;
        for (int i = 0; i < type.pellets; i++) {
            Vec3 dir = applySpread(shooter.getLookAngle(), spread);
            Vec3 end = eye.add(dir.scale(type.range));
            BlockHitResult blockHit =
                    level.clip(
                            new ClipContext(
                                    eye,
                                    end,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    shooter));
            if (blockHit.getType() != HitResult.Type.MISS) {
                end = blockHit.getLocation();
            }
            AABB searchBox = shooter.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
            EntityHitResult entityHit =
                    ProjectileUtil.getEntityHitResult(
                            shooter,
                            eye,
                            end,
                            searchBox,
                            target -> blocksRay(shooter, target),
                            eye.distanceToSqr(end));
            if (entityHit != null && entityHit.getEntity() instanceof LivingEntity target) {
                Vec3 hitPos = entityHit.getLocation();
                end = hitPos;
                boolean headshot = (hitPos.y - target.getY()) / target.getBbHeight() > 0.78;
                float amount =
                        type.damage
                                * (headshot ? type.headshotMultiplier : 1f)
                                * CombatMath.damageMultiplier(
                                        eye.distanceTo(hitPos),
                                        type.range,
                                        type.minimumDamageMultiplier());
                boolean accepted = damage(level, shooter, target, amount, type.displayName);
                anyHit |= accepted;
                if (accepted)
                    level.sendParticles(
                            ParticleTypes.DAMAGE_INDICATOR,
                            hitPos.x,
                            hitPos.y,
                            hitPos.z,
                            2,
                            0.1,
                            0.1,
                            0.1,
                            0.0);
                if (accepted && headshot && shooter instanceof ServerPlayer player) {
                    player.sendSystemMessage(
                            Component.literal("Kopftreffer!").withStyle(ChatFormatting.RED), true);
                }
            } else if (blockHit.getType() != HitResult.Type.MISS) {
                level.sendParticles(
                        ParticleTypes.SMOKE, end.x, end.y, end.z, 3, 0.05, 0.05, 0.05, 0.01);
            }
            tracer(level, eye.add(dir.scale(1.2)), end);
        }
        if (anyHit) {
            level.playSound(
                    null,
                    shooter.getX(),
                    shooter.getY(),
                    shooter.getZ(),
                    SoundEvents.ARROW_HIT_PLAYER,
                    SoundSource.PLAYERS,
                    0.4f,
                    1.4f);
        }
    }

    private static boolean damage(
            ServerLevel level,
            LivingEntity shooter,
            LivingEntity target,
            float amount,
            String weapon) {
        if (!canDamage(shooter, target)) return false;
        // A grenade thrown before death must not deal damage from a protected respawn.
        Match.get().breakSpawnProtection(shooter);
        DamageSource source =
                shooter instanceof Player player
                        ? level.damageSources().playerAttack(player)
                        : level.damageSources().mobAttack(shooter);
        // Death callbacks run inside hurtServer. Expose attribution only during
        // this call; a later fall/melee kill must never inherit an old gun label.
        LastHit hit = new LastHit(weapon, now());
        lastHits.put(target.getUUID(), hit);
        int immunity = target.invulnerableTime;
        try {
            target.invulnerableTime = 0;
            return target.hurtServer(level, source, amount);
        } finally {
            lastHits.remove(target.getUUID(), hit);
            // Do not erase protection installed by a synchronous respawn callback.
            Match match = Match.get();
            if (match == null || !match.isSpawnProtected(target))
                target.invulnerableTime = immunity;
        }
    }

    private static boolean blocksRay(LivingEntity shooter, Entity target) {
        return CombatRules.blocksRay(
                target instanceof LivingEntity,
                target.isAlive(),
                target.isSpectator(),
                target == shooter,
                false);
    }

    private static boolean canDamage(LivingEntity shooter, LivingEntity target) {
        Match match = Match.get();
        return match != null
                && target.isAlive()
                && !target.isRemoved()
                && !target.isSpectator()
                && shooter.level() == target.level()
                && CombatRules.canDamage(
                        match.isRunning(),
                        match.teamOf(shooter),
                        match.teamOf(target),
                        match.isSpawnProtected(target));
    }

    private static float recoveredBloom(LivingEntity shooter) {
        Recoil previous = recoil.get(shooter.getUUID());
        return previous == null
                ? 0
                : CombatMath.recoverBloom(previous.bloom(), now() - previous.tick());
    }

    private static float currentSpread(LivingEntity shooter, WeaponType type) {
        Vec3 motion = shooter.getDeltaMovement();
        Vec3 observed = shooter.getKnownMovement();
        return CombatMath.spread(
                type.spreadDegrees,
                type.needsAiming(),
                shooter.isShiftKeyDown(),
                CombatMath.isMoving(
                        motion.x * motion.x + motion.z * motion.z,
                        observed.x * observed.x + observed.z * observed.z),
                shooter.onGround(),
                recoveredBloom(shooter));
    }

    private static Vec3 applySpread(Vec3 dir, float degrees) {
        if (degrees <= 0) {
            return dir;
        }
        double s = Math.tan(Math.toRadians(degrees)) * 0.5;
        return dir.add(
                        RANDOM.nextGaussian() * s,
                        RANDOM.nextGaussian() * s,
                        RANDOM.nextGaussian() * s)
                .normalize();
    }

    private static void tracer(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = Math.min(delta.length(), 48);
        if (length < 0.5) {
            return;
        }
        Vec3 step = delta.normalize().scale(1.5);
        Vec3 pos = from;
        for (double d = 0; d < length; d += 1.5) {
            level.sendParticles(ParticleTypes.CRIT, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            pos = pos.add(step);
        }
    }

    // ---------------------------------------------------------------- Nachladen

    public static boolean isReloading(LivingEntity entity) {
        return reloads.containsKey(entity.getUUID());
    }

    /** Command/key-binding entry point; also permits topping up a sniper magazine. */
    public static boolean requestReload(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        WeaponType type = Weapons.typeOf(stack);
        if (type == null || !canFire(player, stack, type)) return false;
        startReload(player, stack, type);
        return isReloading(player);
    }

    public static void startReload(LivingEntity shooter, ItemStack stack, WeaponType type) {
        if (!canFire(shooter, stack, type)) {
            return;
        }
        if (Weapons.ammo(stack) >= type.magazine || Weapons.reserve(stack) <= 0) {
            if (Weapons.reserve(stack) <= 0
                    && Weapons.ammo(stack) == 0
                    && shooter instanceof ServerPlayer player) {
                player.sendSystemMessage(
                        Component.literal("Keine Munition mehr").withStyle(ChatFormatting.RED),
                        true);
            }
            return;
        }
        pendingShots.removeIf(shot -> shot.shooter() == shooter);
        reloads.put(
                shooter.getUUID(),
                new Reload(shooter, stack, type, selectedSlot(shooter), now() + type.reloadTicks));
        ServerLevel level = (ServerLevel) shooter.level();
        level.playSound(
                null,
                shooter.getX(),
                shooter.getY(),
                shooter.getZ(),
                SoundEvents.PISTON_CONTRACT,
                SoundSource.PLAYERS,
                0.5f,
                1.8f);
    }

    private static void finishReload(LivingEntity shooter, ItemStack stack, WeaponType type) {
        int ammo = Weapons.ammo(stack);
        int reserve = Weapons.reserve(stack);
        int take = CombatMath.reloadTake(ammo, reserve, type.magazine);
        Weapons.setAmmo(stack, ammo + take, reserve - take);
        ServerLevel level = (ServerLevel) shooter.level();
        level.playSound(
                null,
                shooter.getX(),
                shooter.getY(),
                shooter.getZ(),
                SoundEvents.PISTON_EXTEND,
                SoundSource.PLAYERS,
                0.5f,
                1.8f);
    }

    // ---------------------------------------------------------------- Granaten

    private static void throwGrenade(ServerPlayer player, ItemStack stack) {
        long now = now();
        Match match = Match.get();
        if (match == null
                || !match.isRunning()
                || match.teamOf(player) == null
                || !player.isAlive()
                || player.isSpectator()
                || stack.isEmpty()
                || nextShotTick.getOrDefault(player.getUUID(), 0L) > now) {
            return;
        }
        match.breakSpawnProtection(player);
        pendingShots.removeIf(shot -> shot.shooter() == player);
        nextShotTick.put(player.getUUID(), now + 20);
        ServerLevel level = (ServerLevel) player.level();
        ItemStack thrown = stack.copyWithCount(1);
        stack.shrink(1);
        ItemEntity entity =
                new ItemEntity(level, player.getX(), player.getEyeY() - 0.2, player.getZ(), thrown);
        entity.setNeverPickUp();
        entity.setDeltaMovement(player.getLookAngle().scale(1.1).add(0, 0.15, 0));
        level.addFreshEntity(entity);
        grenades.add(
                new Grenade(
                        entity, player.getUUID(), match.teamOf(player), now + GRENADE_FUSE_TICKS));
        level.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.SNOWBALL_THROW,
                SoundSource.PLAYERS,
                0.8f,
                0.6f);
    }

    private static void explode(Grenade grenade) {
        ItemEntity entity = grenade.entity();
        ServerLevel level = (ServerLevel) entity.level();
        ServerPlayer thrower = server.getPlayerList().getPlayer(grenade.thrower());
        Vec3 pos = entity.position();
        entity.discard();
        Match match = Match.get();
        if (thrower == null
                || match == null
                || !match.isRunning()
                || thrower.level() != level
                || match.teamOf(thrower) != grenade.team()) return;
        // Vanilla explosions damage outsiders, apply knockback and can trigger
        // world interactions even with NONE. Keep the entire blast match-local.
        level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y + 0.2, pos.z, 1, 0, 0, 0, 0);
        level.playSound(
                null,
                pos.x,
                pos.y,
                pos.z,
                SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS,
                1.5f,
                1f);
        Vec3 origin = pos.add(0, 0.2, 0);
        for (LivingEntity target :
                level.getEntitiesOfClass(
                        LivingEntity.class, new AABB(pos, pos).inflate(GRENADE_RADIUS))) {
            if (!canDamage(thrower, target)) continue;
            Vec3 center = target.getBoundingBox().getCenter();
            double distance = origin.distanceTo(center);
            if (distance >= GRENADE_RADIUS) continue;
            BlockHitResult cover =
                    level.clip(
                            new ClipContext(
                                    origin,
                                    center,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    entity));
            if (cover.getType() != HitResult.Type.MISS) continue;
            float amount = (float) (18 * (1 - distance / GRENADE_RADIUS));
            damage(level, thrower, target, amount, "Granate");
        }
    }

    // ---------------------------------------------------------------- Tick

    public static void tick() {
        long now = now();
        if (Match.get() == null || !Match.get().isRunning()) {
            resetRound();
            return;
        }

        pendingShots.removeIf(
                shot ->
                        selectedSlot(shot.shooter()) != shot.slot()
                                || !canFire(shot.shooter(), shot.stack(), shot.type()));
        pendingShots.tick(
                shot -> shot.fireTick() <= now,
                shot -> fireRound(shot.shooter(), shot.stack(), shot.type()));

        Iterator<Map.Entry<UUID, Reload>> it = reloads.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Reload> entry = it.next();
            Reload reload = entry.getValue();
            LivingEntity shooter = reload.shooter();
            ItemStack held = shooter.getMainHandItem();
            boolean switched =
                    shooter instanceof ServerPlayer player
                            && player.getInventory().getSelectedSlot() != reload.slot();
            if (switched
                    || held != reload.stack()
                    || Weapons.typeOf(held) != reload.type()
                    || !shooter.isAlive()
                    || shooter.isRemoved()
                    || Match.get().teamOf(shooter) == null) {
                it.remove();
                continue;
            }
            if (now >= reload.finishTick()) {
                it.remove();
                finishReload(shooter, held, reload.type());
            }
        }

        for (Grenade grenade : new ArrayList<>(grenades)) {
            if (grenade.entity().isRemoved()) {
                grenades.remove(grenade);
            } else if (now >= grenade.explodeTick() && grenades.remove(grenade)) {
                explode(grenade);
            }
        }

        if (now % 4 == 0 && Match.get() != null && Match.get().isActive()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                showAmmo(player);
            }
        }
    }

    private static void showAmmo(ServerPlayer player) {
        if (Match.get().teamOf(player) == null) return;
        ItemStack held = player.getMainHandItem();
        WeaponType type = Weapons.typeOf(held);
        if (type == null) {
            return;
        }
        Reload reload = reloads.get(player.getUUID());
        Component text;
        if (reload != null) {
            int percent =
                    (int) (100 - 100 * (reload.finishTick() - now()) / (float) type.reloadTicks);
            text =
                    Component.literal(type.displayName + "  Lade nach... " + percent + "%")
                            .withStyle(ChatFormatting.YELLOW);
        } else {
            int ammo = Weapons.ammo(held);
            text =
                    Component.literal(type.displayName + "  ")
                            .withStyle(ChatFormatting.WHITE)
                            .append(
                                    Component.literal(ammo + " / " + type.magazine)
                                            .withStyle(
                                                    ammo == 0
                                                            ? ChatFormatting.RED
                                                            : ChatFormatting.GREEN))
                            .append(
                                    Component.literal("  | " + Weapons.reserve(held))
                                            .withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(text, true);
    }

    /** Mit welcher Waffe das Opfer zuletzt getroffen wurde (fuer den Kill-Feed). */
    public static String lastWeapon(LivingEntity victim) {
        LastHit hit = lastHits.remove(victim.getUUID());
        if (hit == null || hit.tick() != now()) {
            return null;
        }
        return hit.weapon();
    }

    public static void forget(LivingEntity entity) {
        recoil.remove(entity.getUUID());
        lastHits.remove(entity.getUUID());
        reloads.remove(entity.getUUID());
        nextShotTick.remove(entity.getUUID());
        pendingShots.removeIf(shot -> shot.shooter() == entity);
    }
}
