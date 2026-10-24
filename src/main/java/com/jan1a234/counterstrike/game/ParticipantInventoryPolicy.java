package com.jan1a234.counterstrike.game;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;

/** Server-side boundary between temporary match equipment and persistent survival inventories. */
public final class ParticipantInventoryPolicy {
    private ParticipantInventoryPolicy() {}

    public static boolean isIsolated(Player player) {
        if (!(player instanceof ServerPlayer)) return false;
        Match match = Match.get();
        // Team membership intentionally survives stop; it is not sufficient on its own.
        return match != null && match.isActive() && match.teamOf(player) != null;
    }

    public static boolean canTransferEquipment(Entity target) {
        // Armor stands, horses, wolves, allays, etc. can exchange items without opening a menu.
        // This is interaction-only: attacks and weapon use/reload are not intercepted.
        return target instanceof LivingEntity || target instanceof ItemFrame;
    }
}
