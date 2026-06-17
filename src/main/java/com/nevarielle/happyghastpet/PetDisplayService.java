package com.nevarielle.happyghastpet;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.LivingEntity;

public final class PetDisplayService {
    private final PetConfig config;
    private final PetSoundController soundController;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public PetDisplayService(PetConfig config, PetSoundController soundController) {
        this.config = config;
        this.soundController = soundController;
    }

    public void applyPetStats(HappyGhast ghast, PetRecord pet) {
        ghast.setPersistent(true);
        ghast.setRemoveWhenFarAway(false);
        ghast.customName(legacy.deserialize(displayName(pet)));
        ghast.setCustomNameVisible(config.customNameVisible());
        soundController.applySilence(ghast);

        double speed = config.effectiveSpeed(pet.level());
        setAttribute(ghast, Attribute.MOVEMENT_SPEED, speed);
        setAttribute(ghast, Attribute.FLYING_SPEED, speed);

        double maxHealth = config.maxHealth(pet.level());
        if (maxHealth > 0) {
            setAttribute(ghast, Attribute.MAX_HEALTH, maxHealth);
            // Only clamp downward; never silently heal on rename/reload/summon.
            if (ghast.getHealth() > maxHealth) {
                ghast.setHealth(maxHealth);
            }
        }
    }

    /** Restore the pet to its full (level-appropriate) health — used as a level-up reward. */
    public void healToFull(HappyGhast ghast) {
        AttributeInstance instance = ghast.getAttribute(Attribute.MAX_HEALTH);
        if (instance != null) {
            ghast.setHealth(instance.getValue());
        }
    }

    private String displayName(PetRecord pet) {
        return config.entityNameFormat()
                .replace("{name}", pet.name())
                .replace("{level}", Integer.toString(pet.level()))
                .replace("{max_level}", Integer.toString(config.maxLevel()));
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}
