package com.jan1a234.counterstrike;

import com.jan1a234.counterstrike.command.CsCommands;
import com.jan1a234.counterstrike.game.Match;
import com.jan1a234.counterstrike.weapon.WeaponHandler;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CounterStrikeMod implements ModInitializer {
	public static final String MOD_ID = "counterstrike";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				CsCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Match.init(server);
			WeaponHandler.init(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Match.shutdown());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (Match.get() != null) {
				Match.get().tick();
				WeaponHandler.tick();
			}
		});

		UseItemCallback.EVENT.register(WeaponHandler::onUse);
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> WeaponHandler.onUse(player, level, hand));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> Match.get().allowDeath(entity));

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> Match.get().onDeath(entity, source));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> Match.get().onRespawn(newPlayer));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Match.get().onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Match.get().onLeave(handler.player));

		LOGGER.info("Counter-Strike geladen");
	}
}
