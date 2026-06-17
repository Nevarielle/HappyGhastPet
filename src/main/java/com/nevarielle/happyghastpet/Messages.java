package com.nevarielle.happyghastpet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads and renders all player-facing text from messages.yml.
 * Color codes use '&'. Placeholders use {key}; {prefix} is always available.
 */
public final class Messages {
    private final JavaPlugin plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private FileConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.isFile()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    /** Raw string with placeholders applied, '&' color codes intact. */
    public String raw(String path, Object... placeholders) {
        String value = config.getString(path, path);
        value = value.replace("{prefix}", config.getString("prefix", ""));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return value;
    }

    /** Adventure component, ready to send. */
    public Component component(String path, Object... placeholders) {
        return legacy.deserialize(raw(path, placeholders));
    }

    public void send(CommandSender target, String path, Object... placeholders) {
        target.sendMessage(component(path, placeholders));
    }

    /** Send each line of a string-list message (e.g. help / onboarding). Silently does nothing if the list is empty. */
    public void sendList(CommandSender target, String path, Object... placeholders) {
        for (String line : config.getStringList(path)) {
            String value = line.replace("{prefix}", config.getString("prefix", ""));
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                value = value.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
            }
            target.sendMessage(legacy.deserialize(value));
        }
    }

    public void actionBar(Player player, String path, Object... placeholders) {
        player.sendActionBar(component(path, placeholders));
    }

    /** Legacy '§' string, for APIs that still require a String (e.g. inventory titles). */
    public String legacyString(String path, Object... placeholders) {
        return legacy.serialize(component(path, placeholders));
    }
}
