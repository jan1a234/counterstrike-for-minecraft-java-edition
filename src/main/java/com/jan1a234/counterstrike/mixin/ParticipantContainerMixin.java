package com.jan1a234.counterstrike.mixin;

import com.jan1a234.counterstrike.game.ParticipantInventoryPolicy;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ParticipantContainerMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void counterstrike$denyExternalTransaction(
            int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (!ParticipantInventoryPolicy.isIsolated(player)) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        // Reject stale external menus, Q/Ctrl-Q and cursor drops BEFORE any source stack is split.
        // Ordinary moves, equipment swaps and dragging within the player's inventory still work.
        if (menu != player.inventoryMenu
                || input == ContainerInput.THROW
                || (slot == -999 && input == ContainerInput.PICKUP)) {
            menu.sendAllDataToRemote();
            ci.cancel();
        }
    }
}
