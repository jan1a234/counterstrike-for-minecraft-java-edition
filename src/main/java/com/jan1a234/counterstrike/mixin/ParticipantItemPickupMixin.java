package com.jan1a234.counterstrike.mixin;

import com.jan1a234.counterstrike.game.ParticipantInventoryPolicy;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ItemEntity.class, net.minecraft.world.entity.ExperienceOrb.class})
public abstract class ParticipantItemPickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denyWorldPickup(Player player, CallbackInfo ci) {
        // Leave both the stack and entity intact: they may belong to a survival bystander.
        // Do not globally block Inventory.add, which also returns the player's own cursor/crafting
        // stacks.
        if (ParticipantInventoryPolicy.isIsolated(player)) ci.cancel();
    }
}
