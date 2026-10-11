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
	private static final int HIT_MEMORY_TICKS = 60;

	private static final Random RANDOM = new Random();
	private static final Map<UUID, Long> nextShotTick = new HashMap<>();
	private static final Map<UUID, Reload> reloads = new HashMap<>();
	private static final List<PendingShot> pendingShots = new ArrayList<>();
	private static final List<Grenade> grenades = new ArrayList<>();
	private static final Map<UUID, LastHit> lastHits = new HashMap<>();

	private static MinecraftServer server;

	private record Reload(LivingEntity shooter, WeaponType type, int slot, long finishTick) {
	}

	private record PendingShot(LivingEntity shooter, WeaponType type, long fireTick) {
	}

	private record Grenade(ItemEntity entity, UUID thrower, long explodeTick) {
	}

	private record LastHit(String weapon, long tick) {
	}

	private WeaponHandler() {
	}

	public static void init(MinecraftServer minecraftServer) {
		server = minecraftServer;
		nextShotTick.clear();
		reloads.clear();
		pendingShots.clear();
		grenades.clear();
		lastHits.clear();
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
			return InteractionResult.CONSUME;
		}
		if (Match.get() == null || !Match.get().isRunning() || Match.get().teamOf(serverPlayer) == null) {
			serverPlayer.sendSystemMessage(Component.literal("Waffen funktionieren nur waehrend eines Spiels.")
					.withStyle(ChatFormatting.GRAY), true);
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
		if (serverPlayer.isShiftKeyDown() && !type.needsAiming()) {
			startReload(serverPlayer, stack, type);
		} else if (serverPlayer.isShiftKeyDown() && Weapons.ammo(stack) == 0) {
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
		if (reloads.containsKey(shooter.getUUID())) {
			return false;
		}
		if (nextShotTick.getOrDefault(shooter.getUUID(), 0L) > now) {
			return false;
		}
		int ammo = Weapons.ammo(stack);
		if (ammo <= 0) {
			ServerLevel level = (ServerLevel) shooter.level();
			level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
					SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.6f);
			startReload(shooter, stack, type);
			nextShotTick.put(shooter.getUUID(), now + 10);
			return false;
		}
		int shots = Math.min(type.burst, ammo);
		Weapons.setAmmo(stack, ammo - shots, Weapons.reserve(stack));
		nextShotTick.put(shooter.getUUID(), now + type.cooldownTicks);
		if (shooter instanceof ServerPlayer player && type.cooldownTicks > 8) {
			player.getCooldowns().addCooldown(stack, type.cooldownTicks);
		}
		shoot(shooter, type);
		for (int i = 1; i < shots; i++) {
			pendingShots.add(new PendingShot(shooter, type, now + i * 2L));
		}
		return true;
	}

	private static void shoot(LivingEntity shooter, WeaponType type) {
		if (!shooter.isAlive()) {
			return;
		}
		ServerLevel level = (ServerLevel) shooter.level();
		level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(),
				type.sound, SoundSource.PLAYERS, 1.2f, type.pitch + RANDOM.nextFloat() * 0.1f);

		float spread = currentSpread(shooter, type);
		Vec3 eye = shooter.getEyePosition();
		boolean anyHit = false;
		for (int i = 0; i < type.pellets; i++) {
			Vec3 dir = applySpread(shooter.getLookAngle(), spread);
			Vec3 end = eye.add(dir.scale(type.range));
			BlockHitResult blockHit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
			if (blockHit.getType() != HitResult.Type.MISS) {
				end = blockHit.getLocation();
			}
			AABB searchBox = shooter.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
			EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(shooter, eye, end, searchBox,
					target -> canHit(shooter, target), eye.distanceToSqr(end));
			if (entityHit != null && entityHit.getEntity() instanceof LivingEntity target) {
				Vec3 hitPos = entityHit.getLocation();
				end = hitPos;
				boolean headshot = (hitPos.y - target.getY()) / target.getBbHeight() > 0.78;
				float damage = type.damage * (headshot ? type.headshotMultiplier : 1f);
				damage(level, shooter, target, damage, type.displayName);
				anyHit = true;
				level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, hitPos.x, hitPos.y, hitPos.z, 2, 0.1, 0.1, 0.1, 0.0);
				if (headshot && shooter instanceof ServerPlayer player) {
					player.sendSystemMessage(Component.literal("Kopftreffer!").withStyle(ChatFormatting.RED), true);
				}
			} else if (blockHit.getType() != HitResult.Type.MISS) {
				level.sendParticles(ParticleTypes.SMOKE, end.x, end.y, end.z, 3, 0.05, 0.05, 0.05, 0.01);
			}
			tracer(level, eye.add(dir.scale(1.2)), end);
		}
		if (anyHit) {
			level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
					SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.4f, 1.4f);
		}
	}

	private static void damage(ServerLevel level, LivingEntity shooter, LivingEntity target, float amount, String weapon) {
		DamageSource source = shooter instanceof Player player
				? level.damageSources().playerAttack(player)
				: level.damageSources().mobAttack(shooter);
		lastHits.put(target.getUUID(), new LastHit(weapon, now()));
		target.invulnerableTime = 0;
		target.hurtServer(level, source, amount);
		target.invulnerableTime = 0;
	}

	private static boolean canHit(LivingEntity shooter, Entity target) {
		if (!(target instanceof LivingEntity living) || !living.isAlive() || target == shooter || target.isSpectator()) {
			return false;
		}
		Match match = Match.get();
		if (match == null) {
			return true;
		}
		CsTeam shooterTeam = match.teamOf(shooter);
		CsTeam targetTeam = match.teamOf(target);
		return shooterTeam == null || shooterTeam != targetTeam;
	}

	private static float currentSpread(LivingEntity shooter, WeaponType type) {
		float spread = type.spreadDegrees;
		boolean aiming = shooter.isShiftKeyDown();
		if (type.needsAiming()) {
			spread = aiming ? 0.05f : spread;
		} else if (aiming) {
			spread *= 0.5f;
		}
		Vec3 motion = shooter.getDeltaMovement();
		if (motion.x * motion.x + motion.z * motion.z > 0.01) {
			spread *= 1.6f;
		}
		if (!shooter.onGround()) {
			spread *= 2f;
		}
		return spread;
	}

	private static Vec3 applySpread(Vec3 dir, float degrees) {
		if (degrees <= 0) {
			return dir;
		}
		double s = Math.tan(Math.toRadians(degrees)) * 0.5;
		return dir.add(RANDOM.nextGaussian() * s, RANDOM.nextGaussian() * s, RANDOM.nextGaussian() * s).normalize();
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

	public static void startReload(LivingEntity shooter, ItemStack stack, WeaponType type) {
		if (reloads.containsKey(shooter.getUUID())) {
			return;
		}
		if (Weapons.ammo(stack) >= type.magazine || Weapons.reserve(stack) <= 0) {
			if (Weapons.reserve(stack) <= 0 && Weapons.ammo(stack) == 0 && shooter instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal("Keine Munition mehr").withStyle(ChatFormatting.RED), true);
			}
			return;
		}
		int slot = shooter instanceof ServerPlayer player ? player.getInventory().getSelectedSlot() : -1;
		reloads.put(shooter.getUUID(), new Reload(shooter, type, slot, now() + type.reloadTicks));
		ServerLevel level = (ServerLevel) shooter.level();
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
				SoundEvents.PISTON_CONTRACT, SoundSource.PLAYERS, 0.5f, 1.8f);
	}

	private static void finishReload(LivingEntity shooter, ItemStack stack, WeaponType type) {
		int ammo = Weapons.ammo(stack);
		int reserve = Weapons.reserve(stack);
		int take = Math.min(type.magazine - ammo, reserve);
		Weapons.setAmmo(stack, ammo + take, reserve - take);
		ServerLevel level = (ServerLevel) shooter.level();
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
				SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.5f, 1.8f);
	}

	// ---------------------------------------------------------------- Granaten

	private static void throwGrenade(ServerPlayer player, ItemStack stack) {
		long now = now();
		if (nextShotTick.getOrDefault(player.getUUID(), 0L) > now) {
			return;
		}
		nextShotTick.put(player.getUUID(), now + 20);
		ServerLevel level = (ServerLevel) player.level();
		ItemStack thrown = stack.copyWithCount(1);
		stack.shrink(1);
		ItemEntity entity = new ItemEntity(level, player.getX(), player.getEyeY() - 0.2, player.getZ(), thrown);
		entity.setNeverPickUp();
		entity.setDeltaMovement(player.getLookAngle().scale(1.1).add(0, 0.15, 0));
		level.addFreshEntity(entity);
		grenades.add(new Grenade(entity, player.getUUID(), now + GRENADE_FUSE_TICKS));
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8f, 0.6f);
	}

	private static void explode(Grenade grenade) {
		ItemEntity entity = grenade.entity();
		ServerLevel level = (ServerLevel) entity.level();
		ServerPlayer thrower = server.getPlayerList().getPlayer(grenade.thrower());
		Vec3 pos = entity.position();
		entity.discard();
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(GRENADE_RADIUS * 2))) {
			lastHits.put(target.getUUID(), new LastHit("Granate", now()));
		}
		level.explode(thrower, pos.x, pos.y, pos.z, GRENADE_RADIUS, Level.ExplosionInteraction.NONE);
	}

	// ---------------------------------------------------------------- Tick

	public static void tick() {
		long now = now();

		Iterator<PendingShot> shots = pendingShots.iterator();
		while (shots.hasNext()) {
			PendingShot shot = shots.next();
			if (shot.fireTick() <= now) {
				shots.remove();
				shoot(shot.shooter(), shot.type());
			}
		}

		Iterator<Map.Entry<UUID, Reload>> it = reloads.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Reload> entry = it.next();
			Reload reload = entry.getValue();
			LivingEntity shooter = reload.shooter();
			ItemStack held = shooter.getMainHandItem();
			boolean switched = shooter instanceof ServerPlayer player
					&& player.getInventory().getSelectedSlot() != reload.slot();
			if (switched || Weapons.typeOf(held) != reload.type() || !shooter.isAlive() || shooter.isRemoved()) {
				it.remove();
				continue;
			}
			if (now >= reload.finishTick()) {
				it.remove();
				finishReload(shooter, held, reload.type());
			}
		}

		Iterator<Grenade> nades = grenades.iterator();
		while (nades.hasNext()) {
			Grenade grenade = nades.next();
			if (grenade.entity().isRemoved()) {
				nades.remove();
			} else if (now >= grenade.explodeTick()) {
				nades.remove();
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
		ItemStack held = player.getMainHandItem();
		WeaponType type = Weapons.typeOf(held);
		if (type == null) {
			return;
		}
		Reload reload = reloads.get(player.getUUID());
		Component text;
		if (reload != null) {
			int percent = (int) (100 - 100 * (reload.finishTick() - now()) / (float) type.reloadTicks);
			text = Component.literal(type.displayName + "  Lade nach... " + percent + "%").withStyle(ChatFormatting.YELLOW);
		} else {
			int ammo = Weapons.ammo(held);
			text = Component.literal(type.displayName + "  ").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(ammo + " / " + type.magazine)
							.withStyle(ammo == 0 ? ChatFormatting.RED : ChatFormatting.GREEN))
					.append(Component.literal("  | " + Weapons.reserve(held)).withStyle(ChatFormatting.GRAY));
		}
		player.sendSystemMessage(text, true);
	}

	/** Mit welcher Waffe das Opfer zuletzt getroffen wurde (fuer den Kill-Feed). */
	public static String lastWeapon(LivingEntity victim) {
		LastHit hit = lastHits.remove(victim.getUUID());
		if (hit == null || now() - hit.tick() > HIT_MEMORY_TICKS) {
			return null;
		}
		return hit.weapon();
	}

	public static void forget(LivingEntity entity) {
		reloads.remove(entity.getUUID());
		nextShotTick.remove(entity.getUUID());
		pendingShots.removeIf(shot -> shot.shooter() == entity);
	}
}
