package com.jan1a234.counterstrike.game;

import net.minecraft.ChatFormatting;
import com.jan1a234.counterstrike.bot.BotSlot;
import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.weapon.WeaponHandler;
import com.jan1a234.counterstrike.weapon.Weapons;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Ein Team-Deathmatch-Spiel. Es gibt genau eine Instanz pro Server.
 * Das Team mit den meisten Kills bei Ablauf der Zeit gewinnt.
 */
public class Match {
	public enum State { LOBBY, COUNTDOWN, RUNNING, ENDED }

	private static final int COUNTDOWN_SECONDS = 10;
	private static final int END_SCREEN_SECONDS = 10;

	private static Match instance;

	private final MinecraftServer server;
	private final ArenaConfig config;
	private final Random random = new Random();

	private final Map<UUID, CsTeam> members = new LinkedHashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, Integer> kills = new HashMap<>();
	private final Map<UUID, Integer> deaths = new HashMap<>();
	private final Map<UUID, GameType> previousGameModes = new HashMap<>();
	private final Map<UUID, CsClass> classes = new HashMap<>();
	private final List<BotSlot> bots = new ArrayList<>();
	private Objective sidebar;

	private static final String[] BOT_NAMES = {
			"Alex", "Bruno", "Chris", "Dario", "Emil", "Finn", "Gustav", "Hugo", "Ivan", "Jonas",
			"Kai", "Lars", "Milo", "Nico", "Oskar", "Paul", "Quentin", "Rico", "Sven", "Tim",
			"Udo", "Viktor", "Willi", "Xaver", "Yannik", "Zeno", "Arne", "Ben", "Carl", "Dennis",
			"Erik", "Felix"};
	private final EnumMap<CsTeam, Integer> teamKills = new EnumMap<>(CsTeam.class);

	private final ServerBossEvent bar = new ServerBossEvent(
			UUID.randomUUID(), Component.literal("Counter-Strike"), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);

	private State state = State.LOBBY;
	private int ticksLeft;
	private int totalTicks;
	private int stateTicks;

	private Match(MinecraftServer server, ArenaConfig config) {
		this.server = server;
		this.config = config;
		resetScores();
		setupScoreboardTeams();
	}

	public static void init(MinecraftServer server) {
		ArenaConfig config = ArenaConfig.load(server.getWorldPath(LevelResource.ROOT).resolve("counterstrike.json"));
		instance = new Match(server, config);
	}

	public static void shutdown() {
		if (instance != null) {
			instance.bar.removeAllPlayers();
			instance.config.save();
			instance = null;
		}
	}

	public static Match get() {
		return instance;
	}

	public ArenaConfig config() {
		return config;
	}

	public State state() {
		return state;
	}

	public boolean isRunning() {
		return state == State.RUNNING;
	}

	public boolean isActive() {
		return state == State.COUNTDOWN || state == State.RUNNING;
	}

	// ---------------------------------------------------------------- Teams

	public CsTeam teamOf(Entity entity) {
		return members.get(entity.getUUID());
	}

	public List<String> memberNames(CsTeam team) {
		List<String> result = new ArrayList<>();
		members.forEach((uuid, t) -> {
			if (t == team) {
				result.add(names.getOrDefault(uuid, "?"));
			}
		});
		return result;
	}

	public int teamSize(CsTeam team) {
		int count = 0;
		for (Map.Entry<UUID, CsTeam> entry : members.entrySet()) {
			if (entry.getValue() == team && bots.stream().noneMatch(b -> b.id.equals(entry.getKey()))) {
				count++;
			}
		}
		return count;
	}

	/** Teilt das Team zu, das gerade weniger Spieler hat. */
	public CsTeam smallerTeam() {
		return teamSize(CsTeam.CT) <= teamSize(CsTeam.T) ? CsTeam.CT : CsTeam.T;
	}

	public void join(ServerPlayer player, CsTeam team) {
		members.put(player.getUUID(), team);
		names.put(player.getUUID(), player.getName().getString());
		PlayerTeam scoreboardTeam = scoreboard().getPlayerTeam(team.scoreboardName());
		if (scoreboardTeam != null) {
			scoreboard().addPlayerToTeam(player.getScoreboardName(), scoreboardTeam);
		}
		broadcast(Component.literal(player.getName().getString() + " spielt jetzt bei den ")
				.append(teamName(team)).withStyle(ChatFormatting.GRAY));
		showClassMenu(player);
		if (isActive()) {
			enterMatch(player);
			updateSidebar(player.getUUID());
		}
	}

	public void leave(ServerPlayer player) {
		CsTeam team = members.remove(player.getUUID());
		if (team == null) {
			return;
		}
		scoreboard().removePlayerFromTeam(player.getScoreboardName());
		bar.removePlayer(player);
		if (isActive()) {
			clearEquipment(player);
		}
		restoreGameMode(player);
		broadcast(Component.literal(player.getName().getString() + " hat das Team verlassen").withStyle(ChatFormatting.GRAY));
	}

	public void onJoin(ServerPlayer player) {
		if (members.containsKey(player.getUUID()) && isActive()) {
			bar.addPlayer(player);
		}
	}

	public void onLeave(ServerPlayer player) {
		bar.removePlayer(player);
	}

	// ---------------------------------------------------------------- Ablauf

	/** Startet das Spiel. Gibt eine Fehlermeldung zurueck oder null, wenn es geklappt hat. */
	public String start() {
		if (isActive()) {
			return "Es laeuft schon ein Spiel.";
		}
		for (CsTeam team : CsTeam.values()) {
			if (config.spawns(team).isEmpty()) {
				return "Fuer die " + team.displayName + " fehlt ein Spawnpunkt (/cs spawn add " + team.id + ").";
			}
		}
		if (onlineMembers().isEmpty() && config.ctBots + config.tBots == 0) {
			return "Niemand ist in einem Team (/cs join ct|t|auto) und es sind keine Bots eingestellt.";
		}

		resetScores();
		previousGameModes.clear();
		totalTicks = config.durationMinutes * 60 * 20;
		ticksLeft = totalTicks;
		setState(State.COUNTDOWN);

		for (ServerPlayer player : onlineMembers()) {
			enterMatch(player);
		}
		createBots();
		setupSidebar();
		broadcast(Component.literal("Team Deathmatch startet! Laufzeit: " + config.durationMinutes + " Minuten")
				.withStyle(ChatFormatting.GREEN));
		return null;
	}

	/** Bricht das Spiel ohne Sieger ab. */
	public void stop() {
		if (!isActive()) {
			return;
		}
		broadcast(Component.literal("Das Spiel wurde abgebrochen.").withStyle(ChatFormatting.RED));
		finish();
		removeSidebar();
		setState(State.LOBBY);
	}

	public void tick() {
		stateTicks++;
		switch (state) {
			case COUNTDOWN -> tickCountdown();
			case RUNNING -> tickRunning();
			case ENDED -> {
				if (stateTicks >= END_SCREEN_SECONDS * 20) {
					removeSidebar();
					setState(State.LOBBY);
				}
			}
			case LOBBY -> {
			}
		}
	}

	private void tickCountdown() {
		int remaining = COUNTDOWN_SECONDS - stateTicks / 20;
		if (stateTicks % 20 == 0 && remaining > 0) {
			for (ServerPlayer player : onlineMembers()) {
				showTitle(player, Component.literal(String.valueOf(remaining)).withStyle(ChatFormatting.YELLOW),
						Component.literal("Macht euch bereit"), 0, 25, 0);
			}
		}
		updateBar();
		if (remaining <= 0) {
			setState(State.RUNNING);
			for (ServerPlayer player : onlineMembers()) {
				showTitle(player, Component.literal("Los!").withStyle(ChatFormatting.GREEN), Component.empty(), 0, 30, 10);
			}
		}
	}

	private void tickRunning() {
		ticksLeft--;
		long now = server.getTickCount();
		for (BotSlot slot : bots) {
			if (slot.respawnTick >= 0 && now >= slot.respawnTick) {
				slot.respawnTick = -1;
				spawnBot(slot);
			}
		}
		if (ticksLeft % 20 == 0) {
			updateBar();
		}
		if (ticksLeft == 60 * 20) {
			broadcast(Component.literal("Noch 1 Minute!").withStyle(ChatFormatting.YELLOW));
		}
		if (ticksLeft <= 0) {
			end();
		}
	}

	private void end() {
		int ct = teamKills.get(CsTeam.CT);
		int t = teamKills.get(CsTeam.T);
		CsTeam winner = ct > t ? CsTeam.CT : t > ct ? CsTeam.T : null;

		Component title = winner == null
				? Component.literal("Unentschieden").withStyle(ChatFormatting.WHITE)
				: Component.literal(winner.displayName + " gewinnen!").withStyle(winner.color);
		Component subtitle = scoreLine();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			showTitle(player, title, subtitle, 10, 100, 20);
		}

		broadcast(Component.literal("===== Spielende =====").withStyle(ChatFormatting.GOLD));
		broadcast(Component.empty().append(title).append(Component.literal("  ")).append(subtitle));
		List<UUID> ranking = new ArrayList<>(members.keySet());
		ranking.sort(Comparator.comparingInt((UUID id) -> kills.getOrDefault(id, 0)).reversed()
				.thenComparingInt(id -> deaths.getOrDefault(id, 0)));
		int place = 1;
		for (UUID id : ranking) {
			if (place > 5) {
				break;
			}
			CsTeam team = members.get(id);
			broadcast(Component.literal(place + ". ")
					.append(Component.literal(names.getOrDefault(id, "?")).withStyle(team.color))
					.append(Component.literal("  " + kills.getOrDefault(id, 0) + " Kills / "
							+ deaths.getOrDefault(id, 0) + " Tode").withStyle(ChatFormatting.GRAY)));
			place++;
		}

		finish();
		setState(State.ENDED);
	}

	private void finish() {
		bar.removeAllPlayers();
		removeBots();
		for (ServerPlayer player : onlineMembers()) {
			clearEquipment(player);
			restoreGameMode(player);
		}
	}

	// ---------------------------------------------------------------- Kampf

	public void onDeath(LivingEntity victim, DamageSource source) {
		if (victim instanceof CsBot bot && bot.slot() != null && bots.contains(bot.slot())) {
			bot.slot().entity = null;
			bot.slot().respawnTick = server.getTickCount() + 60;
		}
		if (state != State.RUNNING) {
			return;
		}
		CsTeam victimTeam = teamOf(victim);
		if (victimTeam == null) {
			return;
		}
		deaths.merge(victim.getUUID(), 1, Integer::sum);
		String weapon = WeaponHandler.lastWeapon(victim);
		WeaponHandler.forget(victim);

		Entity killer = source.getEntity();
		CsTeam killerTeam = killer == null ? null : teamOf(killer);
		if (killer != null && killerTeam != null && killerTeam != victimTeam) {
			kills.merge(killer.getUUID(), 1, Integer::sum);
			updateSidebar(killer.getUUID());
			teamKills.merge(killerTeam, 1, Integer::sum);
			broadcast(Component.literal(killer.getName().getString()).withStyle(killerTeam.color)
					.append(Component.literal(" [" + (weapon != null ? weapon : meleeName(killer)) + "] ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(victim.getName().getString()).withStyle(victimTeam.color)));
			updateBar();
		}
	}

	/** Vor dem Tod: Inventar leeren, damit keine Waffen auf den Boden fallen. */
	public boolean allowDeath(LivingEntity entity, DamageSource source) {
		if (isActive() && entity instanceof ServerPlayer player && teamOf(player) != null) {
			// Sofort-Respawn wie im Team Deathmatch: kein Todesbildschirm, keine Drops.
			onDeath(player, source);
			Entity killer = source.getEntity();
			showTitle(player, Component.literal("Ausgeschaltet").withStyle(ChatFormatting.RED),
					killer != null && killer != player
							? Component.literal("von " + killer.getName().getString())
							: Component.empty(), 0, 30, 10);
			player.removeAllEffects();
			player.clearFire();
			player.setDeltaMovement(Vec3.ZERO);
			player.resetFallDistance();
			preparePlayer(player);
			teleportToSpawn(player);
			showClassMenu(player);
			return false;
		}
		if (entity instanceof CsBot bot) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				bot.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
			}
		}
		return true;
	}

	private static String meleeName(Entity killer) {
		return killer instanceof LivingEntity living && !living.getMainHandItem().isEmpty()
				? living.getMainHandItem().getHoverName().getString()
				: "Faust";
	}

	public void onRespawn(ServerPlayer player) {
		if (isActive() && teamOf(player) != null) {
			preparePlayer(player);
			teleportToSpawn(player);
			showClassMenu(player);
		}
	}

	// ---------------------------------------------------------------- Hilfen

	private void enterMatch(ServerPlayer player) {
		previousGameModes.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
		bar.addPlayer(player);
		preparePlayer(player);
		teleportToSpawn(player);
	}

	private void preparePlayer(ServerPlayer player) {
		player.setGameMode(GameType.ADVENTURE);
		Loadout.equipPlayer(player, classOf(player), teamOf(player));
	}

	private void clearEquipment(ServerPlayer player) {
		player.getInventory().clearContent();
		Loadout.reset(player);
		WeaponHandler.forget(player);
	}

	// ---------------------------------------------------------------- Klassen

	public CsClass classOf(Entity entity) {
		return classes.getOrDefault(entity.getUUID(), CsClass.ASSAULT);
	}

	public void chooseClass(ServerPlayer player, CsClass csClass) {
		classes.put(player.getUUID(), csClass);
		if (state == State.COUNTDOWN && teamOf(player) != null) {
			preparePlayer(player);
			player.sendSystemMessage(Component.literal("Klasse: ").append(className(csClass)));
		} else if (state == State.RUNNING && teamOf(player) != null) {
			player.sendSystemMessage(Component.literal("Klasse ab dem naechsten Respawn: ").append(className(csClass)));
		} else {
			player.sendSystemMessage(Component.literal("Klasse: ").append(className(csClass)));
		}
	}

	public static MutableComponent className(CsClass csClass) {
		return Component.literal(csClass.displayName).withStyle(csClass.color);
	}

	/** Klickbares Klassenmenue im Chat. */
	public void showClassMenu(ServerPlayer player) {
		MutableComponent line = Component.literal("Klasse waehlen: ").withStyle(ChatFormatting.GOLD);
		for (CsClass csClass : CsClass.values()) {
			line.append(Component.literal("[" + csClass.displayName + "]").withStyle(style -> style
					.withColor(csClass.color)
					.withClickEvent(new ClickEvent.RunCommand("/cs class " + csClass.id))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(csClass.description)))));
			line.append(Component.literal(" "));
		}
		player.sendSystemMessage(line);
	}

	private void restoreGameMode(ServerPlayer player) {
		GameType previous = previousGameModes.remove(player.getUUID());
		if (previous != null) {
			player.setGameMode(previous);
		}
	}

	// ---------------------------------------------------------------- Seitenleiste

	private void setupSidebar() {
		removeSidebar();
		Objective old = scoreboard().getObjective("cs_kills");
		if (old != null) {
			scoreboard().removeObjective(old);
		}
		sidebar = scoreboard().addObjective("cs_kills", ObjectiveCriteria.DUMMY,
				Component.literal("Kills").withStyle(ChatFormatting.GOLD), ObjectiveCriteria.RenderType.INTEGER, false, null);
		scoreboard().setDisplayObjective(DisplaySlot.SIDEBAR, sidebar);
		for (UUID id : members.keySet()) {
			updateSidebar(id);
		}
	}

	private void updateSidebar(UUID id) {
		String name = names.get(id);
		if (sidebar == null || name == null) {
			return;
		}
		scoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), sidebar).set(kills.getOrDefault(id, 0));
	}

	private void removeSidebar() {
		if (sidebar != null) {
			scoreboard().removeObjective(sidebar);
			sidebar = null;
		}
	}

	// ---------------------------------------------------------------- Bots

	private void createBots() {
		removeBots();
		int nameIndex = random.nextInt(BOT_NAMES.length);
		for (CsTeam team : CsTeam.values()) {
			int classIndex = random.nextInt(CsClass.values().length);
			for (int i = 0; i < config.bots(team); i++) {
				String name = "[Bot] " + BOT_NAMES[nameIndex++ % BOT_NAMES.length];
				CsClass csClass = CsClass.values()[classIndex++ % CsClass.values().length];
				BotSlot slot = new BotSlot(UUID.randomUUID(), name, team, csClass);
				bots.add(slot);
				members.put(slot.id, team);
				names.put(slot.id, name);
				spawnBot(slot);
			}
		}
	}

	private void spawnBot(BotSlot slot) {
		List<SpawnPoint> spawns = config.spawns(slot.team);
		if (spawns.isEmpty()) {
			return;
		}
		SpawnPoint spawn = spawns.get(random.nextInt(spawns.size()));
		ServerLevel level = levelFor(spawn.dimension);
		if (level == null) {
			return;
		}
		CsBot bot = new CsBot(level, slot);
		bot.setUUID(slot.id);
		bot.snapTo(spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch);
		bot.setCustomName(Component.literal(slot.name).withStyle(slot.team.color));
		bot.setCustomNameVisible(true);
		bot.setItemSlot(EquipmentSlot.MAINHAND, Weapons.create(slot.csClass.primary));
		Loadout.equipCommon(bot, slot.csClass, slot.team);
		level.addFreshEntity(bot);
		PlayerTeam scoreboardTeam = scoreboard().getPlayerTeam(slot.team.scoreboardName());
		if (scoreboardTeam != null) {
			scoreboard().addPlayerToTeam(bot.getScoreboardName(), scoreboardTeam);
			scoreboard().addPlayerToTeam(slot.name, scoreboardTeam);
		}
		slot.entity = bot;
	}

	private void removeBots() {
		for (BotSlot slot : bots) {
			if (slot.entity != null) {
				scoreboard().removePlayerFromTeam(slot.entity.getScoreboardName());
				WeaponHandler.forget(slot.entity);
				slot.entity.discard();
			}
			scoreboard().removePlayerFromTeam(slot.name);
			members.remove(slot.id);
		}
		bots.clear();
	}

	/** Naechster lebender Gegner, optional nur mit Sichtkontakt. */
	public LivingEntity nearestEnemy(LivingEntity from, CsTeam team, double range, boolean needSight) {
		LivingEntity best = null;
		double bestDistance = range * range;
		for (LivingEntity candidate : livingMembers(team.opponent())) {
			if (candidate.level() != from.level()) {
				continue;
			}
			double distance = candidate.distanceToSqr(from);
			if (distance < bestDistance && (!needSight || from.hasLineOfSight(candidate))) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private List<LivingEntity> livingMembers(CsTeam team) {
		List<LivingEntity> result = new ArrayList<>();
		for (ServerPlayer player : onlineMembers()) {
			if (teamOf(player) == team && player.isAlive() && !player.isSpectator()) {
				result.add(player);
			}
		}
		for (BotSlot slot : bots) {
			if (slot.team == team && slot.entity != null && slot.entity.isAlive()) {
				result.add(slot.entity);
			}
		}
		return result;
	}

	public Vec3 randomSpawn(CsTeam team) {
		List<SpawnPoint> spawns = config.spawns(team);
		if (spawns.isEmpty()) {
			return null;
		}
		SpawnPoint spawn = spawns.get(random.nextInt(spawns.size()));
		return new Vec3(spawn.x, spawn.y, spawn.z);
	}

	private void teleportToSpawn(ServerPlayer player) {
		CsTeam team = teamOf(player);
		List<SpawnPoint> spawns = config.spawns(team);
		if (spawns.isEmpty()) {
			return;
		}
		SpawnPoint spawn = spawns.get(random.nextInt(spawns.size()));
		ServerLevel level = levelFor(spawn.dimension);
		if (level == null) {
			return;
		}
		player.teleportTo(level, spawn.x, spawn.y, spawn.z, Set.<Relative>of(), spawn.yaw, spawn.pitch, true);
	}

	private ServerLevel levelFor(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	public static String dimensionId(ServerLevel level) {
		return level.dimension().toString();
	}

	private List<ServerPlayer> onlineMembers() {
		List<ServerPlayer> result = new ArrayList<>();
		for (UUID id : members.keySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				result.add(player);
			}
		}
		return result;
	}

	private void setState(State newState) {
		state = newState;
		stateTicks = 0;
	}

	private void resetScores() {
		kills.clear();
		deaths.clear();
		teamKills.put(CsTeam.CT, 0);
		teamKills.put(CsTeam.T, 0);
	}

	private void updateBar() {
		int seconds = Math.max(0, (ticksLeft + 19) / 20);
		bar.setName(Component.empty().append(scoreLine())
				.append(Component.literal("   " + formatTime(seconds)).withStyle(ChatFormatting.WHITE)));
		bar.setProgress(totalTicks == 0 ? 1f : Math.max(0f, Math.min(1f, ticksLeft / (float) totalTicks)));
		int ct = teamKills.get(CsTeam.CT);
		int t = teamKills.get(CsTeam.T);
		bar.setColor(ct > t ? CsTeam.CT.barColor : t > ct ? CsTeam.T.barColor : BossEvent.BossBarColor.WHITE);
	}

	public MutableComponent scoreLine() {
		return Component.literal("CT " + teamKills.get(CsTeam.CT)).withStyle(CsTeam.CT.color)
				.append(Component.literal(" : ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(teamKills.get(CsTeam.T) + " T").withStyle(CsTeam.T.color));
	}

	public String timeLeftText() {
		return formatTime(Math.max(0, ticksLeft / 20));
	}

	private static String formatTime(int seconds) {
		int h = seconds / 3600;
		int m = (seconds % 3600) / 60;
		int s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
	}

	public static MutableComponent teamName(CsTeam team) {
		return Component.literal(team.displayName).withStyle(team.color);
	}

	private void showTitle(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	private void broadcast(Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	private ServerScoreboard scoreboard() {
		return server.getScoreboard();
	}

	private void setupScoreboardTeams() {
		for (CsTeam team : CsTeam.values()) {
			PlayerTeam scoreboardTeam = scoreboard().getPlayerTeam(team.scoreboardName());
			if (scoreboardTeam == null) {
				scoreboardTeam = scoreboard().addPlayerTeam(team.scoreboardName());
			}
			scoreboardTeam.setDisplayName(Component.literal(team.displayName));
			scoreboardTeam.setPlayerPrefix(Component.literal("[" + team.id.toUpperCase() + "] ").withStyle(team.color));
			scoreboardTeam.setAllowFriendlyFire(false);
			scoreboardTeam.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
			// Nach einem Neustart sind die Teams leer, die Zuteilung lebt nur im Speicher.
			for (String entry : new ArrayList<>(scoreboardTeam.getPlayers())) {
				scoreboard().removePlayerFromTeam(entry, scoreboardTeam);
			}
		}
	}
}
