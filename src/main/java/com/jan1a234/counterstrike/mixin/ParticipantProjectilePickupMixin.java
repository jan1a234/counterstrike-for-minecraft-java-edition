package com.jan1a234.counterstrike.mixin;

import com.jan1a234.counterstrike.game.ParticipantInventoryPolicy;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractArrow.class)
public abstract class ParticipantProjectilePickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denyProjectilePickup(Player player, CallbackInfo ci) {
        // Arrows/tridents are not ItemEntity instances and have their own inventory insertion path.
        if (ParticipantInventoryPolicy.isIsolated(player)) ci.cancel();
    }
}
