package com.jan1a234.counterstrike.game;

import com.jan1a234.counterstrike.weapon.WeaponType;
import net.minecraft.ChatFormatting;

/** Die vier Klassen mit Hauptwaffe, Granaten, Tempo- und Lebensbonus. */
public enum CsClass {
	ASSAULT("sturm", "Sturmtruppler", ChatFormatting.GREEN,
			"Sturmgewehr, Pistole, Messer, 1 Granate. Allrounder.",
			WeaponType.RIFLE, 1, 0.0, 0, false),
	MACHINE_GUNNER("mg", "MG-Schuetze", ChatFormatting.RED,
			"Maschinengewehr mit 100 Schuss, Pistole, Messer. Langsamer, aber mehr Leben.",
			WeaponType.MACHINE_GUN, 0, -0.15, 6, true),
	SNIPER("sniper", "Sniper", ChatFormatting.AQUA,
			"Scharfschuetzengewehr (schleichen zum Zielen), Pistole, Messer.",
			WeaponType.SNIPER, 0, 0.0, 0, false),
	ENGINEER("pionier", "Pionier", ChatFormatting.YELLOW,
			"Schrotflinte, Pistole, Messer, 3 Granaten. Etwas schneller.",
			WeaponType.SHOTGUN, 3, 0.10, 0, false);

	public final String id;
	public final String displayName;
	public final ChatFormatting color;
	public final String description;
	public final WeaponType primary;
	public final int grenades;
	public final double speedBonus;
	public final double healthBonus;
	public final boolean heavyArmor;

	CsClass(String id, String displayName, ChatFormatting color, String description, WeaponType primary,
			int grenades, double speedBonus, double healthBonus, boolean heavyArmor) {
		this.id = id;
		this.displayName = displayName;
		this.color = color;
		this.description = description;
		this.primary = primary;
		this.grenades = grenades;
		this.speedBonus = speedBonus;
		this.healthBonus = healthBonus;
		this.heavyArmor = heavyArmor;
	}

	public static CsClass byId(String id) {
		for (CsClass c : values()) {
			if (c.id.equalsIgnoreCase(id)) {
				return c;
			}
		}
		return null;
	}
}
