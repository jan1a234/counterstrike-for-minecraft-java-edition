package com.jan1a234.counterstrike.bot;

/** Wie gut die Bots zielen und wie schnell sie reagieren. */
public enum BotDifficulty {
	EASY("leicht", 7f, 28),
	NORMAL("normal", 3.5f, 16),
	HARD("schwer", 1.5f, 7);

	public final String id;
	public final float aimErrorDegrees;
	public final int reactionTicks;

	BotDifficulty(String id, float aimErrorDegrees, int reactionTicks) {
		this.id = id;
		this.aimErrorDegrees = aimErrorDegrees;
		this.reactionTicks = reactionTicks;
	}

	public static BotDifficulty byId(String id) {
		for (BotDifficulty d : values()) {
			if (d.id.equalsIgnoreCase(id)) {
				return d;
			}
		}
		return null;
	}
}
