package com.nevarielle.happyghastpet;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class GhastPetCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_SUBCOMMANDS = List.of(
            "tame", "menu", "name", "trust", "untrust", "help"
    );
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("give", "setlevel", "remove", "reload");

    private final PetService service;
    private final PetMenu petMenu;
    private final Messages messages;

    public GhastPetCommand(PetService service, PetMenu petMenu) {
        this.service = service;
        this.petMenu = petMenu;
        this.messages = service.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        service.logCommand(sender, label, args);
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!player.hasPermission("happyghastpet.use")) {
                    messages.send(player, "common.no-permission");
                    return true;
                }
                try {
                    petMenu.open(player);
                    service.logAction(player.getName(), "open_menu", "success");
                } catch (SQLException exception) {
                    messages.send(player, "menu.db-error");
                    service.logAction(player.getName(), "open_menu", "sql_error=" + exception.getMessage());
                }
            } else {
                sendUsage(sender, label);
            }
            return true;
        }
        String subcommand = args[0].toLowerCase();
        try {
            if ("admin".equals(subcommand)) {
                handleAdmin(sender, label, args);
                return true;
            }
            if ("help".equals(subcommand)) {
                messages.sendList(sender, "help.lines");
                return true;
            }
            if (!(sender instanceof Player player)) {
                messages.send(sender, "common.player-only");
                return true;
            }
            if (!player.hasPermission("happyghastpet.use")) {
                messages.send(player, "common.no-permission");
                return true;
            }
            handlePlayer(player, label, subcommand, args);
        } catch (PetException exception) {
            messages.send(sender, exception.key(), exception.placeholders());
        } catch (SQLException exception) {
            messages.send(sender, "common.db-error");
            Bukkit.getLogger().warning("[HappyGhastPet] SQL error: " + exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(PLAYER_SUBCOMMANDS);
            if (sender.hasPermission("happyghastpet.admin")) {
                values.add("admin");
            }
            return filter(values, args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0]) && sender.hasPermission("happyghastpet.admin")) {
            return filter(ADMIN_SUBCOMMANDS, args[1]);
        }
        if (args.length == 2 && ("trust".equalsIgnoreCase(args[0]) || "untrust".equalsIgnoreCase(args[0]))) {
            return null;
        }
        if (args.length == 3 && "admin".equalsIgnoreCase(args[0])
                && ("give".equalsIgnoreCase(args[1]) || "setlevel".equalsIgnoreCase(args[1]))) {
            return null;
        }
        return Collections.emptyList();
    }

    private void handlePlayer(Player player, String label, String subcommand, String[] args) throws SQLException {
        switch (subcommand) {
            case "tame" -> tame(player, args);
            case "menu" -> petMenu.open(player);
            case "name" -> rename(player, args);
            case "trust" -> trust(player, args);
            case "untrust" -> untrust(player, args);
            default -> sendUsage(player, label);
        }
    }

    private void tame(Player player, String[] args) throws SQLException {
        HappyGhast ghast = service.targetUntamedHappyGhast(player)
                .orElseThrow(() -> new PetException("tame.look-at-ghast"));
        String name = args.length > 1 ? validateName(player, Arrays.copyOfRange(args, 1, args.length)) : null;
        if (args.length > 1 && name == null) {
            return;
        }
        PetRecord pet = service.tame(player, ghast, name);
        messages.send(player, "tame.success", "name", pet.name(), "id", pet.petId());
        // First-time onboarding: only when this is the player's single (first) pet.
        if (service.petsOwnedBy(player.getUniqueId()).size() == 1) {
            messages.sendList(player, "help.onboarding");
        }
    }

    private void rename(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            messages.send(player, "name.usage");
            return;
        }
        PetRecord pet = targetOwned(player);
        String name = validateName(player, Arrays.copyOfRange(args, 1, args.length));
        if (name == null) {
            return;
        }
        service.rename(pet, name);
        messages.send(player, "name.changed", "name", name);
    }

    private void trust(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            messages.send(player, "trust.usage");
            return;
        }
        PetRecord pet = targetOwned(player);
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null) {
            messages.send(player, "trust.unknown-player", "player", args[1]);
            return;
        }
        String name = target.getName() == null ? args[1] : target.getName();
        service.trust(pet, target.getUniqueId(), name);
        messages.send(player, "trust.added", "player", name);
    }

    private void untrust(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            messages.send(player, "untrust.usage");
            return;
        }
        PetRecord pet = targetOwned(player);
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null) {
            messages.send(player, "trust.unknown-player", "player", args[1]);
            return;
        }
        service.untrust(pet, target.getUniqueId());
        messages.send(player, "untrust.removed", "player", target.getName() == null ? args[1] : target.getName());
    }

    private void handleAdmin(CommandSender sender, String label, String[] args) throws SQLException {
        if (!sender.hasPermission("happyghastpet.admin")) {
            messages.send(sender, "common.no-permission");
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "admin.usage");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "give" -> adminGive(sender, args);
            case "setlevel" -> adminSetLevel(sender, args);
            case "remove" -> adminRemove(sender, args);
            case "reload" -> adminReload(sender);
            default -> messages.send(sender, "admin.usage");
        }
    }

    private void adminGive(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            messages.send(sender, "admin.give-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            messages.send(sender, "admin.give-target-offline");
            return;
        }
        HappyGhast ghast = target.getWorld().spawn(target.getLocation(), HappyGhast.class);
        PetRecord pet = service.tame(target, ghast, null, false);
        service.logAction(sender.getName(), "admin_give", pet, "target=" + target.getName());
        messages.send(sender, "admin.give-done", "player", target.getName(), "id", pet.petId());
    }

    private void adminSetLevel(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 4) {
            messages.send(sender, "admin.setlevel-usage");
            return;
        }
        OfflinePlayer target = resolveOffline(args[2]);
        if (target == null) {
            messages.send(sender, "admin.setlevel-no-pets");
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            messages.send(sender, "admin.setlevel-nan");
            return;
        }
        level = Math.max(1, Math.min(level, service.maxLevel()));
        List<PetRecord> pets = service.petsOwnedBy(target.getUniqueId());
        if (pets.isEmpty()) {
            messages.send(sender, "admin.setlevel-no-pets");
            return;
        }
        PetRecord pet = pets.get(0);
        service.setLevel(pet, level);
        service.logAction(sender.getName(), "admin_setlevel", pet, "target=" + target.getName() + ", level=" + level);
        messages.send(sender, "admin.setlevel-done", "id", pet.petId(), "level", level);
    }

    private void adminRemove(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 3) {
            messages.send(sender, "admin.remove-usage");
            return;
        }
        UUID petId;
        try {
            petId = UUID.fromString(args[2]);
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "admin.remove-bad-id");
            return;
        }
        Optional<PetRecord> pet = service.petById(petId);
        if (pet.isEmpty()) {
            messages.send(sender, "admin.remove-not-found");
            return;
        }
        service.remove(pet.get());
        service.logAction(sender.getName(), "admin_remove", pet.get(), "removed");
        messages.send(sender, "admin.remove-done");
    }

    private void adminReload(CommandSender sender) {
        service.reloadRuntimeConfig();
        service.logAction(sender.getName(), "admin_reload", "config reloaded");
        messages.send(sender, "admin.reload-done");
    }

    private PetRecord targetOwned(Player player) {
        PetRecord pet = service.targetPet(player)
                .orElseThrow(() -> new PetException("common.pet-not-found"));
        if (!pet.ownerUuid().equals(player.getUniqueId())) {
            throw new PetException("common.not-your-pet");
        }
        return pet;
    }

    private void sendUsage(CommandSender sender, String label) {
        messages.send(sender, "usage.player");
        if (sender.hasPermission("happyghastpet.admin")) {
            messages.send(sender, "admin.usage");
        }
    }

    private String validateName(Player player, String[] parts) {
        return service.validatePetName(player, String.join(" ", parts));
    }

    /** Non-blocking offline lookup: online first, then the local profile cache. Never hits Mojang on the main thread. */
    private OfflinePlayer resolveOffline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase();
        return values.stream().filter(value -> value.startsWith(lower)).toList();
    }
}
