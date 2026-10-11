package com.jan1a234.counterstrike.game;

import net.minecraft.ChatFormatting;
import net.minecraft.world.BossEvent;

/** Die beiden Teams im Team Deathmatch. */
public enum CsTeam {
	CT("ct", "Counter-Terroristen", ChatFormatting.BLUE, BossEvent.BossBarColor.BLUE),
	T("t", "Terroristen", ChatFormatting.GOLD, BossEvent.BossBarColor.YELLOW);

	public final String id;
	public final String displayName;
	public final ChatFormatting color;
	public final BossEvent.BossBarColor barColor;

	CsTeam(String id, String displayName, ChatFormatting color, BossEvent.BossBarColor barColor) {
		this.id = id;
		this.displayName = displayName;
		this.color = color;
		this.barColor = barColor;
	}

	public String scoreboardName() {
		return "cs_" + id;
	}

	public CsTeam opponent() {
		return this == CT ? T : CT;
	}

	public static CsTeam byId(String id) {
		for (CsTeam team : values()) {
			if (team.id.equalsIgnoreCase(id)) {
				return team;
			}
		}
		return null;
	}
}
