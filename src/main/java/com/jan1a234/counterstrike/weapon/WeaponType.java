package com.jan1a234.counterstrike.weapon;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Alle Schusswaffen. Werte: Schaden pro Treffer, Ticks zwischen zwei Abzuegen, Kugeln pro Abzug
 * (Salve), Projektile pro Kugel (Schrot), Streuung in Grad, Reichweite, Magazin, Reserve und
 * Nachladezeit in Ticks.
 */
public enum WeaponType {
    RIFLE(
            "rifle",
            "Sturmgewehr",
            Items.IRON_HORSE_ARMOR,
            4.5f,
            4,
            1,
            1,
            1.6f,
            90,
            30,
            90,
            50,
            1.8f,
            SoundEvents.FIREWORK_ROCKET_BLAST,
            1.4f),
    MACHINE_GUN(
            "mg",
            "Maschinengewehr",
            Items.DIAMOND_HORSE_ARMOR,
            3.2f,
            4,
            2,
            1,
            3.2f,
            80,
            100,
            200,
            100,
            1.6f,
            SoundEvents.FIREWORK_ROCKET_BLAST,
            1.1f),
    SNIPER(
            "sniper",
            "Scharfschuetzengewehr",
            Items.GOLDEN_HORSE_ARMOR,
            17f,
            30,
            1,
            1,
            6f,
            200,
            5,
            20,
            60,
            2.2f,
            SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
            0.6f),
    SHOTGUN(
            "shotgun",
            "Schrotflinte",
            Items.BLAZE_ROD,
            2.6f,
            16,
            1,
            8,
            7f,
            25,
            6,
            24,
            60,
            1.5f,
            SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
            1.0f),
    PISTOL(
            "pistol",
            "Pistole",
            Items.LEATHER_HORSE_ARMOR,
            4f,
            6,
            1,
            1,
            1.2f,
            60,
            12,
            48,
            30,
            1.8f,
            SoundEvents.FIREWORK_ROCKET_BLAST,
            1.8f);

    public final String id;
    public final String displayName;
    public final Item item;
    public final float damage;
    public final int cooldownTicks;
    public final int burst;
    public final int pellets;
    public final float spreadDegrees;
    public final double range;
    public final int magazine;
    public final int reserve;
    public final int reloadTicks;
    public final float headshotMultiplier;
    public final SoundEvent sound;
    public final float pitch;

    WeaponType(
            String id,
            String displayName,
            Item item,
            float damage,
            int cooldownTicks,
            int burst,
            int pellets,
            float spreadDegrees,
            double range,
            int magazine,
            int reserve,
            int reloadTicks,
            float headshotMultiplier,
            SoundEvent sound,
            float pitch) {
        this.id = id;
        this.displayName = displayName;
        this.item = item;
        this.damage = damage;
        this.cooldownTicks = cooldownTicks;
        this.burst = burst;
        this.pellets = pellets;
        this.spreadDegrees = spreadDegrees;
        this.range = range;
        this.magazine = magazine;
        this.reserve = reserve;
        this.reloadTicks = reloadTicks;
        this.headshotMultiplier = headshotMultiplier;
        this.sound = sound;
        this.pitch = pitch;
    }

    /** Das Scharfschuetzengewehr trifft nur im Schleichen (Zielen) genau. */
    public boolean needsAiming() {
        return this == SNIPER;
    }

    /** Damage remaining at maximum range; shotguns are deliberately short-range. */
    public float minimumDamageMultiplier() {
        return switch (this) {
            case SNIPER -> 0.9f;
            case SHOTGUN -> 0.2f;
            case PISTOL -> 0.5f;
            case RIFLE, MACHINE_GUN -> 0.65f;
        };
    }

    public float bloomPerShot() {
        return switch (this) {
            case RIFLE -> 0.85f;
            case MACHINE_GUN -> 1.0f;
            case SNIPER -> 2.5f;
            case SHOTGUN -> 1.5f;
            case PISTOL -> 0.9f;
        };
    }

    public static WeaponType byId(String id) {
        for (WeaponType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
