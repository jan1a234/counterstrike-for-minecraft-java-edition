package com.jan1a234.counterstrike.bot;

import com.jan1a234.counterstrike.game.CsClass;
import com.jan1a234.counterstrike.game.CsTeam;

import java.util.UUID;

/** Ein fester Bot-Platz im Team. Die UUID bleibt ueber Respawns gleich, damit Kills zaehlen. */
public class BotSlot {
	public final UUID id;
	public final String name;
	public final CsTeam team;
	public final CsClass csClass;
	public CsBot entity;
	public long respawnTick = -1;

	public BotSlot(UUID id, String name, CsTeam team, CsClass csClass) {
		this.id = id;
		this.name = name;
		this.team = team;
		this.csClass = csClass;
	}
}
