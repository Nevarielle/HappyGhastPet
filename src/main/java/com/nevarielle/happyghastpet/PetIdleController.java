package com.nevarielle.happyghastpet;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.HappyGhast;
import org.bukkit.util.Vector;

public final class PetIdleController {
    private final PetConfig config;

    public PetIdleController(PetConfig config) {
        this.config = config;
    }

    public boolean park(PetRecord pet, HappyGhast ghast) {
        if (!pet.parkAfterDismount()) {
            return false;
        }
        Location location = ghast.getLocation();
        pet.parked(true);
        pet.parkPosition(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        ghast.setVelocity(new Vector(0.0, 0.0, 0.0));
        if (config.idleDisableAi()) {
            ghast.setAI(false);
        }
        return true;
    }

    public boolean unpark(PetRecord pet, HappyGhast ghast) {
        if (!pet.parked()) {
            return false;
        }
        pet.parked(false);
        ghast.setAI(true);
        return true;
    }

    public void applyIdleBehavior(HappyGhast ghast, PetRecord pet) {
        if (!pet.parkAfterDismount()) {
            if (pet.parked()) {
                pet.parked(false);
            }
            ghast.setAI(true);
            return;
        }
        String mode = config.idleMode();
        if ("OFF".equals(mode)) {
            ghast.setAI(true);
            return;
        }
        if (!pet.parked()) {
            Location location = ghast.getLocation();
            pet.parked(true);
            pet.parkPosition(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        }
        if (config.idleStopVelocity()) {
            ghast.setVelocity(new Vector(0.0, 0.0, 0.0));
        }
        boolean disableAi = config.idleDisableAi();
        ghast.setAI(!disableAi);
        if ("FREEZE".equals(mode)) {
            ghast.teleport(parkLocationOrCurrent(ghast, pet));
            return;
        }
        // Gentle return-to-park only works while AI is on; with AI off the ghast just hovers in place.
        if (disableAi) {
            return;
        }
        Location park = parkLocationOrCurrent(ghast, pet);
        double maxDistance = config.idleMaxDistanceFromPark();
        if (park.getWorld() == null || !park.getWorld().equals(ghast.getWorld())) {
            return;
        }
        if (ghast.getLocation().distanceSquared(park) <= maxDistance * maxDistance) {
            return;
        }
        Vector direction = park.toVector().subtract(ghast.getLocation().toVector());
        if (direction.lengthSquared() > 0.0001) {
            ghast.setVelocity(direction.normalize().multiply(config.idleReturnSpeed()));
        }
    }

    private Location parkLocationOrCurrent(HappyGhast ghast, PetRecord pet) {
        World world = pet.parkWorld() == null ? ghast.getWorld() : config.world(pet.parkWorld());
        if (world == null) {
            world = ghast.getWorld();
        }
        return new Location(world, pet.parkX(), pet.parkY(), pet.parkZ());
    }
}
