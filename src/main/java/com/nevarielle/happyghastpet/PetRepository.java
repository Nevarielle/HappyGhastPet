package com.nevarielle.happyghastpet;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PetRepository {
    private final File databaseFile;
    private Connection connection;

    public PetRepository(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    public synchronized void open() throws SQLException {
        databaseFile.getParentFile().mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pets (
                        pet_id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        owner_name TEXT NOT NULL,
                        pet_name TEXT NOT NULL,
                        level INTEGER NOT NULL,
                        exp INTEGER NOT NULL,
                        daily_exp INTEGER NOT NULL,
                        daily_date TEXT NOT NULL,
                        entity_uuid TEXT,
                        world TEXT,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        dismissed INTEGER NOT NULL,
                        last_summon_at INTEGER NOT NULL DEFAULT 0,
                        parked INTEGER NOT NULL DEFAULT 0,
                        park_after_dismount INTEGER NOT NULL DEFAULT 0,
                        park_world TEXT,
                        park_x REAL NOT NULL DEFAULT 0,
                        park_y REAL NOT NULL DEFAULT 0,
                        park_z REAL NOT NULL DEFAULT 0
                    )
                    """);
            addColumnIfMissing(statement, "pets", "last_summon_at", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "pets", "parked", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "pets", "park_after_dismount", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "pets", "park_world", "TEXT");
            addColumnIfMissing(statement, "pets", "park_x", "REAL NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "pets", "park_y", "REAL NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "pets", "park_z", "REAL NOT NULL DEFAULT 0");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trusted_players (
                        pet_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        PRIMARY KEY (pet_id, player_uuid)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pet_food_usage (
                        pet_id TEXT NOT NULL,
                        usage_date TEXT NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        PRIMARY KEY (pet_id, usage_date, material)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pet_storage (
                        pet_id TEXT PRIMARY KEY,
                        data TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS pets_owner_idx ON pets(owner_uuid)");
        }
    }

    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void save(PetRecord pet) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pets (
                    pet_id, owner_uuid, owner_name, pet_name, level, exp, daily_exp, daily_date,
                    entity_uuid, world, x, y, z, dismissed, last_summon_at, parked,
                    park_after_dismount, park_world, park_x, park_y, park_z
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(pet_id) DO UPDATE SET
                    owner_uuid = excluded.owner_uuid,
                    owner_name = excluded.owner_name,
                    pet_name = excluded.pet_name,
                    level = excluded.level,
                    exp = excluded.exp,
                    daily_exp = excluded.daily_exp,
                    daily_date = excluded.daily_date,
                    entity_uuid = excluded.entity_uuid,
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    dismissed = excluded.dismissed,
                    last_summon_at = excluded.last_summon_at,
                    parked = excluded.parked,
                    park_after_dismount = excluded.park_after_dismount,
                    park_world = excluded.park_world,
                    park_x = excluded.park_x,
                    park_y = excluded.park_y,
                    park_z = excluded.park_z
                """)) {
            statement.setString(1, pet.petId().toString());
            statement.setString(2, pet.ownerUuid().toString());
            statement.setString(3, pet.ownerName());
            statement.setString(4, pet.name());
            statement.setInt(5, pet.level());
            statement.setInt(6, pet.exp());
            statement.setInt(7, pet.dailyExp());
            statement.setString(8, pet.dailyDate().toString());
            statement.setString(9, pet.entityUuid() == null ? null : pet.entityUuid().toString());
            statement.setString(10, pet.world());
            statement.setDouble(11, pet.x());
            statement.setDouble(12, pet.y());
            statement.setDouble(13, pet.z());
            statement.setInt(14, pet.dismissed() ? 1 : 0);
            statement.setLong(15, pet.lastSummonAt());
            statement.setInt(16, pet.parked() ? 1 : 0);
            statement.setInt(17, pet.parkAfterDismount() ? 1 : 0);
            statement.setString(18, pet.parkWorld());
            statement.setDouble(19, pet.parkX());
            statement.setDouble(20, pet.parkY());
            statement.setDouble(21, pet.parkZ());
            statement.executeUpdate();
        }
    }

    public synchronized Optional<PetRecord> findByPetId(UUID petId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM pets WHERE pet_id = ?")) {
            statement.setString(1, petId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readPet(result)) : Optional.empty();
            }
        }
    }

    public synchronized List<PetRecord> findByOwner(UUID ownerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pets WHERE owner_uuid = ? ORDER BY rowid ASC")) {
            statement.setString(1, ownerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<PetRecord> pets = new ArrayList<>();
                while (result.next()) {
                    pets.add(readPet(result));
                }
                return pets;
            }
        }
    }

    public synchronized int countByOwner(UUID ownerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pets WHERE owner_uuid = ?")) {
            statement.setString(1, ownerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public synchronized void delete(UUID petId) throws SQLException {
        try (PreparedStatement trusted = connection.prepareStatement("DELETE FROM trusted_players WHERE pet_id = ?");
             PreparedStatement food = connection.prepareStatement("DELETE FROM pet_food_usage WHERE pet_id = ?");
             PreparedStatement storage = connection.prepareStatement("DELETE FROM pet_storage WHERE pet_id = ?");
             PreparedStatement pets = connection.prepareStatement("DELETE FROM pets WHERE pet_id = ?")) {
            trusted.setString(1, petId.toString());
            trusted.executeUpdate();
            food.setString(1, petId.toString());
            food.executeUpdate();
            storage.setString(1, petId.toString());
            storage.executeUpdate();
            pets.setString(1, petId.toString());
            pets.executeUpdate();
        }
    }

    public synchronized String loadStorage(UUID petId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT data FROM pet_storage WHERE pet_id = ?")) {
            statement.setString(1, petId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("data") : null;
            }
        }
    }

    public synchronized void saveStorage(UUID petId, String data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pet_storage (pet_id, data) VALUES (?, ?)
                ON CONFLICT(pet_id) DO UPDATE SET data = excluded.data
                """)) {
            statement.setString(1, petId.toString());
            statement.setString(2, data);
            statement.executeUpdate();
        }
    }

    public synchronized void deleteStorage(UUID petId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pet_storage WHERE pet_id = ?")) {
            statement.setString(1, petId.toString());
            statement.executeUpdate();
        }
    }

    public synchronized void trust(UUID petId, UUID playerUuid, String playerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO trusted_players (pet_id, player_uuid, player_name)
                VALUES (?, ?, ?)
                ON CONFLICT(pet_id, player_uuid) DO UPDATE SET player_name = excluded.player_name
                """)) {
            statement.setString(1, petId.toString());
            statement.setString(2, playerUuid.toString());
            statement.setString(3, playerName);
            statement.executeUpdate();
        }
    }

    public synchronized void untrust(UUID petId, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM trusted_players WHERE pet_id = ? AND player_uuid = ?")) {
            statement.setString(1, petId.toString());
            statement.setString(2, playerUuid.toString());
            statement.executeUpdate();
        }
    }

    public synchronized boolean isTrusted(UUID petId, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM trusted_players WHERE pet_id = ? AND player_uuid = ?")) {
            statement.setString(1, petId.toString());
            statement.setString(2, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized List<String> trustedNames(UUID petId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name FROM trusted_players WHERE pet_id = ? ORDER BY player_name ASC")) {
            statement.setString(1, petId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (result.next()) {
                    names.add(result.getString("player_name"));
                }
                return names;
            }
        }
    }

    public synchronized int foodUsage(UUID petId, LocalDate date, String material) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amount FROM pet_food_usage WHERE pet_id = ? AND usage_date = ? AND material = ?")) {
            statement.setString(1, petId.toString());
            statement.setString(2, date.toString());
            statement.setString(3, material);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("amount") : 0;
            }
        }
    }

    public synchronized void addFoodUsage(UUID petId, LocalDate date, String material, int amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pet_food_usage (pet_id, usage_date, material, amount)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(pet_id, usage_date, material) DO UPDATE SET
                    amount = amount + excluded.amount
                """)) {
            statement.setString(1, petId.toString());
            statement.setString(2, date.toString());
            statement.setString(3, material);
            statement.setInt(4, amount);
            statement.executeUpdate();
        }
    }

    private PetRecord readPet(ResultSet result) throws SQLException {
        String entityUuid = result.getString("entity_uuid");
        return new PetRecord(
                UUID.fromString(result.getString("pet_id")),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("owner_name"),
                result.getString("pet_name"),
                result.getInt("level"),
                result.getInt("exp"),
                result.getInt("daily_exp"),
                LocalDate.parse(result.getString("daily_date")),
                entityUuid == null ? null : UUID.fromString(entityUuid),
                result.getString("world"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getInt("dismissed") != 0,
                result.getLong("last_summon_at"),
                result.getInt("parked") != 0,
                result.getInt("park_after_dismount") != 0,
                result.getString("park_world"),
                result.getDouble("park_x"),
                result.getDouble("park_y"),
                result.getDouble("park_z")
        );
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String definition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase().contains("duplicate column name")) {
                throw exception;
            }
        }
    }
}
