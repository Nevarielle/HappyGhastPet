package com.nevarielle.happyghastpet;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the per-pet chest inventory: opening (with a single-viewer guard to prevent
 * item duplication) and saving on close.
 */
public final class PetStorageManager implements Listener {
    private final PetService service;
    private final Messages messages;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, UUID> openByPet = new ConcurrentHashMap<>();

    public PetStorageManager(PetService service) {
        this.service = service;
        this.messages = service.messages();
    }

    public void open(Player player, PetRecord pet) {
        if (!service.storageEnabled()) {
            messages.send(player, "storage.disabled");
            return;
        }
        if (!pet.ownerUuid().equals(player.getUniqueId())) {
            messages.send(player, "common.not-your-pet");
            return;
        }
        if (pet.dismissed()) {
            messages.send(player, "storage.dead");
            return;
        }
        Entity entity = service.findEntity(pet).orElse(null);
        double radius = service.storageOpenRadius();
        if (entity == null || !entity.getWorld().equals(player.getWorld())
                || entity.getLocation().distanceSquared(player.getLocation()) > radius * radius) {
            messages.send(player, "storage.too-far", "radius", (int) Math.round(radius));
            return;
        }
        UUID existing = openByPet.get(pet.petId());
        if (existing != null && !existing.equals(player.getUniqueId())) {
            messages.send(player, "storage.busy");
            return;
        }
        try {
            PetStorageHolder holder = new PetStorageHolder(pet.petId());
            Inventory inventory = Bukkit.createInventory(holder, service.storageSize(),
                    legacy.deserialize(messages.raw("storage.title", "name", pet.name())));
            holder.inventory(inventory);
            inventory.setContents(service.loadStorageItems(pet));
            openByPet.put(pet.petId(), player.getUniqueId());
            player.openInventory(inventory);
        } catch (SQLException exception) {
            messages.send(player, "common.db-error");
            service.logAction(player.getName(), "storage_open", "sql_error=" + exception.getMessage());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PetStorageHolder holder)) {
            return;
        }
        openByPet.remove(holder.petId());
        try {
            Optional<PetRecord> pet = service.petById(holder.petId());
            // A dead pet's items were already dropped on death; never re-save (would duplicate them).
            if (pet.isPresent() && !pet.get().dismissed()) {
                service.saveStorageItems(pet.get(), event.getInventory().getContents());
            }
        } catch (SQLException exception) {
            service.logAction(event.getPlayer().getName(), "storage_save", "sql_error=" + exception.getMessage());
        }
    }

    /**
     * On pet death: drop every stored item at the death location and clear storage.
     * Handles the case where the owner currently has the chest open (drops the live contents).
     */
    public void onPetDeath(PetRecord pet, Location location) {
        ItemStack[] toDrop;
        Inventory openInventory = null;
        UUID viewerId = openByPet.remove(pet.petId());
        if (viewerId != null) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null
                    && viewer.getOpenInventory().getTopInventory().getHolder() instanceof PetStorageHolder holder
                    && holder.petId().equals(pet.petId())) {
                openInventory = viewer.getOpenInventory().getTopInventory();
            }
        }
        if (openInventory != null) {
            toDrop = openInventory.getContents();
        } else {
            try {
                toDrop = service.loadStorageItems(pet);
            } catch (SQLException exception) {
                service.logAction("server", "storage_death_load", "sql_error=" + exception.getMessage());
                toDrop = new ItemStack[0];
            }
        }
        World world = location.getWorld();
        if (world != null) {
            for (ItemStack item : toDrop) {
                if (item != null && !item.getType().isAir()) {
                    world.dropItemNaturally(location, item.clone());
                }
            }
        }
        if (openInventory != null) {
            openInventory.clear();
        }
        try {
            service.clearStorage(pet);
        } catch (SQLException exception) {
            service.logAction("server", "storage_clear", "sql_error=" + exception.getMessage());
        }
    }

    private static final class PetStorageHolder implements InventoryHolder {
        private final UUID petId;
        private Inventory inventory;

        private PetStorageHolder(UUID petId) {
            this.petId = petId;
        }

        private UUID petId() {
            return petId;
        }

        private void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
