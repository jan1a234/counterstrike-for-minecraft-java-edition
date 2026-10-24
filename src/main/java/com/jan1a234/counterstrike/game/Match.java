package com.jan1a234.counterstrike.game;

import com.jan1a234.counterstrike.CounterStrikeMod;
import com.jan1a234.counterstrike.bot.BotSlot;
import com.jan1a234.counterstrike.bot.CsBot;
import com.jan1a234.counterstrike.game.arena.ArenaManager;
import com.jan1a234.counterstrike.weapon.WeaponHandler;
import com.jan1a234.counterstrike.weapon.Weapons;

import net.minecraft.ChatFormatting;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.IOException;
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
 * Ein Team-Deathmatch-Spiel. Es gibt genau eine Instanz pro Server. Das Team mit den meisten Kills
 * bei Ablauf der Zeit gewinnt.
 */
public class Match {
    public enum State {
        LOBBY,
        COUNTDOWN,
        RUNNING,
        ENDED
    }

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
    private final PlayerStateStore playerStates;
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Map<UUID, SpawnPoint> countdownPositions = new HashMap<>();
    private final EnumMap<CsTeam, Integer> spawnCursor = new EnumMap<>(CsTeam.class);
    private Objective previousSidebar;
    private final Map<ServerLevel, Set<net.minecraft.world.level.ChunkPos>> matchChunks =
            new HashMap<>();
    private static final net.minecraft.server.level.TicketType MATCH_TICKET =
            new net.minecraft.server.level.TicketType(
                    0,
                    net.minecraft.server.level.TicketType.FLAG_LOADING
                            | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                            | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final Map<UUID, CsClass> classes = new HashMap<>();
    private final List<BotSlot> bots = new ArrayList<>();
    private Objective sidebar;

    private static final String[] BOT_NAMES = {
        "Alex", "Bruno", "Chris", "Dario", "Emil", "Finn", "Gustav", "Hugo", "Ivan", "Jonas",
        "Kai", "Lars", "Milo", "Nico", "Oskar", "Paul", "Quentin", "Rico", "Sven", "Tim",
        "Udo", "Viktor", "Willi", "Xaver", "Yannik", "Zeno", "Arne", "Ben", "Carl", "Dennis",
        "Erik", "Felix"
    };
    private final EnumMap<CsTeam, Integer> teamKills = new EnumMap<>(CsTeam.class);

    private final ServerBossEvent bar =
            new ServerBossEvent(
                    UUID.randomUUID(),
                    Component.literal("Counter-Strike"),
                    BossEvent.BossBarColor.WHITE,
                    BossEvent.BossBarOverlay.PROGRESS);

    private State state = State.LOBBY;
    private int ticksLeft;
    private int totalTicks;
    private int stateTicks;

    private Match(MinecraftServer server, ArenaConfig config) {
        this.server = server;
        this.config = config;
        this.playerStates = new PlayerStateStore(server);
        resetScores();
        setupScoreboardTeams();
    }

    public static void init(MinecraftServer server) {
        ArenaConfig config =
                ArenaConfig.load(
                        server.getWorldPath(LevelResource.ROOT).resolve("counterstrike.json"));
        instance = new Match(server, config);
    }

    public static void shutdown() {
        if (instance != null) {
            instance.finish();
            instance.removeSidebar();
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
        members.forEach(
                (uuid, t) -> {
                    if (t == team) {
                        result.add(names.getOrDefault(uuid, "?"));
                    }
                });
        return result;
    }

    public int teamSize(CsTeam team) {
        int count = 0;
        for (Map.Entry<UUID, CsTeam> entry : members.entrySet()) {
            if (entry.getValue() == team
                    && bots.stream().noneMatch(b -> b.id.equals(entry.getKey()))) {
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
        if (isActive() && teamOf(player) != null) {
            player.sendSystemMessage(Component.literal("Teamwechsel erst nach der Runde."));
            return;
        }
        if (isActive() && !capturePlayer(player)) return;
        members.put(player.getUUID(), team);
        names.put(player.getUUID(), player.getName().getString());

        broadcast(
                Component.literal(player.getName().getString() + " spielt jetzt bei den ")
                        .append(teamName(team))
                        .withStyle(ChatFormatting.GRAY));
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

        bar.removePlayer(player);
        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true));
        player.sendSystemMessage(Component.empty(), true);
        WeaponHandler.forget(player);
        protectedUntil.remove(player.getUUID());
        countdownPositions.remove(player.getUUID());
        restorePlayer(player);
        broadcast(
                Component.literal(player.getName().getString() + " hat das Team verlassen")
                        .withStyle(ChatFormatting.GRAY));
    }

    public void onJoin(ServerPlayer player) {
        restorePlayer(player);
        player.sendSystemMessage(
                Component.literal("[Counter-Strike spielen]")
                        .withStyle(
                                style ->
                                        style.withColor(ChatFormatting.GOLD)
                                                .withClickEvent(
                                                        new ClickEvent.RunCommand("/cs play")))
                        .append(
                                Component.literal("  Eigene Hafenkarte · feste Spawns · /cs help")
                                        .withStyle(ChatFormatting.GRAY)));
    }

    public void onLeave(ServerPlayer player) {
        leave(player);
    }

    // ---------------------------------------------------------------- Ablauf

    /** Startet das Spiel. Gibt eine Fehlermeldung zurueck oder null, wenn es geklappt hat. */
    public String start() {
        if (isActive()) {
            return "Es laeuft schon ein Spiel.";
        }
        try {
            if ("harbor".equals(config.arena)) ArenaManager.ensureReady(server);
        } catch (RuntimeException e) {
            CounterStrikeMod.LOGGER.error("Arena konnte nicht geladen werden", e);
            return "Hafenkarte konnte nicht geladen werden. Details im Server-Log.";
        }
        for (CsTeam team : CsTeam.values()) {
            if (spawns(team).isEmpty()) {
                return "Fuer die "
                        + team.displayName
                        + " fehlt ein Spawnpunkt (/cs spawn add "
                        + team.id
                        + ").";
            }
        }
        if (onlineMembers().isEmpty() && config.ctBots + config.tBots == 0) {
            return "Niemand ist in einem Team (/cs join ct|t|auto) und es sind keine Bots"
                    + " eingestellt.";
        }

        for (CsTeam team : CsTeam.values()) {
            for (SpawnPoint spawn : spawns(team)) {
                if (!safeSpawn(spawn)) return "Unsicherer Spawn fuer " + team.id + ": " + spawn;
            }
        }
        try {
            keepMatchChunksActive();
        } catch (RuntimeException e) {
            releaseMatchChunks();
            CounterStrikeMod.LOGGER.error("Arena-Chunks konnten nicht aktiviert werden", e);
            return "Karte nicht bereit: " + e.getMessage();
        }
        for (ServerPlayer player : onlineMembers()) {
            if (!capturePlayer(player)) {
                releaseMatchChunks();
                for (ServerPlayer captured : onlineMembers()) restorePlayer(captured);
                return "Spieler-Inventar konnte nicht gesichert werden.";
            }
        }
        resetScores();
        spawnCursor.clear();
        totalTicks = config.durationMinutes * 60 * 20;
        ticksLeft = totalTicks;
        setState(State.COUNTDOWN);

        for (ServerPlayer player : onlineMembers()) {
            enterMatch(player);
        }
        createBots();
        setupSidebar();
        broadcast(
                Component.literal(
                                "Team Deathmatch startet! Laufzeit: "
                                        + config.durationMinutes
                                        + " Minuten")
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
        for (ServerPlayer player : onlineMembers())
            player.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true));
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
            case LOBBY -> {}
        }
    }

    private void tickCountdown() {
        for (BotSlot slot : bots) {
            SpawnPoint anchor = countdownPositions.get(slot.id);
            if (slot.entity != null && anchor != null) {
                slot.entity.snapTo(anchor.x, anchor.y, anchor.z, anchor.yaw, anchor.pitch);
                slot.entity.setDeltaMovement(Vec3.ZERO);
            }
        }
        for (ServerPlayer player : onlineMembers()) {
            SpawnPoint anchor = countdownPositions.get(player.getUUID());
            if (anchor != null
                    && player.position().distanceToSqr(new Vec3(anchor.x, anchor.y, anchor.z))
                            > 0.01) {
                player.teleportTo(
                        levelFor(anchor.dimension),
                        anchor.x,
                        anchor.y,
                        anchor.z,
                        Set.of(),
                        player.getYRot(),
                        player.getXRot(),
                        true);
                player.setDeltaMovement(Vec3.ZERO);
            }
        }
        int remaining = COUNTDOWN_SECONDS - stateTicks / 20;
        if (stateTicks % 20 == 0 && remaining > 0) {
            for (ServerPlayer player : onlineMembers()) {
                showTitle(
                        player,
                        Component.literal(String.valueOf(remaining))
                                .withStyle(ChatFormatting.YELLOW),
                        Component.literal("Macht euch bereit"),
                        0,
                        25,
                        0);
            }
        }
        updateBar();
        if (remaining <= 0) {
            setState(State.RUNNING);
            countdownPositions.clear();
            for (ServerPlayer player : onlineMembers()) protect(player);
            for (BotSlot bot : bots)
                if (bot.entity != null) {
                    bot.entity.setNoAi(false);
                    protect(bot.entity);
                }
            for (ServerPlayer player : onlineMembers()) {
                showTitle(
                        player,
                        Component.literal("Los!").withStyle(ChatFormatting.GREEN),
                        Component.empty(),
                        0,
                        30,
                        10);
            }
        }
    }

    private void tickRunning() {
        ticksLeft--;
        if (ticksLeft % 20 == 0 && "harbor".equals(config.arena)) {
            for (ServerPlayer player : onlineMembers())
                if (!ArenaManager.contains(player)) teleportToSpawn(player);
        }
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

        Component title =
                winner == null
                        ? Component.literal("Unentschieden").withStyle(ChatFormatting.WHITE)
                        : Component.literal(winner.displayName + " gewinnen!")
                                .withStyle(winner.color);
        Component subtitle = scoreLine();
        for (ServerPlayer player : onlineMembers()) {
            showTitle(player, title, subtitle, 10, 100, 20);
        }

        broadcast(Component.literal("===== Spielende =====").withStyle(ChatFormatting.GOLD));
        broadcast(Component.empty().append(title).append(Component.literal("  ")).append(subtitle));
        List<UUID> ranking = new ArrayList<>(members.keySet());
        ranking.sort(
                Comparator.comparingInt((UUID id) -> kills.getOrDefault(id, 0))
                        .reversed()
                        .thenComparingInt(id -> deaths.getOrDefault(id, 0)));
        int place = 1;
        for (UUID id : ranking) {
            if (place > 5) {
                break;
            }
            CsTeam team = members.get(id);
            broadcast(
                    Component.literal(place + ". ")
                            .append(
                                    Component.literal(names.getOrDefault(id, "?"))
                                            .withStyle(team.color))
                            .append(
                                    Component.literal(
                                                    "  "
                                                            + kills.getOrDefault(id, 0)
                                                            + " Kills / "
                                                            + deaths.getOrDefault(id, 0)
                                                            + " Tode")
                                            .withStyle(ChatFormatting.GRAY)));
            place++;
        }

        finish();
        setState(State.ENDED);
    }

    private void finish() {
        releaseMatchChunks();
        WeaponHandler.resetRound();
        protectedUntil.clear();
        countdownPositions.clear();
        bar.removeAllPlayers();
        removeBots();
        for (ServerPlayer player : onlineMembers()) {
            WeaponHandler.forget(player);
            restorePlayer(player);
        }
    }

    // ---------------------------------------------------------------- Kampf

    public void onDeath(LivingEntity victim, DamageSource source) {
        if (victim instanceof CsBot bot && bot.slot() != null && bots.contains(bot.slot())) {
            // Retain the corpse reference so stop/shutdown can remove it immediately.
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
            broadcast(
                    Component.literal(killer.getName().getString())
                            .withStyle(killerTeam.color)
                            .append(
                                    Component.literal(
                                                    " ["
                                                            + (weapon != null
                                                                    ? weapon
                                                                    : meleeName(killer))
                                                            + "] ")
                                            .withStyle(ChatFormatting.GRAY))
                            .append(
                                    Component.literal(victim.getName().getString())
                                            .withStyle(victimTeam.color)));
            updateBar();
        }
    }

    /** Vor dem Tod: Inventar leeren, damit keine Waffen auf den Boden fallen. */
    public boolean allowDeath(LivingEntity entity, DamageSource source) {
        if (isActive() && entity instanceof ServerPlayer player && teamOf(player) != null) {
            // Sofort-Respawn wie im Team Deathmatch: kein Todesbildschirm, keine Drops.
            onDeath(player, source);
            Entity killer = source.getEntity();
            showTitle(
                    player,
                    Component.literal("Ausgeschaltet").withStyle(ChatFormatting.RED),
                    killer != null && killer != player
                            ? Component.literal("von " + killer.getName().getString())
                            : Component.empty(),
                    0,
                    30,
                    10);
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
        if (!capturePlayer(player)) return;
        PlayerTeam team = scoreboard().getPlayerTeam(teamOf(player).scoreboardName());
        if (team != null) scoreboard().addPlayerToTeam(player.getScoreboardName(), team);
        bar.addPlayer(player);
        preparePlayer(player);
        teleportToSpawn(player);
    }

    private void preparePlayer(ServerPlayer player) {
        WeaponHandler.forget(player);
        player.removeAllEffects();
        player.clearFire();
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        player.setGameMode(GameType.ADVENTURE);
        Loadout.equipPlayer(player, classOf(player), teamOf(player));
        protect(player);
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
            player.sendSystemMessage(
                    Component.literal("Klasse ab dem naechsten Respawn: ")
                            .append(className(csClass)));
        } else {
            player.sendSystemMessage(Component.literal("Klasse: ").append(className(csClass)));
        }
    }

    public static MutableComponent className(CsClass csClass) {
        return Component.literal(csClass.displayName).withStyle(csClass.color);
    }

    /** Klickbares Klassenmenue im Chat. */
    public void showClassMenu(ServerPlayer player) {
        MutableComponent line =
                Component.literal("Klasse waehlen: ").withStyle(ChatFormatting.GOLD);
        for (CsClass csClass : CsClass.values()) {
            line.append(
                    Component.literal("[" + csClass.displayName + "]")
                            .withStyle(
                                    style ->
                                            style.withColor(csClass.color)
                                                    .withClickEvent(
                                                            new ClickEvent.RunCommand(
                                                                    "/cs class " + csClass.id))
                                                    .withHoverEvent(
                                                            new HoverEvent.ShowText(
                                                                    Component.literal(
                                                                            csClass.description)))));
            line.append(Component.literal(" "));
        }
        player.sendSystemMessage(line);
    }

    private boolean capturePlayer(ServerPlayer player) {
        try {
            playerStates.capture(player);
            return true;
        } catch (IOException | RuntimeException e) {
            CounterStrikeMod.LOGGER.error(
                    "Inventar konnte nicht gesichert werden: {}", player.getUUID(), e);
            player.sendSystemMessage(
                    Component.literal(
                                    "Beitritt abgebrochen: Inventar konnte nicht sicher gespeichert"
                                            + " werden.")
                            .withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private void restorePlayer(ServerPlayer player) {
        try {
            playerStates.restore(player);
            player.sendSystemMessage(Component.empty(), true);
        } catch (IOException | RuntimeException e) {
            CounterStrikeMod.LOGGER.error(
                    "Spieler-Sicherung konnte nicht wiederhergestellt werden: {}",
                    player.getUUID(),
                    e);
            player.connection.disconnect(
                    Component.literal(
                            "Spielerzustand konnte nicht sicher wiederhergestellt werden. Sicherung"
                                    + " bleibt erhalten; bitte Admin informieren."));
        }
    }

    public boolean isSpawnProtected(Entity entity) {
        return protectedUntil.getOrDefault(entity.getUUID(), 0L) > server.getTickCount();
    }

    public void breakSpawnProtection(Entity entity) {
        protectedUntil.remove(entity.getUUID());
    }

    private void protect(Entity entity) {
        protectedUntil.put(entity.getUUID(), (long) server.getTickCount() + 60);
    }

    public boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        if (!isActive()) return true;
        CsTeam victimTeam = teamOf(victim);
        Entity attacker = source.getEntity();
        CsTeam attackerTeam = attacker == null ? null : teamOf(attacker);
        if (victimTeam == null && attackerTeam == null) return true;
        if (victimTeam != null
                && attacker instanceof net.minecraft.world.entity.player.Player
                && attackerTeam == null) return false;
        if (!isRunning()) return false;
        if (attackerTeam != null) {
            breakSpawnProtection(attacker);
            if (victimTeam == null || attackerTeam == victimTeam) return false;
        }
        return !isSpawnProtected(victim);
    }

    public List<SpawnPoint> spawns(CsTeam team) {
        return "harbor".equals(config.arena) ? ArenaManager.spawns(team) : config.spawns(team);
    }

    private boolean safeSpawn(SpawnPoint spawn) {
        if (!ArenaConfig.validSpawn(spawn)) return false;
        ServerLevel level = levelFor(spawn.dimension);
        if (level == null) return false;
        net.minecraft.core.BlockPos feet =
                net.minecraft.core.BlockPos.containing(spawn.x, spawn.y, spawn.z);
        return level.getBlockState(feet.below()).isSolidRender()
                && level.noCollision(
                        new net.minecraft.world.phys.AABB(
                                spawn.x - 0.3,
                                spawn.y,
                                spawn.z - 0.3,
                                spawn.x + 0.3,
                                spawn.y + 1.9,
                                spawn.z + 0.3));
    }

    // ---------------------------------------------------------------- Seitenleiste

    private void setupSidebar() {
        removeSidebar();
        previousSidebar = scoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        Objective old = scoreboard().getObjective("cs_kills");
        if (old != null) {
            scoreboard().removeObjective(old);
        }
        sidebar =
                scoreboard()
                        .addObjective(
                                "cs_kills",
                                ObjectiveCriteria.DUMMY,
                                Component.literal("Kills").withStyle(ChatFormatting.GOLD),
                                ObjectiveCriteria.RenderType.INTEGER,
                                false,
                                null);
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
        scoreboard()
                .getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), sidebar)
                .set(kills.getOrDefault(id, 0));
    }

    private void removeSidebar() {
        if (sidebar != null) {
            scoreboard().removeObjective(sidebar);
            sidebar = null;
            if (previousSidebar != null
                    && scoreboard().getObjective(previousSidebar.getName()) == previousSidebar)
                scoreboard().setDisplayObjective(DisplaySlot.SIDEBAR, previousSidebar);
            previousSidebar = null;
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
        List<SpawnPoint> spawns = spawns(slot.team);
        if (spawns.isEmpty()) {
            return;
        }
        SpawnPoint spawn = selectSpawn(slot.team);
        ServerLevel level = levelFor(spawn.dimension);
        if (level == null) {
            return;
        }
        CsBot bot = new CsBot(level, slot);
        bot.setUUID(slot.id);
        bot.setNoAi(state == State.COUNTDOWN);
        if (state == State.COUNTDOWN) countdownPositions.put(slot.id, spawn);
        bot.snapTo(spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch);
        bot.setCustomName(Component.literal(slot.name).withStyle(slot.team.color));
        bot.setCustomNameVisible(true);
        bot.setItemSlot(EquipmentSlot.MAINHAND, Weapons.create(slot.csClass.primary));
        Loadout.equipCommon(bot, slot.csClass, slot.team);
        protect(bot);
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
            names.remove(slot.id);
            classes.remove(slot.id);
            protectedUntil.remove(slot.id);
        }
        bots.clear();
    }

    /** Naechster lebender Gegner, optional nur mit Sichtkontakt. */
    public LivingEntity nearestEnemy(
            LivingEntity from, CsTeam team, double range, boolean needSight) {
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
        List<SpawnPoint> spawns = spawns(team);
        if (spawns.isEmpty()) {
            return null;
        }
        SpawnPoint spawn = spawns.get(random.nextInt(spawns.size()));
        return new Vec3(spawn.x, spawn.y, spawn.z);
    }

    private void teleportToSpawn(ServerPlayer player) {
        CsTeam team = teamOf(player);
        List<SpawnPoint> spawns = spawns(team);
        if (spawns.isEmpty()) {
            return;
        }
        SpawnPoint spawn = selectSpawn(team);
        ServerLevel level = levelFor(spawn.dimension);
        if (level == null) {
            return;
        }
        player.teleportTo(
                level, spawn.x, spawn.y, spawn.z, Set.<Relative>of(), spawn.yaw, spawn.pitch, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        protect(player);
        if (state == State.COUNTDOWN) countdownPositions.put(player.getUUID(), spawn);
    }

    private void keepMatchChunksActive() {
        releaseMatchChunks();
        if ("harbor".equals(config.arena)) {
            ServerLevel level = ArenaManager.level(server);
            for (int x = -3; x <= 2; x++) for (int z = -2; z <= 1; z++) addMatchChunk(level, x, z);
        } else {
            // Lease the travel area, not only spawn chunks. A one-chunk margin permits
            // paths around cover; reject excessive footprints before taking any tickets.
            Map<ServerLevel, int[]> bounds = new HashMap<>();
            for (CsTeam team : CsTeam.values()) {
                for (SpawnPoint spawn : spawns(team)) {
                    int x = Math.floorDiv((int) Math.floor(spawn.x), 16);
                    int z = Math.floorDiv((int) Math.floor(spawn.z), 16);
                    int[] area =
                            bounds.computeIfAbsent(
                                    levelFor(spawn.dimension), level -> new int[] {x, x, z, z});
                    area[0] = Math.min(area[0], x);
                    area[1] = Math.max(area[1], x);
                    area[2] = Math.min(area[2], z);
                    area[3] = Math.max(area[3], z);
                }
            }
            long chunks = 0;
            for (int[] area : bounds.values()) {
                chunks += ((long) area[1] - area[0] + 3) * ((long) area[3] - area[2] + 3);
                if (chunks > 256)
                    throw new IllegalArgumentException(
                            "Custom-Karte zu gross: Spawn-Rechteck mit Rand darf insgesamt 256"
                                + " Chunks umfassen.");
            }
            bounds.forEach(
                    (level, area) -> {
                        for (int x = area[0] - 1; x <= area[1] + 1; x++) {
                            for (int z = area[2] - 1; z <= area[3] + 1; z++)
                                addMatchChunk(level, x, z);
                        }
                    });
        }
    }

    private void addMatchChunk(ServerLevel level, int x, int z) {
        var position = new net.minecraft.world.level.ChunkPos(x, z);
        if (matchChunks.computeIfAbsent(level, key -> new java.util.HashSet<>()).add(position)) {
            // Nonpersistent, mod-owned ticket: bots tick without spectators, and admin /forceload
            // tickets are never altered. Radius 2 requests an entity-ticking central chunk.
            var ready =
                    level.getChunkSource().addTicketAndLoadWithRadius(MATCH_TICKET, position, 2);
            server.managedBlock(ready::isDone);
            ready.join();
            level.waitForEntities(position, 0);
        }
    }

    private void releaseMatchChunks() {
        matchChunks.forEach(
                (level, positions) ->
                        positions.forEach(
                                position ->
                                        level.getChunkSource()
                                                .removeTicketWithRadius(
                                                        MATCH_TICKET, position, 2)));
        matchChunks.clear();
    }

    private SpawnPoint selectSpawn(CsTeam team) {
        List<SpawnPoint> points = spawns(team);
        int cursor = spawnCursor.getOrDefault(team, 0);
        SpawnPoint best = points.get(Math.floorMod(cursor, points.size()));
        double bestScore = -Double.MAX_VALUE;
        List<LivingEntity> allies = livingMembers(team);
        List<LivingEntity> enemies = livingMembers(team.opponent());
        for (int i = 0; i < points.size(); i++) {
            SpawnPoint point = points.get(Math.floorMod(cursor + i, points.size()));
            if (!safeSpawn(point)) continue;
            Vec3 position = new Vec3(point.x, point.y, point.z);
            double score = 10000;
            for (LivingEntity enemy : enemies) {
                if (dimensionId((ServerLevel) enemy.level()).equals(point.dimension))
                    score = Math.min(score, enemy.position().distanceToSqr(position));
            }
            for (LivingEntity ally : allies) {
                if (dimensionId((ServerLevel) ally.level()).equals(point.dimension)
                        && ally.position().distanceToSqr(position) < 2.25) score -= 100000;
            }
            if (score > bestScore) {
                best = point;
                bestScore = score;
            }
        }
        spawnCursor.put(team, Math.floorMod(points.indexOf(best) + 1, points.size()));
        return best;
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
        bar.setName(
                Component.empty()
                        .append(scoreLine())
                        .append(
                                Component.literal("   " + formatTime(seconds))
                                        .withStyle(ChatFormatting.WHITE)));
        bar.setProgress(
                totalTicks == 0 ? 1f : Math.max(0f, Math.min(1f, ticksLeft / (float) totalTicks)));
        int ct = teamKills.get(CsTeam.CT);
        int t = teamKills.get(CsTeam.T);
        bar.setColor(
                ct > t
                        ? CsTeam.CT.barColor
                        : t > ct ? CsTeam.T.barColor : BossEvent.BossBarColor.WHITE);
    }

    public MutableComponent scoreLine() {
        return Component.literal("CT " + teamKills.get(CsTeam.CT))
                .withStyle(CsTeam.CT.color)
                .append(Component.literal(" : ").withStyle(ChatFormatting.WHITE))
                .append(
                        Component.literal(teamKills.get(CsTeam.T) + " T")
                                .withStyle(CsTeam.T.color));
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

    private void showTitle(
            ServerPlayer player,
            Component title,
            Component subtitle,
            int fadeIn,
            int stay,
            int fadeOut) {
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
            scoreboardTeam.setPlayerPrefix(
                    Component.literal("[" + team.id.toUpperCase() + "] ").withStyle(team.color));
            scoreboardTeam.setAllowFriendlyFire(false);
            scoreboardTeam.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
            // Nach einem Neustart sind die Teams leer, die Zuteilung lebt nur im Speicher.
            for (String entry : new ArrayList<>(scoreboardTeam.getPlayers())) {
                scoreboard().removePlayerFromTeam(entry, scoreboardTeam);
            }
        }
    }
}
