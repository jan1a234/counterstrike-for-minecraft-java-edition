package com.jan1a234.counterstrike.weapon;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/** Erzeugt Waffen-Items und liest bzw. schreibt ihre Munition. Alles steckt in den Custom Data des Items. */
public final class Weapons {
	public static final String KEY_WEAPON = "cs_weapon";
	public static final String KEY_AMMO = "cs_ammo";
	public static final String KEY_RESERVE = "cs_reserve";
	public static final String GRENADE = "grenade";

	private Weapons() {
	}

	public static ItemStack create(WeaponType type) {
		ItemStack stack = new ItemStack(type.item);
		CompoundTag tag = new CompoundTag();
		tag.putString(KEY_WEAPON, type.id);
		tag.putInt(KEY_AMMO, type.magazine);
		tag.putInt(KEY_RESERVE, type.reserve);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal(type.displayName).withStyle(ChatFormatting.WHITE));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Rechtsklick: Schiessen").withStyle(ChatFormatting.GRAY),
				Component.literal("Schleichen + Rechtsklick: Nachladen").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	public static ItemStack grenades(int count) {
		ItemStack stack = new ItemStack(Items.FIREWORK_STAR, count);
		CompoundTag tag = new CompoundTag();
		tag.putString(KEY_WEAPON, GRENADE);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("Granate").withStyle(ChatFormatting.RED));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Rechtsklick: Werfen").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	private static CompoundTag tag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.copyTag();
	}

	/** Die Waffen-ID des Items, oder null fuer normale Items. */
	public static String weaponId(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		CompoundTag tag = tag(stack);
		if (tag == null) {
			return null;
		}
		String id = tag.getStringOr(KEY_WEAPON, "");
		return id.isEmpty() ? null : id;
	}

	public static WeaponType typeOf(ItemStack stack) {
		String id = weaponId(stack);
		return id == null ? null : WeaponType.byId(id);
	}

	public static boolean isGrenade(ItemStack stack) {
		return GRENADE.equals(weaponId(stack));
	}

	public static int ammo(ItemStack stack) {
		CompoundTag tag = tag(stack);
		return tag == null ? 0 : tag.getIntOr(KEY_AMMO, 0);
	}

	public static int reserve(ItemStack stack) {
		CompoundTag tag = tag(stack);
		return tag == null ? 0 : tag.getIntOr(KEY_RESERVE, 0);
	}

	public static void setAmmo(ItemStack stack, int ammo, int reserve) {
		CompoundTag tag = tag(stack);
		if (tag == null) {
			return;
		}
		tag.putInt(KEY_AMMO, ammo);
		tag.putInt(KEY_RESERVE, reserve);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}
}
