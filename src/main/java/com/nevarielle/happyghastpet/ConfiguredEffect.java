package com.nevarielle.happyghastpet;

import org.bukkit.potion.PotionEffectType;

public record ConfiguredEffect(PotionEffectType type, int durationTicks, int amplifier, int periodTicks) {
}
