package com.jan1a234.counterstrike.game;

import com.jan1a234.counterstrike.weapon.WeaponType;
import com.jan1a234.counterstrike.weapon.Weapons;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

/** Ruestet Spieler (und spaeter Bots) mit der Ausruestung ihrer Klasse aus. */
public final class Loadout {
    private static final Identifier SPEED_ID =
            Identifier.fromNamespaceAndPath("counterstrike", "class_speed");
    private static final Identifier HEALTH_ID =
            Identifier.fromNamespaceAndPath("counterstrike", "class_health");
    private static final int SPAWN_PROTECTION_TICKS = 60;

    private Loadout() {}

    public static int teamRgb(CsTeam team) {
        return team == CsTeam.CT ? 0x2F5BD3 : 0xD39A1F;
    }

    public static void equipPlayer(ServerPlayer player, CsClass csClass, CsTeam team) {
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        inventory.setItem(0, Weapons.create(csClass.primary));
        inventory.setItem(1, Weapons.create(WeaponType.PISTOL));
        inventory.setItem(2, knife());
        if (csClass.grenades > 0) {
            inventory.setItem(3, Weapons.grenades(csClass.grenades));
        }
        inventory.setSelectedSlot(0);
        equipCommon(player, csClass, team);
        player.getFoodData().setFoodLevel(20);
    }

    /** Ruestung, Attribute, Leben und Effekte. Gilt fuer Spieler und Bots. */
    public static void equipCommon(LivingEntity entity, CsClass csClass, CsTeam team) {
        entity.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, team));
        entity.setItemSlot(
                EquipmentSlot.CHEST,
                csClass.heavyArmor
                        ? unbreakable(new ItemStack(Items.IRON_CHESTPLATE))
                        : dyed(Items.LEATHER_CHESTPLATE, team));
        entity.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, team));
        entity.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, team));

        applyModifier(
                entity.getAttribute(Attributes.MOVEMENT_SPEED),
                SPEED_ID,
                csClass.speedBonus,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        applyModifier(
                entity.getAttribute(Attributes.MAX_HEALTH),
                HEALTH_ID,
                csClass.healthBonus,
                AttributeModifier.Operation.ADD_VALUE);
        entity.setHealth(entity.getMaxHealth());

        entity.addEffect(
                new MobEffectInstance(
                        MobEffects.SATURATION,
                        MobEffectInstance.INFINITE_DURATION,
                        0,
                        false,
                        false));
    }

    /** Entfernt Klassenboni, z.B. am Spielende. */
    public static void reset(LivingEntity entity) {
        applyModifier(
                entity.getAttribute(Attributes.MOVEMENT_SPEED),
                SPEED_ID,
                0,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        applyModifier(
                entity.getAttribute(Attributes.MAX_HEALTH),
                HEALTH_ID,
                0,
                AttributeModifier.Operation.ADD_VALUE);
        entity.removeEffect(MobEffects.SATURATION);
        if (entity.getHealth() > entity.getMaxHealth()) {
            entity.setHealth(entity.getMaxHealth());
        }
    }

    private static void applyModifier(
            AttributeInstance attribute,
            Identifier id,
            double amount,
            AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        attribute.removeModifier(id);
        if (amount != 0) {
            attribute.addTransientModifier(new AttributeModifier(id, amount, operation));
        }
    }

    public static ItemStack knife() {
        ItemStack knife = unbreakable(new ItemStack(Items.IRON_SWORD));
        knife.set(DataComponents.ITEM_NAME, Component.literal("Messer"));
        return knife;
    }

    private static ItemStack dyed(Item item, CsTeam team) {
        ItemStack stack = unbreakable(new ItemStack(item));
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(teamRgb(team)));
        return stack;
    }

    private static ItemStack unbreakable(ItemStack stack) {
        stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        return stack;
    }
}
