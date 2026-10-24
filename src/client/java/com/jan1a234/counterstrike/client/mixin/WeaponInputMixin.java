package com.jan1a234.counterstrike.client.mixin;

import com.jan1a234.counterstrike.weapon.Weapons;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Route gun input independently of the crosshair's vanilla block/entity interaction target. */
@Mixin(Minecraft.class)
public abstract class WeaponInputMixin {
    @Shadow private int rightClickDelay;
    @Unique private LocalPlayer counterstrike$lastPlayer;
    @Unique private int counterstrike$lastFireTick = Integer.MIN_VALUE;

    @Unique
    private boolean counterstrike$holdingGun() {
        Minecraft client = (Minecraft) (Object) this;
        return client.player != null
                && !client.player.isSpectator()
                && Weapons.typeOf(client.player.getMainHandItem()) != null;
    }

    @Unique
    private void counterstrike$requestShot() {
        Minecraft client = (Minecraft) (Object) this;
        LocalPlayer player = client.player;
        if (player == null
                || client.gameMode == null
                || client.getConnection() == null
                || client.gui.screen() != null
                || client.gui.overlay() != null
                || !client.mouseHandler.isMouseGrabbed()
                || !client.isWindowActive()
                || client.isPaused()
                || !player.isAlive()
                || player.isSpectator()
                || player.isUsingItem()) return;
        // Click, held attack and both buttons can coincide. One request per client tick;
        // actual cadence, ammunition, reload and match membership remain server-authoritative.
        if (counterstrike$lastPlayer == player && counterstrike$lastFireTick == player.tickCount)
            return;
        counterstrike$lastPlayer = player;
        counterstrike$lastFireTick = player.tickCount;
        // Fabric's successful UseItemCallback cancels before vanilla synchronizes the slot.
        ((MultiPlayerGameModeInvoker) client.gameMode).counterstrike$ensureHasSentCarriedItem();
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void counterstrike$fireOnAttack(CallbackInfoReturnable<Boolean> cir) {
        if (!counterstrike$holdingGun()) return;
        counterstrike$requestShot();
        cir.setReturnValue(false); // No vanilla melee, mining or miss-delay for firearms.
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void counterstrike$holdTrigger(boolean held, CallbackInfo ci) {
        if (!counterstrike$holdingGun()) return;
        if (held) counterstrike$requestShot();
        ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void counterstrike$fireOnUse(CallbackInfo ci) {
        if (!counterstrike$holdingGun()) return;
        rightClickDelay = 4; // Retain the existing right-button held-fire cadence.
        counterstrike$requestShot();
        ci.cancel(); // Do not equip horse armor, open containers or use the offhand instead.
    }
}
