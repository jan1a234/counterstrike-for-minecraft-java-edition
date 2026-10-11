package com.jan1a234.counterstrike.game;

/** Ein gespeicherter Spawnpunkt. Die Dimension wird als Text des ResourceKey abgelegt. */
public class SpawnPoint {
	public String dimension;
	public double x;
	public double y;
	public double z;
	public float yaw;
	public float pitch;

	public SpawnPoint() {
	}

	public SpawnPoint(String dimension, double x, double y, double z, float yaw, float pitch) {
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
	}

	@Override
	public String toString() {
		return String.format("%.1f / %.1f / %.1f", x, y, z);
	}
}
