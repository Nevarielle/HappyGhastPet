package com.nevarielle.happyghastpet;

import org.bukkit.Sound;
import org.bukkit.entity.HappyGhast;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PetSoundController {
    private final PetConfig config;
    private final Map<UUID, Long> lastAmbientSound = new HashMap<>();

    public PetSoundController(PetConfig config) {
        this.config = config;
    }

    public void applySilence(HappyGhast ghast) {
        ghast.setSilent(config.ambientSoundEnabled());
    }

    public void playReducedAmbientSound(HappyGhast ghast, PetRecord pet) {
        if (!config.ambientSoundEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastAmbientSound.getOrDefault(pet.petId(), 0L);
        if (now - previous < config.ambientSoundCooldownMillis()) {
            return;
        }
        double playChance = (100 - config.ambientSoundReductionPercent()) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() > playChance) {
            return;
        }
        ghast.getWorld().playSound(
                ghast.getLocation(),
                Sound.ENTITY_HAPPY_GHAST_AMBIENT,
                config.ambientSoundVolume(),
                config.ambientSoundPitch()
        );
        lastAmbientSound.put(pet.petId(), now);
    }

    public void clear(PetRecord pet) {
        lastAmbientSound.remove(pet.petId());
    }

    public void clearAll() {
        lastAmbientSound.clear();
    }
}
