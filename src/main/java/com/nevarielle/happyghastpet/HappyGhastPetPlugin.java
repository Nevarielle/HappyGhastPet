package com.nevarielle.happyghastpet;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;

public final class HappyGhastPetPlugin extends JavaPlugin {
    private PetRepository repository;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!isCompatiblePaperVersion()) {
            getLogger().severe("HappyGhastPet requires Paper 1.21.6 or newer: org.bukkit.entity.HappyGhast is missing.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ConfigValidator.validate(this);
        Messages messages = new Messages(this);

        File databaseFile = new File(getDataFolder(), "pets.db");
        backupDatabaseBeforeMigration(databaseFile);
        repository = new PetRepository(databaseFile);
        try {
            repository.open();
        } catch (SQLException exception) {
            getLogger().severe("Failed to open SQLite database: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PetService petService = new PetService(this, repository, messages);
        PetStorageManager storageManager = new PetStorageManager(petService);
        PetMenu petMenu = new PetMenu(petService, storageManager);
        GhastPetCommand command = new GhastPetCommand(petService, petMenu);
        PluginCommand pluginCommand = getCommand("ghastpet");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new GhastPetListener(petService, storageManager), this);
        getServer().getPluginManager().registerEvents(petMenu, this);
        getServer().getPluginManager().registerEvents(storageManager, this);

        int buffPeriod = Math.max(1, getConfig().getInt("tick-period-ticks", 40));
        getServer().getScheduler().runTaskTimer(this, petService::tickRidingBuffs, buffPeriod, buffPeriod);

        startMetrics();
    }

    @Override
    public void onDisable() {
        if (repository != null) {
            repository.close();
        }
    }

    private boolean isCompatiblePaperVersion() {
        try {
            Class.forName("org.bukkit.entity.HappyGhast");
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private void startMetrics() {
        int pluginId = getConfig().getInt("metrics.bstats-plugin-id", 0);
        if (pluginId <= 0) {
            getLogger().info("bStats metrics are disabled. Set metrics.bstats-plugin-id after registering the plugin on bStats.");
            return;
        }
        if (startMetrics("com.nevarielle.happyghastpet.libs.bstats.bukkit.Metrics", pluginId)) {
            return;
        }
        if (!startMetrics("org.bstats.bukkit.Metrics", pluginId)) {
            getLogger().warning("bStats metrics could not be started because the Metrics class was not found.");
        }
    }

    private boolean startMetrics(String className, int pluginId) {
        try {
            Class<?> metricsClass = Class.forName(className);
            metricsClass.getConstructor(JavaPlugin.class, int.class).newInstance(this, pluginId);
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private void backupDatabaseBeforeMigration(File databaseFile) {
        if (!databaseFile.isFile()) {
            return;
        }
        File backupFile = new File(databaseFile.getParentFile(), "pets.db.before-v2.bak");
        if (backupFile.exists()) {
            return;
        }
        try {
            databaseFile.getParentFile().mkdirs();
            Files.copy(databaseFile.toPath(), backupFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            getLogger().info("Created SQLite backup before schema migration: " + backupFile.getName());
        } catch (IOException exception) {
            getLogger().warning("Could not create SQLite backup before migration: " + exception.getMessage());
        }
    }
}
