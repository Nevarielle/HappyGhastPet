package com.nevarielle.happyghastpet;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PetMenu implements Listener {
    private static final int[] TOP_PET_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private static final int PROGRESS_SEGMENTS = 20;

    private final PetService service;
    private final PetStorageManager storageManager;
    private final Messages messages;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    // player -> pet awaiting a chat-typed name
    private final Map<UUID, UUID> pendingRename = new ConcurrentHashMap<>();

    public PetMenu(PetService service, PetStorageManager storageManager) {
        this.service = service;
        this.storageManager = storageManager;
        this.messages = service.messages();
    }

    public void open(Player player) throws SQLException {
        List<PetRecord> pets = service.petsOwnedBy(player.getUniqueId());
        if (pets.isEmpty()) {
            messages.send(player, "common.pets-none");
            return;
        }
        openPet(player, pets.get(0).petId());
    }

    public void openPet(Player player, UUID selectedPetId) throws SQLException {
        List<PetRecord> pets = service.petsOwnedBy(player.getUniqueId());
        if (pets.isEmpty()) {
            messages.send(player, "common.pets-none");
            return;
        }
        PetRecord selected = pets.stream()
                .filter(pet -> pet.petId().equals(selectedPetId))
                .findFirst()
                .orElse(pets.get(0));

        PetMenuHolder holder = new PetMenuHolder(player.getUniqueId(), selected.petId());
        Inventory inventory = Bukkit.createInventory(holder, 45,
                Component.text("Гаст: ").append(legacy.deserialize(selected.name())));
        holder.inventory(inventory);
        renderPetTabs(inventory, holder, pets, selected.petId());
        renderControls(inventory, holder, selected);
        player.openInventory(inventory);
    }

    private void openConfirm(Player player, PetRecord pet, MenuAction action) {
        ConfirmHolder holder = new ConfirmHolder(player.getUniqueId(), pet.petId(), action);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Подтверждение"));
        holder.inventory(inventory);
        inventory.setItem(13, confirmInfoItem(pet, action));
        inventory.setItem(11, button(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "Отмена",
                List.of(ChatColor.GRAY + "Вернуться без изменений.")));
        inventory.setItem(15, button(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "Подтвердить",
                List.of(ChatColor.GRAY + "Выполнить действие.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getInventory().getHolder();
        if (rawHolder instanceof ConfirmHolder confirmHolder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                handleConfirmClick(player, confirmHolder, event.getRawSlot());
            }
            return;
        }
        if (!(rawHolder instanceof PetMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!holder.ownerUuid().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }

        UUID petId = holder.petAt(event.getRawSlot());
        if (petId != null) {
            tryOpen(player, petId);
            return;
        }

        MenuAction action = holder.actionAt(event.getRawSlot());
        if (action == null) {
            return;
        }
        try {
            Optional<PetRecord> selected = service.petById(holder.selectedPetId());
            if (selected.isEmpty() || !selected.get().ownerUuid().equals(player.getUniqueId())) {
                player.closeInventory();
                messages.send(player, "common.pet-not-found");
                return;
            }
            handleAction(player, selected.get(), action);
        } catch (PetException exception) {
            messages.send(player, exception.key(), exception.placeholders());
            tryOpen(player, holder.selectedPetId());
        } catch (SQLException exception) {
            player.closeInventory();
            messages.send(player, "menu.db-error");
        }
    }

    private void handleAction(Player player, PetRecord pet, MenuAction action) throws SQLException {
        switch (action) {
            case SUMMON -> {
                service.summon(player, pet);
                messages.send(player, "summon.done");
                player.playSound(player.getLocation(), Sound.ENTITY_HAPPY_GHAST_AMBIENT, 0.6f, 1.2f);
                openPet(player, pet.petId());
            }
            case RESURRECT -> openConfirm(player, pet, MenuAction.RESURRECT);
            case UPGRADE -> {
                if (pet.level() >= service.maxLevel()) {
                    messages.send(player, "upgrade.max-level");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                    openPet(player, pet.petId());
                    return;
                }
                int nextLevel = pet.level() + 1;
                int required = service.requiredExp(nextLevel);
                if (pet.exp() < required) {
                    messages.send(player, "upgrade.not-enough-exp", "needed", required - pet.exp(), "level", nextLevel);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                    openPet(player, pet.petId());
                    return;
                }
                openConfirm(player, pet, MenuAction.UPGRADE);
            }
            case TOGGLE_IDLE_BEHAVIOR -> {
                service.toggleParkAfterDismount(pet);
                messages.send(player, pet.parkAfterDismount() ? "behavior.park-on" : "behavior.park-off");
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                openPet(player, pet.petId());
            }
            case STORAGE -> storageManager.open(player, pet);
            case HARNESS -> {
                Optional<Material> result = service.cycleHarness(pet);
                if (result.isEmpty()) {
                    messages.send(player, "harness.summon-first");
                } else {
                    messages.send(player, "harness.changed", "color", harnessColorName(result.get()));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                }
                openPet(player, pet.petId());
            }
            case RENAME -> {
                pendingRename.put(player.getUniqueId(), pet.petId());
                player.closeInventory();
                messages.send(player, "name.prompt");
            }
            case BACK -> open(player);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID petId = pendingRename.remove(event.getPlayer().getUniqueId());
        if (petId == null) {
            return;
        }
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        service.runMainThread(() -> finishRename(player, petId, raw));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingRename.remove(event.getPlayer().getUniqueId());
    }

    private void finishRename(Player player, UUID petId, String raw) {
        if (raw.equalsIgnoreCase("отмена") || raw.equalsIgnoreCase("cancel")) {
            messages.send(player, "name.cancelled");
            tryOpen(player, petId);
            return;
        }
        try {
            Optional<PetRecord> opt = service.petById(petId);
            if (opt.isEmpty() || !opt.get().ownerUuid().equals(player.getUniqueId())) {
                messages.send(player, "common.pet-not-found");
                return;
            }
            String name = service.validatePetName(player, raw);
            if (name == null) {
                tryOpen(player, petId);
                return;
            }
            service.rename(opt.get(), name);
            messages.send(player, "name.changed", "name", name);
            tryOpen(player, petId);
        } catch (SQLException exception) {
            messages.send(player, "menu.db-error");
        }
    }

    private void handleConfirmClick(Player player, ConfirmHolder holder, int rawSlot) {
        if (rawSlot == 11) {
            tryOpen(player, holder.petId());
            return;
        }
        if (rawSlot != 15) {
            return;
        }
        try {
            Optional<PetRecord> opt = service.petById(holder.petId());
            if (opt.isEmpty() || !opt.get().ownerUuid().equals(player.getUniqueId())) {
                player.closeInventory();
                messages.send(player, "common.pet-not-found");
                return;
            }
            PetRecord pet = opt.get();
            switch (holder.action()) {
                case RESURRECT -> {
                    service.resurrect(player, pet);
                    messages.send(player, "resurrect.done");
                }
                case UPGRADE -> performUpgrade(player, pet);
                default -> {
                }
            }
            openPet(player, holder.petId());
        } catch (PetException exception) {
            messages.send(player, exception.key(), exception.placeholders());
            tryOpen(player, holder.petId());
        } catch (SQLException exception) {
            player.closeInventory();
            messages.send(player, "menu.db-error");
        }
    }

    private void performUpgrade(Player player, PetRecord pet) throws SQLException {
        if (!service.upgrade(pet)) {
            messages.send(player, "upgrade.not-enough-exp",
                    "needed", Math.max(0, service.requiredExp(pet.level() + 1) - pet.exp()),
                    "level", pet.level() + 1);
            return;
        }
        messages.send(player, "upgrade.done", "level", pet.level(), "improvement", service.improvementName(pet.level()));
        messages.actionBar(player, "upgrade.actionbar");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.35f);
    }

    private void tryOpen(Player player, UUID petId) {
        try {
            openPet(player, petId);
        } catch (SQLException exception) {
            player.closeInventory();
            messages.send(player, "menu.db-error");
        }
    }

    private void renderPetTabs(Inventory inventory, PetMenuHolder holder, List<PetRecord> pets, UUID selectedPetId) throws SQLException {
        for (int i = 0; i < pets.size() && i < TOP_PET_SLOTS.length; i++) {
            PetRecord pet = pets.get(i);
            service.resetDailyCapIfNeeded(pet);
            int slot = TOP_PET_SLOTS[i];
            holder.petAt(slot, pet.petId());
            inventory.setItem(slot, petItem(pet, pet.petId().equals(selectedPetId)));
        }
    }

    private void renderControls(Inventory inventory, PetMenuHolder holder, PetRecord pet) throws SQLException {
        inventory.setItem(13, petDetailsItem(pet));
        if (pet.dismissed()) {
            setAction(inventory, holder, 20, MenuAction.RESURRECT, resurrectItem());
        } else {
            setAction(inventory, holder, 20, MenuAction.SUMMON, summonItem(pet));
        }
        setAction(inventory, holder, 22, MenuAction.UPGRADE, upgradeItem(pet));
        setAction(inventory, holder, 24, MenuAction.TOGGLE_IDLE_BEHAVIOR, behaviorItem(pet));
        if (service.storageEnabled()) {
            setAction(inventory, holder, 29, MenuAction.STORAGE, storageItem());
        }
        inventory.setItem(31, foodLimitsItem(pet));
        inventory.setItem(33, trustedItem(pet));
        if (service.harnessRecolorEnabled()) {
            setAction(inventory, holder, 42, MenuAction.HARNESS, harnessItem(pet));
        }
        setAction(inventory, holder, 38, MenuAction.RENAME, renameItem());
        setAction(inventory, holder, 40, MenuAction.BACK, button(Material.ARROW, ChatColor.WHITE + "Назад", List.of(
                ChatColor.GRAY + "Вернуться к списку питомцев."
        )));
    }

    private void setAction(Inventory inventory, PetMenuHolder holder, int slot, MenuAction action, ItemStack item) {
        holder.actionAt(slot, action);
        inventory.setItem(slot, item);
    }

    private ItemStack petItem(PetRecord pet, boolean selected) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.YELLOW + "Уровень: " + pet.level() + "/" + service.maxLevel());
        lore.add(ChatColor.YELLOW + "Скорость: " + ChatColor.AQUA + "+" + service.speedPercent(pet.level()) + "%");
        lore.add(ChatColor.YELLOW + "Статус: " + statusLabel(pet));
        if (pet.level() < service.maxLevel()) {
            lore.add(progressLine(pet.exp(), service.requiredExp(pet.level() + 1)));
        } else {
            lore.add(ChatColor.GOLD + "Максимальный уровень");
        }
        lore.add(selected ? ChatColor.GREEN + "Выбран" : ChatColor.WHITE + "Клик: выбрать");
        String coloredName = ChatColor.translateAlternateColorCodes('&', pet.name());
        return button(selected ? Material.NETHER_STAR : Material.GHAST_TEAR,
                (selected ? ChatColor.GREEN : ChatColor.AQUA) + coloredName, lore);
    }

    private ItemStack petDetailsItem(PetRecord pet) throws SQLException {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "ID: " + pet.petId());
        lore.add(ChatColor.GRAY + "Владелец: " + pet.ownerName());
        lore.add("");
        lore.add(ChatColor.YELLOW + "Уровень: " + ChatColor.WHITE + pet.level() + "/" + service.maxLevel());
        lore.add(ChatColor.YELLOW + "Здоровье: " + ChatColor.WHITE + formatHealth(pet.level()));
        lore.add(ChatColor.YELLOW + "Скорость: " + ChatColor.AQUA + "+" + service.speedPercent(pet.level()) + "%"
                + ChatColor.GRAY + " к базовой");
        List<String> buffs = service.passengerBuffSummary(pet.level());
        lore.add(ChatColor.YELLOW + "Бонусы пассажирам: " + (buffs.isEmpty()
                ? ChatColor.GRAY + "нет"
                : ChatColor.AQUA + String.join(ChatColor.GRAY + ", " + ChatColor.AQUA, buffs)));
        lore.add(ChatColor.YELLOW + "Статус: " + statusLabel(pet));
        lore.add(ChatColor.YELLOW + "После схода: " + behaviorLabel(pet));
        long cooldown = service.summonCooldownRemainingMillis(pet);
        lore.add(ChatColor.YELLOW + "Призыв: " + (pet.dismissed()
                ? ChatColor.RED + "нужно воскрешение"
                : cooldown <= 0 ? ChatColor.GREEN + "доступен" : ChatColor.RED + "через " + service.formatDuration(cooldown)));
        lore.add(ChatColor.YELLOW + "Где: " + ChatColor.WHITE + pet.world() + " "
                + Math.round(pet.x()) + " " + Math.round(pet.y()) + " " + Math.round(pet.z()));
        lore.add("");
        if (pet.level() < service.maxLevel()) {
            int nextLevel = pet.level() + 1;
            int required = service.requiredExp(nextLevel);
            lore.add(ChatColor.GOLD + "Опыт: " + ChatColor.WHITE + pet.exp() + "/" + required);
            lore.add(progressLine(pet.exp(), required));
            lore.add(ChatColor.DARK_GRAY + "Дальше (ур. " + nextLevel + "): " + ChatColor.GRAY + service.improvementName(nextLevel));
            for (String unlocked : newBuffsAt(pet.level(), nextLevel)) {
                lore.add(ChatColor.DARK_AQUA + "+ " + unlocked);
            }
        } else {
            lore.add(ChatColor.GOLD + "Опыт: " + ChatColor.WHITE + pet.exp());
            lore.add(ChatColor.GOLD + "Питомец полностью прокачан.");
        }
        return button(Material.SADDLE, ChatColor.AQUA + "Питомец", lore);
    }

    private ItemStack foodLimitsItem(PetRecord pet) throws SQLException {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.YELLOW + "Опыт за день: " + pet.dailyExp() + "/" + service.dailyLimit());
        List<Material> materials = service.feedingMaterials();
        if (materials.isEmpty()) {
            lore.add(ChatColor.GRAY + "Еда не настроена.");
        } else {
            lore.add("");
            lore.add(ChatColor.GOLD + "Еда сегодня:");
            for (Material material : materials) {
                int max = service.feedingDailyMax(material);
                int used = max > 0 ? service.foodUsageToday(pet, material) : 0;
                String limit = max > 0 ? used + "/" + max : "без лимита";
                lore.add(ChatColor.GRAY + "- " + prettyMaterial(material) + ": "
                        + ChatColor.AQUA + service.feedingExp(material) + " exp" + ChatColor.GRAY + ", " + limit);
            }
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "ПКМ по гасту едой — покормить.");
        return button(Material.HONEY_BOTTLE, ChatColor.GOLD + "Кормление", lore);
    }

    private ItemStack storageItem() {
        return button(Material.CHEST, ChatColor.GOLD + "Сундук питомца", List.of(
                ChatColor.GRAY + "Личное хранилище гаста (" + service.storageSize() + " слотов).",
                ChatColor.GRAY + "Также: Shift+ПКМ по гасту.",
                ChatColor.GREEN + "Клик: открыть."
        ));
    }

    private ItemStack harnessItem(PetRecord pet) {
        Material current = service.currentHarness(pet).orElse(null);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Сменить цвет упряжи гаста (косметика).");
        if (current == null) {
            lore.add(ChatColor.GRAY + "Текущий: " + ChatColor.WHITE + "питомец не призван");
        } else if (current.isAir()) {
            lore.add(ChatColor.GRAY + "Текущий: " + ChatColor.WHITE + "без упряжи");
        } else {
            lore.add(ChatColor.GRAY + "Текущий: " + ChatColor.WHITE + harnessColorName(current));
        }
        lore.add(ChatColor.GREEN + "Клик: следующий цвет");
        Material icon = (current != null && !current.isAir()) ? current : Material.WHITE_HARNESS;
        return button(icon, ChatColor.GOLD + "Цвет упряжи", lore);
    }

    private String harnessColorName(Material harness) {
        return switch (harness) {
            case WHITE_HARNESS -> "белая";
            case LIGHT_GRAY_HARNESS -> "светло-серая";
            case GRAY_HARNESS -> "серая";
            case BLACK_HARNESS -> "чёрная";
            case BROWN_HARNESS -> "коричневая";
            case RED_HARNESS -> "красная";
            case ORANGE_HARNESS -> "оранжевая";
            case YELLOW_HARNESS -> "жёлтая";
            case LIME_HARNESS -> "лаймовая";
            case GREEN_HARNESS -> "зелёная";
            case CYAN_HARNESS -> "бирюзовая";
            case LIGHT_BLUE_HARNESS -> "голубая";
            case BLUE_HARNESS -> "синяя";
            case PURPLE_HARNESS -> "фиолетовая";
            case MAGENTA_HARNESS -> "пурпурная";
            case PINK_HARNESS -> "розовая";
            default -> harness.name();
        };
    }

    private ItemStack behaviorItem(PetRecord pet) {
        if (pet.parkAfterDismount()) {
            return button(Material.LEAD, ChatColor.GREEN + "Поведение: зависать", List.of(
                    ChatColor.GRAY + "После схода питомец остается на месте.",
                    ChatColor.GREEN + "Клик: вернуть обычное поведение."
            ));
        }
        return button(Material.FEATHER, ChatColor.YELLOW + "Поведение: обычное", List.of(
                ChatColor.GRAY + "После схода питомец ведет себя как обычный гаст.",
                ChatColor.GREEN + "Клик: включить зависание на месте."
        ));
    }

    private String behaviorLabel(PetRecord pet) {
        return pet.parkAfterDismount() ? ChatColor.WHITE + "зависает на месте" : ChatColor.WHITE + "обычное";
    }

    private String statusLabel(PetRecord pet) {
        if (pet.dismissed()) {
            return ChatColor.RED + "погиб";
        }
        return pet.parked() ? ChatColor.GRAY + "припаркован" : ChatColor.GREEN + "активен";
    }

    private ItemStack renameItem() {
        return button(Material.NAME_TAG, ChatColor.YELLOW + "Переименовать", List.of(
                ChatColor.GRAY + "Сменить имя питомца.",
                ChatColor.GREEN + "Клик: ввести имя в чат.",
                ChatColor.DARK_GRAY + "Также: /gh name <имя>"
        ));
    }

    private ItemStack trustedItem(PetRecord pet) throws SQLException {
        List<String> trusted = service.trustedNames(pet);
        List<String> lore = new ArrayList<>();
        if (trusted.isEmpty()) {
            lore.add(ChatColor.GRAY + "Никому не выдан доступ.");
        } else {
            lore.add(ChatColor.GOLD + "Могут ездить:");
            for (String name : trusted) {
                lore.add(ChatColor.GRAY + "- " + name);
            }
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "Команды:");
        lore.add(ChatColor.WHITE + "/gh trust <игрок>");
        lore.add(ChatColor.WHITE + "/gh untrust <игрок>");
        return button(Material.PLAYER_HEAD, ChatColor.GREEN + "Доверенные", lore);
    }

    private ItemStack summonItem(PetRecord pet) {
        if (pet.dismissed()) {
            return button(Material.BONE, ChatColor.DARK_RED + "Нужно воскрешение", List.of(
                    ChatColor.GRAY + "Обычный призыв недоступен после смерти."
            ));
        }
        long cooldown = service.summonCooldownRemainingMillis(pet);
        if (cooldown > 0) {
            return button(Material.GRAY_DYE, ChatColor.DARK_GRAY + "Призвать", List.of(
                    ChatColor.RED + "Кулдаун: " + service.formatDuration(cooldown),
                    ChatColor.GRAY + "Только в том же мире, где питомец."
            ));
        }
        return button(Material.ENDER_PEARL, ChatColor.GREEN + "Призвать", List.of(
                ChatColor.GRAY + "Телепортировать питомца к себе.",
                ChatColor.GRAY + "Работает только в одном мире с питомцем."
        ));
    }

    private ItemStack resurrectItem() {
        return button(Material.GHAST_TEAR, ChatColor.LIGHT_PURPLE + "Воскресить", List.of(
                ChatColor.GRAY + "Ритуал:",
                ChatColor.WHITE + "встань рядом с " + service.resurrectionRequiredBlock().name(),
                ChatColor.WHITE + "радиус: " + service.resurrectionBlockRadius() + " блока",
                ChatColor.WHITE + "предметы: " + service.resurrectionCostText(),
                ChatColor.GREEN + "Клик: подтверждение ритуала"
        ));
    }

    private ItemStack upgradeItem(PetRecord pet) {
        if (pet.level() >= service.maxLevel()) {
            return button(Material.DIAMOND, ChatColor.GREEN + "Максимальный уровень", List.of(
                    ChatColor.GRAY + "Питомец уже полностью прокачан."
            ));
        }
        int nextLevel = pet.level() + 1;
        int required = service.requiredExp(nextLevel);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.YELLOW + "Следующий уровень: " + ChatColor.WHITE + nextLevel);
        lore.add(ChatColor.YELLOW + "Нужно опыта: " + ChatColor.WHITE + required);
        lore.add(progressLine(pet.exp(), required));
        lore.add(ChatColor.GRAY + service.improvementName(nextLevel));
        for (String unlocked : newBuffsAt(pet.level(), nextLevel)) {
            lore.add(ChatColor.DARK_AQUA + "+ " + unlocked);
        }
        if (pet.exp() >= required) {
            lore.add("");
            lore.add(ChatColor.GREEN + "Клик: улучшить (с подтверждением)");
            return button(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Улучшить", lore);
        }
        lore.add("");
        lore.add(ChatColor.RED + "Не хватает " + (required - pet.exp()) + " опыта");
        return button(Material.GLASS_BOTTLE, ChatColor.RED + "Улучшить", lore);
    }

    private ItemStack confirmInfoItem(PetRecord pet, MenuAction action) {
        if (action == MenuAction.RESURRECT) {
            return button(Material.GHAST_TEAR, ChatColor.LIGHT_PURPLE + "Воскресить питомца?", List.of(
                    ChatColor.GRAY + "Питомец: " + ChatColor.WHITE + pet.name(),
                    ChatColor.GRAY + "Будет потрачено:",
                    ChatColor.WHITE + service.resurrectionCostText(),
                    ChatColor.GRAY + "Нужен " + service.resurrectionRequiredBlock().name()
                            + " рядом (радиус " + service.resurrectionBlockRadius() + ")."
            ));
        }
        int nextLevel = pet.level() + 1;
        int required = service.requiredExp(nextLevel);
        return button(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Улучшить питомца?", List.of(
                ChatColor.GRAY + "Питомец: " + ChatColor.WHITE + pet.name(),
                ChatColor.GRAY + "Уровень: " + ChatColor.WHITE + pet.level() + " -> " + nextLevel,
                ChatColor.GRAY + "Будет списано опыта: " + ChatColor.WHITE + required,
                ChatColor.GRAY + "Откроется: " + ChatColor.WHITE + service.improvementName(nextLevel)
        ));
    }

    private List<String> newBuffsAt(int currentLevel, int nextLevel) {
        List<String> current = service.passengerBuffSummary(currentLevel);
        List<String> next = service.passengerBuffSummary(nextLevel);
        List<String> result = new ArrayList<>();
        for (String buff : next) {
            if (!current.contains(buff)) {
                result.add(buff);
            }
        }
        return result;
    }

    private String progressLine(int current, int required) {
        double ratio = required <= 0 ? 1.0 : Math.min(1.0, (double) current / required);
        int filled = (int) Math.round(PROGRESS_SEGMENTS * ratio);
        StringBuilder bar = new StringBuilder();
        bar.append(ChatColor.GREEN);
        for (int i = 0; i < filled; i++) {
            bar.append('▮');
        }
        bar.append(ChatColor.DARK_GRAY);
        for (int i = filled; i < PROGRESS_SEGMENTS; i++) {
            bar.append('▮');
        }
        bar.append(' ').append(ChatColor.YELLOW).append((int) Math.round(ratio * 100)).append('%');
        return bar.toString();
    }

    private String formatHealth(int level) {
        double health = service.maxHealth(level);
        int hearts = (int) Math.round(health / 2.0);
        return (int) health + " (" + hearts + " ❤)";
    }

    private String prettyMaterial(Material material) {
        String name = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private enum MenuAction {
        SUMMON,
        RESURRECT,
        UPGRADE,
        TOGGLE_IDLE_BEHAVIOR,
        STORAGE,
        HARNESS,
        RENAME,
        BACK
    }

    private static final class PetMenuHolder implements InventoryHolder {
        private final UUID ownerUuid;
        private final UUID selectedPetId;
        private final Map<Integer, UUID> petsBySlot = new HashMap<>();
        private final Map<Integer, MenuAction> actionsBySlot = new HashMap<>();
        private Inventory inventory;

        private PetMenuHolder(UUID ownerUuid, UUID selectedPetId) {
            this.ownerUuid = ownerUuid;
            this.selectedPetId = selectedPetId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private UUID ownerUuid() {
            return ownerUuid;
        }

        private UUID selectedPetId() {
            return selectedPetId;
        }

        private void petAt(int slot, UUID petId) {
            petsBySlot.put(slot, petId);
        }

        private UUID petAt(int slot) {
            return petsBySlot.get(slot);
        }

        private void actionAt(int slot, MenuAction action) {
            actionsBySlot.put(slot, action);
        }

        private MenuAction actionAt(int slot) {
            return actionsBySlot.get(slot);
        }
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private final UUID ownerUuid;
        private final UUID petId;
        private final MenuAction action;
        private Inventory inventory;

        private ConfirmHolder(UUID ownerUuid, UUID petId, MenuAction action) {
            this.ownerUuid = ownerUuid;
            this.petId = petId;
            this.action = action;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private UUID petId() {
            return petId;
        }

        private MenuAction action() {
            return action;
        }
    }
}
