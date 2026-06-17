package com.nevarielle.happyghastpet;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static void validate(JavaPlugin plugin) {
        validateLevels(plugin);
        validateFeeding(plugin);
        validateResurrection(plugin);
        validateIdle(plugin);
        validateSounds(plugin);
    }

    private static void validateLevels(JavaPlugin plugin) {
        ConfigurationSection levels = plugin.getConfig().getConfigurationSection("levels");
        if (levels == null) {
            plugin.getLogger().warning("Config path levels is missing. Built-in fallbacks will be used.");
            return;
        }
        if (!levels.contains("1")) {
            plugin.getLogger().warning("Config path levels.1 is missing. Existing pets still load, but level display may use fallbacks.");
        }
        for (String key : levels.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(key);
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Invalid level key levels." + key + ": level keys must be numbers.");
                continue;
            }
            String path = "levels." + level;
            int requiredExp = plugin.getConfig().getInt(path + ".required-exp", 0);
            if (requiredExp < 0) {
                plugin.getLogger().warning("Invalid " + path + ".required-exp: must be >= 0.");
            }
            double speedMultiplier = plugin.getConfig().getDouble(path + ".speed-multiplier", 1.0);
            if (speedMultiplier <= 0.0 || speedMultiplier > 10.0) {
                plugin.getLogger().warning("Suspicious " + path + ".speed-multiplier: " + speedMultiplier);
            }
            double maxHealth = plugin.getConfig().getDouble(path + ".max-health", -1.0);
            if (maxHealth == 0.0 || maxHealth > 1000.0) {
                plugin.getLogger().warning("Suspicious " + path + ".max-health: " + maxHealth);
            }
            validatePassengerEffects(plugin, path);
        }
    }

    private static void validatePassengerEffects(JavaPlugin plugin, String levelPath) {
        List<Map<?, ?>> effects = plugin.getConfig().getMapList(levelPath + ".passenger-effects");
        for (Map<?, ?> effect : effects) {
            Object typeValue = effect.get("type");
            if (typeValue == null) {
                plugin.getLogger().warning("Missing type in " + levelPath + ".passenger-effects.");
                continue;
            }
            if (PotionEffectType.getByName(typeValue.toString()) == null) {
                plugin.getLogger().warning("Unknown potion effect in " + levelPath + ".passenger-effects: " + typeValue);
            }
            warnIfNegative(plugin, levelPath + ".passenger-effects.duration-ticks", effect.get("duration-ticks"));
            warnIfNegative(plugin, levelPath + ".passenger-effects.amplifier", effect.get("amplifier"));
            warnIfNegative(plugin, levelPath + ".passenger-effects.period-ticks", effect.get("period-ticks"));
        }
    }

    private static void validateFeeding(JavaPlugin plugin) {
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("feeding.items");
        if (items == null) {
            plugin.getLogger().warning("Config path feeding.items is missing. Feeding will not give experience.");
            return;
        }
        for (String materialName : items.getKeys(false)) {
            if (Material.matchMaterial(materialName) == null) {
                plugin.getLogger().warning("Unknown feeding material: feeding.items." + materialName);
            }
            int exp;
            ConfigurationSection item = items.getConfigurationSection(materialName);
            if (item != null) {
                exp = item.getInt("exp", 0);
                if (item.getInt("daily-max-items", 0) < 0) {
                    plugin.getLogger().warning("Invalid feeding.items." + materialName + ".daily-max-items: must be >= 0.");
                }
            } else {
                exp = plugin.getConfig().getInt("feeding.items." + materialName, 0);
            }
            if (exp < 0) {
                plugin.getLogger().warning("Invalid feeding.items." + materialName + " exp: must be >= 0.");
            }
        }
    }

    private static void validateIdle(JavaPlugin plugin) {
        String mode = plugin.getConfig().getString("idle.mode", "PARKED").toUpperCase(Locale.ROOT);
        if (!mode.equals("OFF") && !mode.equals("PARKED") && !mode.equals("FREEZE")) {
            plugin.getLogger().warning("Invalid idle.mode: " + mode + ". Allowed: OFF, PARKED, FREEZE.");
        }
        if (plugin.getConfig().getDouble("idle.max-distance-from-park", 2.0) < 0.0) {
            plugin.getLogger().warning("Invalid idle.max-distance-from-park: must be >= 0.");
        }
        if (plugin.getConfig().getDouble("idle.return-speed", 0.15) < 0.0) {
            plugin.getLogger().warning("Invalid idle.return-speed: must be >= 0.");
        }
    }

    private static void validateResurrection(JavaPlugin plugin) {
        String requiredBlock = plugin.getConfig().getString("resurrection.required-block", "SOUL_CAMPFIRE");
        if (requiredBlock == null || Material.matchMaterial(requiredBlock) == null) {
            plugin.getLogger().warning("Unknown resurrection.required-block: " + requiredBlock);
        }
        if (plugin.getConfig().getInt("resurrection.block-radius", 3) < 0) {
            plugin.getLogger().warning("Invalid resurrection.block-radius: must be >= 0.");
        }
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("resurrection.items");
        if (items == null) {
            plugin.getLogger().warning("Config path resurrection.items is missing. Built-in resurrection cost will be used.");
            return;
        }
        for (String materialName : items.getKeys(false)) {
            if (Material.matchMaterial(materialName) == null) {
                plugin.getLogger().warning("Unknown resurrection material: resurrection.items." + materialName);
            }
            if (items.getInt(materialName, 0) <= 0) {
                plugin.getLogger().warning("Invalid resurrection.items." + materialName + ": must be > 0.");
            }
        }
    }

    private static void validateSounds(JavaPlugin plugin) {
        int reduction = plugin.getConfig().getInt("sounds.ambient.reduction-percent", 80);
        if (reduction < 0 || reduction > 100) {
            plugin.getLogger().warning("Invalid sounds.ambient.reduction-percent: must be from 0 to 100.");
        }
        if (plugin.getConfig().getLong("sounds.ambient.min-cooldown-seconds", 15L) < 0L) {
            plugin.getLogger().warning("Invalid sounds.ambient.min-cooldown-seconds: must be >= 0.");
        }
    }

    private static void warnIfNegative(JavaPlugin plugin, String path, Object value) {
        if (value instanceof Number number && number.intValue() < 0) {
            plugin.getLogger().warning("Invalid " + path + ": must be >= 0.");
        }
    }
}
