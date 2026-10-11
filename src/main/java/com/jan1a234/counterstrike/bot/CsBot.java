package com.jan1a234.counterstrike.bot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.zombie.Zombie;

/**
 * Ein Bot-Soldat. Technisch ein Zombie (damit der Client ihn ohne eigene Modelle darstellen kann),
 * aber mit eigener KI, Teamfarben-Ruestung und Waffe statt Nahkampf.
 */
public class CsBot extends Zombie {
	private BotSlot slot;

	public CsBot(ServerLevel level, BotSlot slot) {
		super(level);
		this.slot = slot;
		setPersistenceRequired();
		setCanPickUpLoot(false);
		setBaby(false);
	}

	public BotSlot slot() {
		return slot;
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new BotCombatGoal(this));
	}

	@Override
	protected boolean isSunSensitive() {
		return false;
	}

	@Override
	protected boolean convertsInWater() {
		return false;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return null;
	}
}
