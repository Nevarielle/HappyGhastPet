package com.nevarielle.happyghastpet;

import java.time.LocalDate;
import java.util.UUID;

public final class PetRecord {
    private final UUID petId;
    private final UUID ownerUuid;
    private String ownerName;
    private String name;
    private int level;
    private int exp;
    private int dailyExp;
    private LocalDate dailyDate;
    private UUID entityUuid;
    private String world;
    private double x;
    private double y;
    private double z;
    private boolean dismissed;
    private long lastSummonAt;
    private boolean parked;
    private boolean parkAfterDismount;
    private String parkWorld;
    private double parkX;
    private double parkY;
    private double parkZ;

    public PetRecord(
            UUID petId,
            UUID ownerUuid,
            String ownerName,
            String name,
            int level,
            int exp,
            int dailyExp,
            LocalDate dailyDate,
            UUID entityUuid,
            String world,
            double x,
            double y,
            double z,
            boolean dismissed,
            long lastSummonAt,
            boolean parked,
            boolean parkAfterDismount,
            String parkWorld,
            double parkX,
            double parkY,
            double parkZ
    ) {
        this.petId = petId;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.name = name;
        this.level = level;
        this.exp = exp;
        this.dailyExp = dailyExp;
        this.dailyDate = dailyDate;
        this.entityUuid = entityUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dismissed = dismissed;
        this.lastSummonAt = lastSummonAt;
        this.parked = parked;
        this.parkAfterDismount = parkAfterDismount;
        this.parkWorld = parkWorld;
        this.parkX = parkX;
        this.parkY = parkY;
        this.parkZ = parkZ;
    }

    public UUID petId() {
        return petId;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public void ownerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = level;
    }

    public int exp() {
        return exp;
    }

    public void exp(int exp) {
        this.exp = exp;
    }

    public int dailyExp() {
        return dailyExp;
    }

    public void dailyExp(int dailyExp) {
        this.dailyExp = dailyExp;
    }

    public LocalDate dailyDate() {
        return dailyDate;
    }

    public void dailyDate(LocalDate dailyDate) {
        this.dailyDate = dailyDate;
    }

    public UUID entityUuid() {
        return entityUuid;
    }

    public void entityUuid(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    public String world() {
        return world;
    }

    public void world(String world) {
        this.world = world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public void position(String world, double x, double y, double z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean dismissed() {
        return dismissed;
    }

    public void dismissed(boolean dismissed) {
        this.dismissed = dismissed;
    }

    public long lastSummonAt() {
        return lastSummonAt;
    }

    public void lastSummonAt(long lastSummonAt) {
        this.lastSummonAt = lastSummonAt;
    }

    public boolean parked() {
        return parked;
    }

    public void parked(boolean parked) {
        this.parked = parked;
    }

    public boolean parkAfterDismount() {
        return parkAfterDismount;
    }

    public void parkAfterDismount(boolean parkAfterDismount) {
        this.parkAfterDismount = parkAfterDismount;
    }

    public String parkWorld() {
        return parkWorld;
    }

    public double parkX() {
        return parkX;
    }

    public double parkY() {
        return parkY;
    }

    public double parkZ() {
        return parkZ;
    }

    public void parkPosition(String world, double x, double y, double z) {
        this.parkWorld = world;
        this.parkX = x;
        this.parkY = y;
        this.parkZ = z;
    }
}
