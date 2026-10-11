package com.jan1a234.counterstrike.command;

import com.jan1a234.counterstrike.game.ArenaConfig;
import com.jan1a234.counterstrike.game.CsTeam;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.game.SpawnPoint;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;

/** Alle /cs-Befehle. Spieler duerfen Teams waehlen, alles andere brauchen Operatoren. */
public final class CsCommands {
	private static final SuggestionProvider<CommandSourceStack> TEAMS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("ct", "t"), builder);
	private static final SuggestionProvider<CommandSourceStack> TEAMS_AUTO =
			(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("ct", "t", "auto"), builder);
	private static final SuggestionProvider<CommandSourceStack> DURATIONS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(
					Arrays.stream(ArenaConfig.ALLOWED_DURATIONS).mapToObj(String::valueOf), builder);

	private CsCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cs")
				.executes(CsCommands::help)
				.then(Commands.literal("help").executes(CsCommands::help))
				.then(Commands.literal("status").executes(CsCommands::status))
				.then(Commands.literal("join")
						.then(Commands.argument("team", StringArgumentType.word()).suggests(TEAMS_AUTO)
								.executes(CsCommands::join)))
				.then(Commands.literal("leave").executes(CsCommands::leave))
				.then(Commands.literal("spawn").requires(CsCommands::isAdmin)
						.then(Commands.literal("add")
								.then(Commands.argument("team", StringArgumentType.word()).suggests(TEAMS)
										.executes(CsCommands::spawnAdd)))
						.then(Commands.literal("list").executes(CsCommands::spawnList))
						.then(Commands.literal("clear")
								.then(Commands.argument("team", StringArgumentType.word()).suggests(TEAMS)
										.executes(CsCommands::spawnClear))))
				.then(Commands.literal("time").requires(CsCommands::isAdmin)
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1)).suggests(DURATIONS)
								.executes(CsCommands::time)))
				.then(Commands.literal("bots").requires(CsCommands::isAdmin)
						.then(Commands.argument("team", StringArgumentType.word()).suggests(TEAMS)
								.then(Commands.argument("count", IntegerArgumentType.integer(0, 16))
										.executes(CsCommands::bots))))
				.then(Commands.literal("start").requires(CsCommands::isAdmin).executes(CsCommands::start))
				.then(Commands.literal("stop").requires(CsCommands::isAdmin).executes(CsCommands::stop));
		dispatcher.register(root);
	}

	private static boolean isAdmin(CommandSourceStack source) {
		return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		String[] lines = {
				"/cs join <ct|t|auto>  Team waehlen",
				"/cs leave  Team verlassen",
				"/cs status  Spielstand und Einstellungen",
				"/cs spawn add <ct|t>  Spawnpunkt an deiner Position setzen",
				"/cs spawn list | clear <ct|t>",
				"/cs time <15|30|120>  Laufzeit in Minuten",
				"/cs bots <ct|t> <anzahl>  Bots pro Team",
				"/cs start | stop",
		};
		ctx.getSource().sendSuccess(() -> Component.literal("Counter-Strike Befehle").withStyle(ChatFormatting.GOLD), false);
		for (String line : lines) {
			ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		Match match = Match.get();
		ArenaConfig config = match.config();
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> Component.literal("Status: " + stateName(match.state())).withStyle(ChatFormatting.GOLD), false);
		if (match.isActive()) {
			source.sendSuccess(() -> Component.empty().append(match.scoreLine())
					.append(Component.literal("  Restzeit " + match.timeLeftText()).withStyle(ChatFormatting.WHITE)), false);
		}
		source.sendSuccess(() -> Component.literal("Laufzeit: " + config.durationMinutes + " Minuten"), false);
		for (CsTeam team : CsTeam.values()) {
			List<String> names = match.memberNames(team);
			source.sendSuccess(() -> Match.teamName(team)
					.append(Component.literal(": " + (names.isEmpty() ? "-" : String.join(", ", names))
							+ "  | Bots: " + config.bots(team)
							+ "  | Spawns: " + config.spawns(team).size()).withStyle(ChatFormatting.GRAY)), false);
		}
		return 1;
	}

	private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String arg = StringArgumentType.getString(ctx, "team");
		Match match = Match.get();
		CsTeam team = "auto".equalsIgnoreCase(arg) ? match.smallerTeam() : CsTeam.byId(arg);
		if (team == null) {
			ctx.getSource().sendFailure(Component.literal("Unbekanntes Team. Nutze ct, t oder auto."));
			return 0;
		}
		if (team == match.teamOf(player)) {
			ctx.getSource().sendFailure(Component.literal("Du bist schon in diesem Team."));
			return 0;
		}
		if (match.teamOf(player) != null) {
			match.leave(player);
		}
		match.join(player, team);
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (Match.get().teamOf(player) == null) {
			ctx.getSource().sendFailure(Component.literal("Du bist in keinem Team."));
			return 0;
		}
		Match.get().leave(player);
		return 1;
	}

	private static int spawnAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CsTeam team = parseTeam(ctx);
		if (team == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerLevel level = (ServerLevel) player.level();
		SpawnPoint spawn = new SpawnPoint(Match.dimensionId(level),
				player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		ArenaConfig config = Match.get().config();
		config.spawns(team).add(spawn);
		config.save();
		ctx.getSource().sendSuccess(() -> Component.literal("Spawnpunkt " + config.spawns(team).size() + " fuer die ")
				.append(Match.teamName(team)).append(Component.literal(" gesetzt (" + spawn + ")")), true);
		return 1;
	}

	private static int spawnList(CommandContext<CommandSourceStack> ctx) {
		ArenaConfig config = Match.get().config();
		for (CsTeam team : CsTeam.values()) {
			ctx.getSource().sendSuccess(() -> Match.teamName(team), false);
			List<SpawnPoint> spawns = config.spawns(team);
			for (int i = 0; i < spawns.size(); i++) {
				String line = "  " + (i + 1) + ". " + spawns.get(i);
				ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
			}
		}
		return 1;
	}

	private static int spawnClear(CommandContext<CommandSourceStack> ctx) {
		CsTeam team = parseTeam(ctx);
		if (team == null) {
			return 0;
		}
		ArenaConfig config = Match.get().config();
		config.spawns(team).clear();
		config.save();
		ctx.getSource().sendSuccess(() -> Component.literal("Alle Spawnpunkte der ")
				.append(Match.teamName(team)).append(Component.literal(" geloescht")), true);
		return 1;
	}

	private static int time(CommandContext<CommandSourceStack> ctx) {
		int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
		if (Arrays.stream(ArenaConfig.ALLOWED_DURATIONS).noneMatch(m -> m == minutes)) {
			ctx.getSource().sendFailure(Component.literal("Erlaubt sind 15, 30 oder 120 Minuten."));
			return 0;
		}
		if (Match.get().isActive()) {
			ctx.getSource().sendFailure(Component.literal("Die Laufzeit kann nur vor dem Start geaendert werden."));
			return 0;
		}
		ArenaConfig config = Match.get().config();
		config.durationMinutes = minutes;
		config.save();
		ctx.getSource().sendSuccess(() -> Component.literal("Laufzeit: " + minutes + " Minuten"), true);
		return 1;
	}

	private static int bots(CommandContext<CommandSourceStack> ctx) {
		CsTeam team = parseTeam(ctx);
		if (team == null) {
			return 0;
		}
		int count = IntegerArgumentType.getInteger(ctx, "count");
		ArenaConfig config = Match.get().config();
		config.setBots(team, count);
		config.save();
		ctx.getSource().sendSuccess(() -> Component.literal(count + " Bots fuer die ")
				.append(Match.teamName(team))
				.append(Component.literal(" (Bots kommen in Schritt 3)").withStyle(ChatFormatting.GRAY)), true);
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		String error = Match.get().start();
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> ctx) {
		if (!Match.get().isActive()) {
			ctx.getSource().sendFailure(Component.literal("Es laeuft kein Spiel."));
			return 0;
		}
		Match.get().stop();
		return 1;
	}

	private static CsTeam parseTeam(CommandContext<CommandSourceStack> ctx) {
		CsTeam team = CsTeam.byId(StringArgumentType.getString(ctx, "team"));
		if (team == null) {
			ctx.getSource().sendFailure(Component.literal("Unbekanntes Team. Nutze ct oder t."));
		}
		return team;
	}

	private static String stateName(Match.State state) {
		return switch (state) {
			case LOBBY -> "Lobby";
			case COUNTDOWN -> "Countdown";
			case RUNNING -> "Spiel laeuft";
			case ENDED -> "Spielende";
		};
	}
}
