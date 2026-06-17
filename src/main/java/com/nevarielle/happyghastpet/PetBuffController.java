package com.nevarielle.happyghastpet;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PetBuffController {
    private final PetConfig config;
    // key: petId:passengerUuid:effectName -> last applied millis
    private final Map<String, Long> lastPotionEffects = new HashMap<>();

    public PetBuffController(PetConfig config) {
        this.config = config;
    }

    public void applyPassengerBuffs(HappyGhast ghast, PetRecord pet) {
        List<ConfiguredEffect> effects = config.passengerEffects(pet.level());
        if (effects.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Entity passenger : ghast.getPassengers()) {
            if (!(passenger instanceof LivingEntity living)) {
                continue;
            }
            for (ConfiguredEffect effect : effects) {
                String key = pet.petId() + ":" + living.getUniqueId() + ":" + effect.type().getKey().value();
                long periodMillis = Math.max(1, effect.periodTicks()) * 50L;
                long previous = lastPotionEffects.getOrDefault(key, 0L);
                if (now - previous < periodMillis) {
                    continue;
                }
                living.addPotionEffect(new PotionEffect(
                        effect.type(),
                        effect.durationTicks(),
                        effect.amplifier(),
                        true,
                        false,
                        true
                ));
                lastPotionEffects.put(key, now);
            }
        }
    }

    public void clear(PetRecord pet) {
        lastPotionEffects.keySet().removeIf(key -> key.startsWith(pet.petId().toString() + ":"));
    }

    public void clearAll() {
        lastPotionEffects.clear();
    }
}
