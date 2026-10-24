package com.jan1a234.counterstrike.mixin;

import com.jan1a234.counterstrike.game.Match;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restore before vanilla saves/removes the player, on the server thread. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void counterstrike$restoreBeforeLogout(ServerPlayer player, CallbackInfo ci) {
        Match match = Match.get();
        if (match != null) match.onLeave(player);
    }
}
