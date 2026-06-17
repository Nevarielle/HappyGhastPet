package com.nevarielle.happyghastpet;

import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.sql.SQLException;
import java.util.Optional;

public final class GhastPetListener implements Listener {
    private final PetService service;
    private final PetStorageManager storageManager;
    private final Messages messages;

    public GhastPetListener(PetService service, PetStorageManager storageManager) {
        this.service = service;
        this.storageManager = storageManager;
        this.messages = service.messages();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMount(EntityMountEvent event) {
        if (!(event.getMount() instanceof HappyGhast ghast) || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Optional<PetRecord> pet = service.petFromEntity(ghast);
        if (pet.isEmpty()) {
            return;
        }
        if (!service.canRide(pet.get(), player)) {
            event.setCancelled(true);
            messages.send(player, "mount.denied-foreign");
            service.logAction(player.getName(), "mount_denied", pet.get(), "no access");
            return;
        }
        try {
            service.unpark(pet.get(), ghast);
        } catch (SQLException exception) {
            messages.send(player, "mount.db-error");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getDismounted() instanceof HappyGhast ghast) || !(event.getEntity() instanceof Player)) {
            return;
        }
        Optional<PetRecord> pet = service.petFromEntity(ghast);
        if (pet.isEmpty()) {
            return;
        }
        try {
            service.park(pet.get(), ghast);
        } catch (SQLException exception) {
            ghast.getServer().getLogger().warning("[HappyGhastPet] Failed to park pet: " + exception.getMessage());
        }
    }

    /**
     * Interaction model:
     *   Shift + right-click -> open the pet's chest inventory (owner only)
     *   right-click with food -> feed
     *   right-click otherwise -> vanilla (mount)
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof HappyGhast ghast)) {
            return;
        }
        Player player = event.getPlayer();
        Optional<PetRecord> pet = service.petFromEntity(ghast);

        if (player.isSneaking()) {
            if (pet.isPresent() && pet.get().ownerUuid().equals(player.getUniqueId())) {
                event.setCancelled(true);
                storageManager.open(player, pet.get());
            }
            return;
        }

        ItemStack hand = player.getInventory().getItem(EquipmentSlot.HAND);
        try {
            if (service.feed(player, ghast, hand)) {
                event.setCancelled(true);
            }
        } catch (SQLException exception) {
            event.setCancelled(true);
            messages.send(player, "feed.db-error");
        }
        // Not food / not owner -> event left uncancelled so vanilla mounting still works.
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof HappyGhast ghast)) {
            return;
        }
        service.petFromEntity(ghast).ifPresent(pet -> {
            storageManager.onPetDeath(pet, ghast.getLocation());
            try {
                service.markDead(pet);
            } catch (SQLException exception) {
                event.getEntity().getServer().getLogger().warning("[HappyGhastPet] Failed to save dead pet: " + exception.getMessage());
            }
        });
    }
}
