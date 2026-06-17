package com.nevarielle.happyghastpet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PetService {
    // Harness colours in a pleasant cycle order (white -> greys -> warm -> cool).
    private static final Material[] HARNESS_COLORS = {
            Material.WHITE_HARNESS, Material.LIGHT_GRAY_HARNESS, Material.GRAY_HARNESS, Material.BLACK_HARNESS,
            Material.BROWN_HARNESS, Material.RED_HARNESS, Material.ORANGE_HARNESS, Material.YELLOW_HARNESS,
            Material.LIME_HARNESS, Material.GREEN_HARNESS, Material.CYAN_HARNESS, Material.LIGHT_BLUE_HARNESS,
            Material.BLUE_HARNESS, Material.PURPLE_HARNESS, Material.MAGENTA_HARNESS, Material.PINK_HARNESS
    };

    private final HappyGhastPetPlugin plugin;
    private final PetRepository repository;
    private final PetConfig config;
    private final Messages messages;
    private final PetBuffController buffController;
    private final PetIdleController idleController;
    private final PetSoundController soundController;
    private final PetDisplayService displayService;
    private final NamespacedKey petIdKey;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Long> lastLocationSave = new ConcurrentHashMap<>();
    // Authoritative single-instance cache keyed by pet id (this is the only writer of the DB).
    private final Map<UUID, PetRecord> petCache = new ConcurrentHashMap<>();

    public PetService(HappyGhastPetPlugin plugin, PetRepository repository, Messages messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.messages = messages;
        this.config = new PetConfig(plugin);
        this.buffController = new PetBuffController(config);
        this.idleController = new PetIdleController(config);
        this.soundController = new PetSoundController(config);
        this.displayService = new PetDisplayService(config, soundController);
        this.petIdKey = new NamespacedKey(plugin, "pet_id");
        this.ownerKey = new NamespacedKey(plugin, "owner_uuid");
    }

    public Messages messages() {
        return messages;
    }

    public PetRecord tame(Player owner, HappyGhast ghast) throws SQLException {
        return tame(owner, ghast, null, true);
    }

    public PetRecord tame(Player owner, HappyGhast ghast, String name) throws SQLException {
        return tame(owner, ghast, name, true);
    }

    public PetRecord tame(Player owner, HappyGhast ghast, String name, boolean enforceLimit) throws SQLException {
        if (readPetId(ghast).isPresent()) {
            throw new PetException("tame.already-pet");
        }
        int maxPets = config.maxPetsPerPlayer();
        if (enforceLimit && repository.countByOwner(owner.getUniqueId()) >= maxPets) {
            throw new PetException("tame.limit-reached", "max", maxPets);
        }

        UUID petId = UUID.randomUUID();
        PetRecord pet = new PetRecord(
                petId,
                owner.getUniqueId(),
                owner.getName(),
                name == null || name.isBlank() ? defaultPetName() : name,
                1,
                0,
                0,
                LocalDate.now(),
                ghast.getUniqueId(),
                ghast.getWorld().getName(),
                ghast.getLocation().getX(),
                ghast.getLocation().getY(),
                ghast.getLocation().getZ(),
                false,
                0L,
                false,
                config.parkAfterDismountDefault(),
                null,
                0.0,
                0.0,
                0.0
        );
        markEntity(ghast, pet);
        applyPetStats(ghast, pet);
        repository.save(pet);
        petCache.put(petId, pet);
        logAction(owner.getName(), "tame", pet, "created at " + locationSummary(ghast.getLocation()));
        return pet;
    }

    public HappyGhast summon(Player player, PetRecord pet) throws SQLException {
        if (pet.dismissed()) {
            throw new PetException("summon.dead", "block", config.resurrectionRequiredBlock().name());
        }
        if (config.summonSameWorldOnly()) {
            if (pet.world() == null || Bukkit.getWorld(pet.world()) == null) {
                throw new PetException("summon.world-missing");
            }
            if (!player.getWorld().getName().equals(pet.world())) {
                throw new PetException("summon.other-world", "world", pet.world());
            }
        }
        long remainingMillis = summonCooldownRemainingMillis(pet);
        if (remainingMillis > 0) {
            throw new PetException("summon.cooldown", "time", formatDuration(remainingMillis));
        }
        Entity active = findOrLoadEntity(pet).orElse(null);
        Location location = spawnLocationFor(player);
        HappyGhast ghast;
        if (active instanceof HappyGhast existing && existing.isValid()) {
            existing.teleport(location);
            ghast = existing;
        } else {
            ghast = location.getWorld().spawn(location, HappyGhast.class);
        }
        ghast.setFireTicks(0);
        despawnDuplicates(pet, ghast.getUniqueId());
        pet.entityUuid(ghast.getUniqueId());
        pet.position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        pet.dismissed(false);
        pet.parked(false);
        pet.lastSummonAt(System.currentTimeMillis());
        markEntity(ghast, pet);
        applyPetStats(ghast, pet);
        repository.save(pet);
        logAction(player.getName(), "summon", pet, "entity=" + ghast.getUniqueId());
        return ghast;
    }

    public HappyGhast resurrect(Player player, PetRecord pet) throws SQLException {
        if (!pet.ownerUuid().equals(player.getUniqueId())) {
            throw new PetException("resurrect.not-owner");
        }
        if (!pet.dismissed()) {
            throw new PetException("resurrect.not-dead");
        }
        if (!isNearResurrectionBlock(player.getLocation())) {
            throw new PetException("resurrect.need-block",
                    "block", config.resurrectionRequiredBlock().name(),
                    "radius", config.resurrectionBlockRadius());
        }
        Map<Material, Integer> requiredItems = config.resurrectionItems();
        if (!hasItems(player.getInventory(), requiredItems)) {
            throw new PetException("resurrect.need-items", "items", resurrectionCostText());
        }
        takeItems(player.getInventory(), requiredItems);

        // Spawn the ghast hovering above the player so it does not land in the soul campfire
        // used for the ritual; clear fire and grant brief fire resistance just in case.
        Location location = spawnLocationFor(player);
        HappyGhast ghast = location.getWorld().spawn(location, HappyGhast.class);
        ghast.setFireTicks(0);
        ghast.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 200, 0, true, false, false));
        despawnDuplicates(pet, ghast.getUniqueId());
        pet.entityUuid(ghast.getUniqueId());
        pet.position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        pet.dismissed(false);
        pet.parked(false);
        pet.lastSummonAt(System.currentTimeMillis());
        markEntity(ghast, pet);
        applyPetStats(ghast, pet);
        repository.save(pet);
        playResurrectionEffects(player, ghast);
        Bukkit.broadcast(messages.component("resurrect.broadcast", "player", player.getName(), "name", pet.name()));
        logAction(player.getName(), "resurrect", pet, "entity=" + ghast.getUniqueId());
        return ghast;
    }

    public void remove(PetRecord pet) throws SQLException {
        findEntity(pet).ifPresent(Entity::remove);
        despawnDuplicates(pet, null);
        repository.delete(pet.petId());
        buffController.clear(pet);
        soundController.clear(pet);
        lastLocationSave.remove(pet.petId());
        petCache.remove(pet.petId());
    }

    public Optional<PetRecord> petFromEntity(Entity entity) {
        Optional<UUID> petId = readPetId(entity);
        if (petId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return petById(petId.get());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load pet " + petId.get() + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<PetRecord> targetPet(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof HappyGhast) {
            Optional<PetRecord> pet = petFromEntity(vehicle);
            if (pet.isPresent()) {
                return pet;
            }
        }

        double distance = config.targetDistance();
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                distance,
                0.6,
                entity -> entity instanceof HappyGhast
        );
        if (result != null && result.getHitEntity() != null) {
            Optional<PetRecord> pet = petFromEntity(result.getHitEntity());
            if (pet.isPresent()) {
                return pet;
            }
        }

        try {
            List<PetRecord> pets = petsOwnedBy(player.getUniqueId());
            return pets.isEmpty() ? Optional.empty() : Optional.of(pets.get(0));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to load pets for " + player.getName() + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<HappyGhast> targetUntamedHappyGhast(Player player) {
        double distance = config.targetDistance();
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                distance,
                0.6,
                entity -> entity instanceof HappyGhast && readPetId(entity).isEmpty()
        );
        if (result != null && result.getHitEntity() instanceof HappyGhast ghast) {
            return Optional.of(ghast);
        }
        return Optional.empty();
    }

    public boolean canRide(PetRecord pet, Player player) {
        if (pet.ownerUuid().equals(player.getUniqueId())) {
            return true;
        }
        try {
            return repository.isTrusted(pet.petId(), player.getUniqueId());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to check trust for " + player.getName() + ": " + exception.getMessage());
            return false;
        }
    }

    public void tickRidingBuffs() {
        for (World world : Bukkit.getWorlds()) {
            for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
                Optional<PetRecord> pet = petFromEntity(ghast);
                if (pet.isEmpty()) {
                    continue;
                }
                updatePetLocation(ghast, pet.get());
                if (!ghast.getPassengers().isEmpty()) {
                    buffController.applyPassengerBuffs(ghast, pet.get());
                } else {
                    boolean wasParked = pet.get().parked();
                    idleController.applyIdleBehavior(ghast, pet.get());
                    if (wasParked != pet.get().parked()) {
                        try {
                            repository.save(pet.get());
                        } catch (SQLException exception) {
                            plugin.getLogger().warning("Failed to update idle state: " + exception.getMessage());
                        }
                    }
                }
                soundController.playReducedAmbientSound(ghast, pet.get());
            }
        }
    }

    public void reloadRuntimeConfig() {
        plugin.reloadConfig();
        messages.reload();
        ConfigValidator.validate(plugin);
        buffController.clearAll();
        soundController.clearAll();
        for (World world : Bukkit.getWorlds()) {
            for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
                petFromEntity(ghast).ifPresent(pet -> applyPetStats(ghast, pet));
            }
        }
    }

    public boolean feed(Player player, HappyGhast ghast, ItemStack item) throws SQLException {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Optional<PetRecord> pet = petFromEntity(ghast);
        if (pet.isEmpty() || !pet.get().ownerUuid().equals(player.getUniqueId())) {
            return false;
        }
        Material food = item.getType();
        int exp = feedingExp(food);
        if (exp <= 0) {
            return false;
        }
        int foodLimit = feedingDailyMax(food);
        LocalDate today = LocalDate.now();
        if (foodLimit > 0) {
            int used = repository.foodUsage(pet.get().petId(), today, food.name());
            if (used >= foodLimit) {
                messages.send(player, "feed.food-limit", "item", food.name(), "used", used, "max", foodLimit);
                playFailedFeedEffects(player, ghast);
                logAction(player.getName(), "feed", pet.get(), "blocked food_limit item=" + food.name());
                return true;
            }
        }
        int added = addExperience(pet.get(), exp, true);
        if (added <= 0) {
            messages.send(player, "feed.daily-exp-reached");
            playFailedFeedEffects(player, ghast);
            logAction(player.getName(), "feed", pet.get(), "blocked daily_exp_limit item=" + food.name());
            return true;
        }
        if (foodLimit > 0) {
            repository.addFoodUsage(pet.get().petId(), today, food.name(), 1);
        }
        consumeOne(item);
        playFeedEffects(player, ghast);
        messages.actionBar(player, "feed.actionbar");
        messages.send(player, "feed.gained",
                "added", added,
                "level", pet.get().level(),
                "max_level", maxLevel(),
                "exp", pet.get().exp(),
                "daily", pet.get().dailyExp(),
                "daily_limit", dailyLimit());
        if (config.autoUpgrade()) {
            while (upgrade(pet.get())) {
                messages.send(player, "upgrade.done",
                        "level", pet.get().level(),
                        "improvement", improvementName(pet.get().level()));
            }
        }
        if (pet.get().level() < maxLevel()) {
            int required = requiredExp(pet.get().level() + 1);
            messages.send(player, "feed.to-next-level",
                    "level", pet.get().level() + 1,
                    "needed", Math.max(0, required - pet.get().exp()));
        }
        logAction(player.getName(), "feed", pet.get(), "item=" + food.name() + ", added_exp=" + added);
        return true;
    }

    public int addExperience(PetRecord pet, int amount, boolean dailyLimited) throws SQLException {
        resetDailyIfNeeded(pet);
        int actual = amount;
        if (dailyLimited) {
            int limit = config.dailyLimit();
            actual = Math.max(0, Math.min(amount, limit - pet.dailyExp()));
            pet.dailyExp(pet.dailyExp() + actual);
        }
        if (actual <= 0) {
            repository.save(pet);
            return 0;
        }
        pet.exp(pet.exp() + actual);
        repository.save(pet);
        return actual;
    }

    public boolean upgrade(PetRecord pet) throws SQLException {
        int maxLevel = maxLevel();
        if (pet.level() >= maxLevel) {
            return false;
        }
        int nextLevel = pet.level() + 1;
        int requiredExp = requiredExp(nextLevel);
        if (pet.exp() < requiredExp) {
            return false;
        }
        pet.exp(pet.exp() - requiredExp);
        pet.level(nextLevel);
        repository.save(pet);
        findEntity(pet).filter(HappyGhast.class::isInstance).map(HappyGhast.class::cast)
                .ifPresent(ghast -> {
                    applyPetStats(ghast, pet);
                    displayService.healToFull(ghast);
                    playUpgradeEffects(ghast);
                });
        logAction(pet.ownerName(), "upgrade", pet, "level=" + nextLevel + ", spent_exp=" + requiredExp);
        return true;
    }

    public void applyPetStats(HappyGhast ghast, PetRecord pet) {
        displayService.applyPetStats(ghast, pet);
    }

    public void markEntity(HappyGhast ghast, PetRecord pet) {
        PersistentDataContainer data = ghast.getPersistentDataContainer();
        data.set(petIdKey, PersistentDataType.STRING, pet.petId().toString());
        data.set(ownerKey, PersistentDataType.STRING, pet.ownerUuid().toString());
    }

    public void park(PetRecord pet, HappyGhast ghast) throws SQLException {
        if (idleController.park(pet, ghast)) {
            repository.save(pet);
            logAction(pet.ownerName(), "park", pet, "after dismount at " + locationSummary(ghast.getLocation()));
        }
    }

    public void unpark(PetRecord pet, HappyGhast ghast) throws SQLException {
        if (idleController.unpark(pet, ghast)) {
            repository.save(pet);
            logAction(pet.ownerName(), "unpark", pet, "mounted or behavior changed");
        }
    }

    public void setParkAfterDismount(PetRecord pet, boolean enabled) throws SQLException {
        pet.parkAfterDismount(enabled);
        if (!enabled) {
            pet.parked(false);
            findEntity(pet).filter(HappyGhast.class::isInstance).map(HappyGhast.class::cast)
                    .ifPresent(ghast -> ghast.setAI(true));
        }
        repository.save(pet);
        logAction(pet.ownerName(), "behavior", pet, "park_after_dismount=" + enabled);
    }

    public void toggleParkAfterDismount(PetRecord pet) throws SQLException {
        setParkAfterDismount(pet, !pet.parkAfterDismount());
    }

    public Optional<Entity> findEntity(PetRecord pet) {
        if (pet.entityUuid() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bukkit.getEntity(pet.entityUuid()));
    }

    public List<PetRecord> petsOwnedBy(UUID ownerUuid) throws SQLException {
        List<PetRecord> rows = repository.findByOwner(ownerUuid);
        List<PetRecord> result = new java.util.ArrayList<>(rows.size());
        for (PetRecord row : rows) {
            result.add(petCache.computeIfAbsent(row.petId(), id -> row));
        }
        return result;
    }

    public Optional<PetRecord> petById(UUID petId) throws SQLException {
        PetRecord cached = petCache.get(petId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<PetRecord> row = repository.findByPetId(petId);
        row.ifPresent(pet -> petCache.put(petId, pet));
        return row;
    }

    public List<String> trustedNames(PetRecord pet) throws SQLException {
        return repository.trustedNames(pet.petId());
    }

    public void rename(PetRecord pet, String name) throws SQLException {
        pet.name(name);
        repository.save(pet);
        findEntity(pet).filter(HappyGhast.class::isInstance).map(HappyGhast.class::cast)
                .ifPresent(ghast -> applyPetStats(ghast, pet));
        logAction(pet.ownerName(), "rename", pet, "name=" + name);
    }

    public void trust(PetRecord pet, UUID playerUuid, String playerName) throws SQLException {
        repository.trust(pet.petId(), playerUuid, playerName);
        logAction(pet.ownerName(), "trust", pet, "trusted=" + playerName + "/" + playerUuid);
    }

    public void untrust(PetRecord pet, UUID playerUuid) throws SQLException {
        repository.untrust(pet.petId(), playerUuid);
        logAction(pet.ownerName(), "untrust", pet, "player=" + playerUuid);
    }

    public void setLevel(PetRecord pet, int level) throws SQLException {
        pet.level(level);
        repository.save(pet);
        findEntity(pet).filter(HappyGhast.class::isInstance).map(HappyGhast.class::cast)
                .ifPresent(ghast -> applyPetStats(ghast, pet));
        logAction(pet.ownerName(), "set_level", pet, "level=" + level);
    }

    public void savePet(PetRecord pet) throws SQLException {
        repository.save(pet);
    }

    public void markDead(PetRecord pet) throws SQLException {
        pet.entityUuid(null);
        pet.dismissed(true);
        pet.parked(false);
        repository.save(pet);
        Bukkit.broadcast(messages.component("death.broadcast", "name", pet.name(), "owner", pet.ownerName()));
        logAction(pet.ownerName(), "death", pet, "entity died");
    }

    // ---- Harness (cosmetic) ----

    public boolean harnessRecolorEnabled() {
        return config.harnessRecolorEnabled();
    }

    /** The pet's current harness material, or empty if the pet isn't currently spawned/loaded. AIR means no harness. */
    public Optional<Material> currentHarness(PetRecord pet) {
        Entity entity = findEntity(pet).orElse(null);
        if (!(entity instanceof HappyGhast ghast) || ghast.getEquipment() == null) {
            return Optional.empty();
        }
        return Optional.of(ghast.getEquipment().getItem(EquipmentSlot.BODY).getType());
    }

    /** Cycle the harness to the next colour. Returns the new material, or empty if the pet isn't currently spawned. */
    public Optional<Material> cycleHarness(PetRecord pet) {
        Entity entity = findEntity(pet).orElse(null);
        if (!(entity instanceof HappyGhast ghast)) {
            return Optional.empty();
        }
        EntityEquipment equipment = ghast.getEquipment();
        if (equipment == null) {
            return Optional.empty();
        }
        Material current = equipment.getItem(EquipmentSlot.BODY).getType();
        int index = -1;
        for (int i = 0; i < HARNESS_COLORS.length; i++) {
            if (HARNESS_COLORS[i] == current) {
                index = i;
                break;
            }
        }
        Material next = HARNESS_COLORS[(index + 1) % HARNESS_COLORS.length];
        equipment.setItem(EquipmentSlot.BODY, new ItemStack(next));
        // Cosmetic only: never drop on death, so it can't be farmed by recolour-then-kill.
        equipment.setDropChance(EquipmentSlot.BODY, 0.0f);
        logAction(pet.ownerName(), "harness", pet, "color=" + next.name());
        return Optional.of(next);
    }

    // ---- Pet storage (chest inventory) ----

    public boolean storageEnabled() {
        return config.inventoryEnabled();
    }

    public int storageSize() {
        return config.inventorySize();
    }

    public double storageOpenRadius() {
        return config.inventoryOpenRadius();
    }

    public void clearStorage(PetRecord pet) throws SQLException {
        repository.deleteStorage(pet.petId());
    }

    public ItemStack[] loadStorageItems(PetRecord pet) throws SQLException {
        ItemStack[] stored = ItemSerialization.fromBase64(repository.loadStorage(pet.petId()));
        ItemStack[] sized = new ItemStack[storageSize()];
        System.arraycopy(stored, 0, sized, 0, Math.min(stored.length, sized.length));
        return sized;
    }

    public void saveStorageItems(PetRecord pet, ItemStack[] items) throws SQLException {
        repository.saveStorage(pet.petId(), ItemSerialization.toBase64(items));
    }

    // ---- Logging ----

    public void logCommand(CommandSender sender, String label, String[] args) {
        String arguments = args.length == 0 ? "" : " " + String.join(" ", args);
        plugin.getLogger().info("command sender=" + sender.getName() + " used=/" + label + arguments);
    }

    public void logAction(String actor, String action, String result) {
        plugin.getLogger().info("action actor=" + actor + " action=" + action + " result=" + result);
    }

    public void logAction(String actor, String action, PetRecord pet, String result) {
        plugin.getLogger().info("action actor=" + actor
                + " action=" + action
                + " pet=" + pet.petId()
                + " owner=" + pet.ownerName()
                + " level=" + pet.level()
                + " result=" + result);
    }

    // ---- Config-derived helpers exposed to GUI/commands ----

    public int requiredExp(int level) {
        return config.requiredExp(level);
    }

    public int maxLevel() {
        return config.maxLevel();
    }

    public int feedingExp(Material material) {
        return config.feedingExp(material);
    }

    public int feedingDailyMax(Material material) {
        return config.feedingDailyMax(material);
    }

    public int foodUsageToday(PetRecord pet, Material material) throws SQLException {
        return repository.foodUsage(pet.petId(), LocalDate.now(), material.name());
    }

    public List<Material> feedingMaterials() {
        return config.feedingMaterials();
    }

    public int dailyLimit() {
        return config.dailyLimit();
    }

    public int speedPercent(int level) {
        return config.speedPercent(level);
    }

    public double maxHealth(int level) {
        return config.maxHealth(level);
    }

    public List<String> passengerBuffSummary(int level) {
        return config.passengerBuffSummary(level);
    }

    public String resurrectionCostText() {
        return config.resurrectionItems().entrySet().stream()
                .map(entry -> entry.getValue() + "x " + entry.getKey().name())
                .reduce((left, right) -> left + ", " + right)
                .orElse(messages.raw("resurrect.cost-none"));
    }

    public Material resurrectionRequiredBlock() {
        return config.resurrectionRequiredBlock();
    }

    public int resurrectionBlockRadius() {
        return config.resurrectionBlockRadius();
    }

    public String improvementName(int level) {
        return config.improvementName(level);
    }

    public String defaultPetName() {
        return config.defaultPetName();
    }

    public int maxPetNameLength() {
        return config.maxPetNameLength();
    }

    /** Validate and sanitize a pet name. Sends the relevant message and returns null when invalid. */
    public String validatePetName(Player player, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isBlank()) {
            messages.send(player, "name.empty");
            return null;
        }
        int maxLength = Math.max(1, maxPetNameLength());
        if (name.length() > maxLength) {
            messages.send(player, "name.too-long", "max", maxLength);
            return null;
        }
        if (!player.hasPermission("happyghastpet.colorname")) {
            name = name.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
        }
        return name;
    }

    /** Run a task on the main server thread (e.g. from an async chat event). */
    public void runMainThread(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public void resetDailyCapIfNeeded(PetRecord pet) throws SQLException {
        LocalDate before = pet.dailyDate();
        resetDailyIfNeeded(pet);
        if (!before.equals(pet.dailyDate())) {
            repository.save(pet);
        }
    }

    public long summonCooldownMillis() {
        return config.summonCooldownMillis();
    }

    public long summonCooldownRemainingMillis(PetRecord pet) {
        long elapsed = System.currentTimeMillis() - pet.lastSummonAt();
        return Math.max(0L, summonCooldownMillis() - elapsed);
    }

    public String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0) {
            return hours + "ч " + minutes + "м";
        }
        if (minutes > 0) {
            return minutes + "м " + remainingSeconds + "с";
        }
        return remainingSeconds + "с";
    }

    // ---- Internals ----

    private Optional<UUID> readPetId(Entity entity) {
        String value = entity.getPersistentDataContainer().get(petIdKey, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Flying pets spawn/teleport slightly above the player to avoid overlap and ground hazards (e.g. the ritual campfire). */
    private Location spawnLocationFor(Player player) {
        return player.getLocation().add(0.0, 2.0, 0.0);
    }

    /** Find the live entity, force-loading its last known chunk so it materializes instead of being duplicated. */
    private Optional<Entity> findOrLoadEntity(PetRecord pet) {
        if (pet.entityUuid() == null) {
            return Optional.empty();
        }
        Entity entity = Bukkit.getEntity(pet.entityUuid());
        if (entity != null) {
            return Optional.of(entity);
        }
        World world = pet.world() == null ? null : Bukkit.getWorld(pet.world());
        if (world != null) {
            world.getChunkAt((int) Math.floor(pet.x()) >> 4, (int) Math.floor(pet.z()) >> 4);
            entity = Bukkit.getEntity(pet.entityUuid());
            if (entity != null) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    /** Remove any stray ghast carrying this pet's marker (cleans up old duplicates). */
    private void despawnDuplicates(PetRecord pet, UUID keepUuid) {
        if (pet.world() == null) {
            return;
        }
        World world = Bukkit.getWorld(pet.world());
        if (world == null) {
            return;
        }
        String idString = pet.petId().toString();
        for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
            if (keepUuid != null && ghast.getUniqueId().equals(keepUuid)) {
                continue;
            }
            String marker = ghast.getPersistentDataContainer().get(petIdKey, PersistentDataType.STRING);
            if (idString.equals(marker)) {
                ghast.remove();
            }
        }
    }

    private void updatePetLocation(HappyGhast ghast, PetRecord pet) {
        long now = System.currentTimeMillis();
        long periodMillis = config.locationSavePeriodMillis();
        long previous = lastLocationSave.getOrDefault(pet.petId(), 0L);
        if (now - previous < periodMillis) {
            return;
        }
        Location location = ghast.getLocation();
        pet.entityUuid(ghast.getUniqueId());
        pet.position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        pet.dismissed(false);
        try {
            repository.save(pet);
            lastLocationSave.put(pet.petId(), now);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to update pet location: " + exception.getMessage());
        }
    }

    private String locationSummary(Location location) {
        return location.getWorld().getName()
                + " " + Math.round(location.getX())
                + " " + Math.round(location.getY())
                + " " + Math.round(location.getZ());
    }

    private void resetDailyIfNeeded(PetRecord pet) {
        LocalDate today = LocalDate.now();
        if (!today.equals(pet.dailyDate())) {
            pet.dailyDate(today);
            pet.dailyExp(0);
        }
    }

    private void consumeOne(ItemStack item) {
        if (item.getAmount() <= 1) {
            item.setAmount(0);
            return;
        }
        item.setAmount(item.getAmount() - 1);
    }

    private void playFeedEffects(Player player, HappyGhast ghast) {
        Location location = ghast.getLocation().add(0.0, 1.5, 0.0);
        ghast.getWorld().spawnParticle(Particle.HEART, location, 6, 0.8, 0.6, 0.8, 0.0);
        ghast.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 12, 0.8, 0.6, 0.8, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.25f);
    }

    private void playFailedFeedEffects(Player player, HappyGhast ghast) {
        ghast.getWorld().spawnParticle(Particle.SMOKE, ghast.getLocation().add(0.0, 1.5, 0.0), 8, 0.5, 0.4, 0.5, 0.01);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }

    private void playUpgradeEffects(HappyGhast ghast) {
        Location location = ghast.getLocation().add(0.0, 1.7, 0.0);
        ghast.getWorld().spawnParticle(Particle.END_ROD, location, 24, 1.0, 0.8, 1.0, 0.05);
        ghast.getWorld().spawnParticle(Particle.HEART, location, 8, 0.9, 0.7, 0.9, 0.0);
        ghast.getWorld().playSound(ghast.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
    }

    private boolean isNearResurrectionBlock(Location center) {
        Material requiredBlock = config.resurrectionRequiredBlock();
        int radius = config.resurrectionBlockRadius();
        World world = center.getWorld();
        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();
        for (int x = baseX - radius; x <= baseX + radius; x++) {
            for (int y = baseY - radius; y <= baseY + radius; y++) {
                for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                    if (world.getBlockAt(x, y, z).getType() == requiredBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasItems(Inventory inventory, Map<Material, Integer> requiredItems) {
        for (Map.Entry<Material, Integer> entry : requiredItems.entrySet()) {
            int found = 0;
            for (ItemStack item : inventory.getStorageContents()) {
                if (item != null && item.getType() == entry.getKey()) {
                    found += item.getAmount();
                }
            }
            if (found < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void takeItems(Inventory inventory, Map<Material, Integer> requiredItems) {
        for (Map.Entry<Material, Integer> entry : requiredItems.entrySet()) {
            int remaining = entry.getValue();
            ItemStack[] contents = inventory.getStorageContents();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack item = contents[slot];
                if (item == null || item.getType() != entry.getKey()) {
                    continue;
                }
                int taken = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - taken);
                remaining -= taken;
                if (item.getAmount() <= 0) {
                    contents[slot] = null;
                }
            }
            inventory.setStorageContents(contents);
        }
    }

    private void playResurrectionEffects(Player player, HappyGhast ghast) {
        Location location = ghast.getLocation().add(0.0, 1.5, 0.0);
        ghast.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, location, 24, 0.9, 0.7, 0.9, 0.02);
        ghast.getWorld().spawnParticle(Particle.END_ROD, location, 18, 0.7, 0.5, 0.7, 0.04);
        ghast.getWorld().playSound(ghast.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.2f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 0.9f);
    }
}
