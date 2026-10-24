package com.jan1a234.counterstrike.mixin;

import com.jan1a234.counterstrike.game.ParticipantInventoryPolicy;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class ParticipantEntityInteractionMixin {
    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denyEquipmentExchange(
            Entity target,
            InteractionHand hand,
            Vec3 position,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (ParticipantInventoryPolicy.isIsolated((Player) (Object) this)
                && ParticipantInventoryPolicy.canTransferEquipment(target)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
