package com.jan1a234.counterstrike.mixin;

import com.jan1a234.counterstrike.game.ParticipantInventoryPolicy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class ParticipantServerPlayerMixin {
    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denyExternalMenu(
            MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (ParticipantInventoryPolicy.isIsolated((ServerPlayer) (Object) this)) {
            cir.setReturnValue(OptionalInt.empty());
        }
    }

    @Inject(method = "openHorseInventory", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denyHorseMenu(
            AbstractHorse horse, Container inventory, CallbackInfo ci) {
        if (ParticipantInventoryPolicy.isIsolated((ServerPlayer) (Object) this)) ci.cancel();
    }

    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denySelectedDrop(boolean entireStack, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (ParticipantInventoryPolicy.isIsolated(player)) {
            // Must run before removeFromSelected, not merely suppress the resulting entity.
            player.containerMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }

    @Inject(
            method =
                    "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            cancellable = true)
    private void counterstrike$denyStackDrop(
            ItemStack stack,
            boolean random,
            boolean record,
            CallbackInfoReturnable<ItemEntity> cir) {
        // The inherited Player.drop(stack, record) delegates to this virtual overload as well.
        if (ParticipantInventoryPolicy.isIsolated((ServerPlayer) (Object) this))
            cir.setReturnValue(null);
    }
}
