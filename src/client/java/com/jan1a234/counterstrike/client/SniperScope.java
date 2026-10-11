package com.jan1a234.counterstrike.client;

import com.jan1a234.counterstrike.weapon.WeaponType;
import com.jan1a234.counterstrike.weapon.Weapons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Zielfernrohr: Wer mit dem Scharfschuetzengewehr schleicht, sieht 4-fach vergroessert. */
public final class SniperScope {
	private static final float ZOOM = 0.25f;

	private SniperScope() {
	}

	public static boolean isScoped() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && player.isShiftKeyDown()
				&& Weapons.typeOf(player.getMainHandItem()) == WeaponType.SNIPER;
	}

	public static float fovMultiplier() {
		return isScoped() ? ZOOM : 1f;
	}
}
