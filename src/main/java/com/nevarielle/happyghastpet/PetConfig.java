package com.nevarielle.happyghastpet;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PetConfig {
    private final JavaPlugin plugin;

    public PetConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public int maxPetsPerPlayer() {
        return plugin.getConfig().getInt("max-pets-per-player", 2);
    }

    public double targetDistance() {
        return plugin.getConfig().getDouble("target-distance", 8.0);
    }

    public int tickPeriodTicks() {
        return Math.max(1, plugin.getConfig().getInt("tick-period-ticks", 40));
    }

    public long locationSavePeriodMillis() {
        return Math.max(1L, plugin.getConfig().getLong("storage.location-save-period-ticks", 100L)) * 50L;
    }

    public int dailyLimit() {
        return plugin.getConfig().getInt("experience.daily-limit", 150);
    }

    public long summonCooldownMillis() {
        return plugin.getConfig().getLong("summon.cooldown-seconds", 7200L) * 1000L;
    }

    public boolean summonSameWorldOnly() {
        return plugin.getConfig().getBoolean("summon.same-world-only", true);
    }

    public boolean inventoryEnabled() {
        return plugin.getConfig().getBoolean("inventory.enabled", true);
    }

    public int inventorySize() {
        int size = plugin.getConfig().getInt("inventory.size", 27);
        size = Math.max(9, Math.min(54, size));
        return size - (size % 9);
    }

    public double inventoryOpenRadius() {
        return Math.max(1.0, plugin.getConfig().getDouble("inventory.open-radius", 10.0));
    }

    public Material resurrectionRequiredBlock() {
        String name = plugin.getConfig().getString("resurrection.required-block", "SOUL_CAMPFIRE");
        Material material = name == null ? null : Material.matchMaterial(name);
        return material == null ? Material.SOUL_CAMPFIRE : material;
    }

    public int resurrectionBlockRadius() {
        int radius = Math.max(0, plugin.getConfig().getInt("resurrection.block-radius", 3));
        int max = Math.max(0, plugin.getConfig().getInt("resurrection.max-block-radius", 8));
        return Math.min(radius, max);
    }

    public Map<Material, Integer> resurrectionItems() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("resurrection.items");
        Map<Material, Integer> items = new LinkedHashMap<>();
        if (section == null) {
            items.put(Material.GHAST_TEAR, 4);
            items.put(Material.GLOWSTONE_DUST, 16);
            items.put(Material.PHANTOM_MEMBRANE, 2);
            return items;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            int amount = section.getInt(key, 0);
            if (material != null && amount > 0) {
                items.put(material, amount);
            }
        }
        return items;
    }

    public double speedBase() {
        return plugin.getConfig().getDouble("speed.base", 0.055);
    }

    public double speedMax() {
        return plugin.getConfig().getDouble("speed.max", 0.12);
    }

    public int feedingExp(Material material) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("feeding.items." + material.name());
        if (section != null) {
            return section.getInt("exp", 0);
        }
        return plugin.getConfig().getInt("feeding.items." + material.name(), 0);
    }

    public int feedingDailyMax(Material material) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("feeding.items." + material.name());
        if (section != null && section.contains("daily-max-items")) {
            return section.getInt("daily-max-items", 0);
        }
        if (!plugin.getConfig().getBoolean("feeding.same-food-daily-limit.enabled", true)) {
            return 0;
        }
        return plugin.getConfig().getInt("feeding.same-food-daily-limit.default-max-items", 8);
    }

    public List<Material> feedingMaterials() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("feeding.items");
        if (section == null) {
            return List.of();
        }
        List<Material> materials = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    public int requiredExp(int level) {
        return plugin.getConfig().getInt("levels." + level + ".required-exp", level * 50);
    }

    public int maxLevel() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("levels");
        if (section == null) {
            return 15;
        }
        return section.getKeys(false).stream()
                .mapToInt(key -> {
                    try {
                        return Integer.parseInt(key);
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                })
                .max()
                .orElse(15);
    }

    public String improvementName(int level) {
        return plugin.getConfig().getString("levels." + level + ".display-name", "уровень " + level);
    }

    public double speedMultiplier(int level) {
        return plugin.getConfig().getDouble("levels." + level + ".speed-multiplier", 1.0);
    }

    public double maxHealth(int level) {
        return plugin.getConfig().getDouble("levels." + level + ".max-health", 8.0 + 2.0 * Math.max(1, level));
    }

    /** Effective movement speed (after the {@link #speedMax()} cap) for the given level. */
    public double effectiveSpeed(int level) {
        return Math.min(speedMax(), speedBase() * speedMultiplier(level));
    }

    /** Honest "% faster than a freshly tamed pet", accounting for the speed cap. */
    public int speedPercent(int level) {
        double base = effectiveSpeed(1);
        if (base <= 0.0) {
            return 0;
        }
        return (int) Math.round((effectiveSpeed(level) / base - 1.0) * 100.0);
    }

    public List<ConfiguredEffect> passengerEffects(int level) {
        List<ConfiguredEffect> effects = new ArrayList<>();
        for (int current = 1; current <= level; current++) {
            List<Map<?, ?>> maps = plugin.getConfig().getMapList("levels." + current + ".passenger-effects");
            for (Map<?, ?> map : maps) {
                Object typeValue = map.get("type");
                if (typeValue == null) {
                    continue;
                }
                PotionEffectType type = PotionEffectType.getByName(typeValue.toString());
                if (type == null) {
                    plugin.getLogger().warning("Unknown potion effect in levels." + current + ".passenger-effects: " + typeValue);
                    continue;
                }
                int durationTicks = intValue(map.get("duration-ticks"), 40);
                int amplifier = intValue(map.get("amplifier"), 0);
                int periodTicks = intValue(map.get("period-ticks"), Math.max(1, durationTicks / 2));
                effects.add(new ConfiguredEffect(type, durationTicks, amplifier, periodTicks));
            }
        }
        return effects;
    }

    /** Readable, deduplicated list of passenger buffs active at the given level (e.g. "Скорость II"). */
    public List<String> passengerBuffSummary(int level) {
        Map<PotionEffectType, Integer> best = new LinkedHashMap<>();
        for (ConfiguredEffect effect : passengerEffects(level)) {
            best.merge(effect.type(), effect.amplifier(), Math::max);
        }
        List<String> out = new ArrayList<>();
        best.forEach((type, amplifier) -> out.add(effectName(type) + " " + roman(amplifier + 1)));
        return out;
    }

    public String defaultPetName() {
        return plugin.getConfig().getString("pet-name.default", "Happy Ghast");
    }

    public int maxPetNameLength() {
        return plugin.getConfig().getInt("pet-name.max-length", 32);
    }

    public String entityNameFormat() {
        return plugin.getConfig().getString("display.entity-name-format", "{name} &7[Ур. {level}/{max_level}]");
    }

    public boolean customNameVisible() {
        return plugin.getConfig().getBoolean("display.custom-name-visible", true);
    }

    public boolean parkAfterDismountDefault() {
        return plugin.getConfig().getBoolean("idle.park-after-dismount", true);
    }

    public String idleMode() {
        return plugin.getConfig().getString("idle.mode", "PARKED").toUpperCase();
    }

    public boolean idleStopVelocity() {
        return plugin.getConfig().getBoolean("idle.stop-velocity-when-idle", true);
    }

    public boolean idleDisableAi() {
        return plugin.getConfig().getBoolean("idle.disable-ai-when-idle", true);
    }

    public double idleMaxDistanceFromPark() {
        return plugin.getConfig().getDouble("idle.max-distance-from-park", 2.0);
    }

    public double idleReturnSpeed() {
        return plugin.getConfig().getDouble("idle.return-speed", 0.15);
    }

    public boolean ambientSoundEnabled() {
        return plugin.getConfig().getBoolean("sounds.ambient.enabled", true)
                && plugin.getConfig().getInt("sounds.ambient.reduction-percent", 80) > 0;
    }

    public long ambientSoundCooldownMillis() {
        return plugin.getConfig().getLong("sounds.ambient.min-cooldown-seconds", 15L) * 1000L;
    }

    public int ambientSoundReductionPercent() {
        return Math.max(0, Math.min(100, plugin.getConfig().getInt("sounds.ambient.reduction-percent", 80)));
    }

    public float ambientSoundVolume() {
        return (float) plugin.getConfig().getDouble("sounds.ambient.volume", 0.7);
    }

    public float ambientSoundPitch() {
        return (float) plugin.getConfig().getDouble("sounds.ambient.pitch", 1.0);
    }

    public World world(String name) {
        return name == null ? null : plugin.getServer().getWorld(name);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String effectName(PotionEffectType type) {
        String key = type.getKey().value();
        return switch (key) {
            case "slow_falling" -> "Медленное падение";
            case "speed" -> "Скорость";
            case "fire_resistance" -> "Огнестойкость";
            case "regeneration" -> "Регенерация";
            case "jump_boost" -> "Прыгучесть";
            case "water_breathing" -> "Подводное дыхание";
            case "night_vision" -> "Ночное зрение";
            case "resistance" -> "Сопротивление";
            case "absorption" -> "Поглощение";
            default -> {
                String pretty = key.replace('_', ' ');
                yield Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1);
            }
        };
    }

    private String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }
}
