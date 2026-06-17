package com.nevarielle.happyghastpet;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Serializes an ItemStack[] (slot layout preserved, nulls allowed) to/from a Base64 string.
 * Uses Bukkit's own serialization, which is tolerant across Minecraft versions.
 */
public final class ItemSerialization {
    private ItemSerialization() {
    }

    public static String toBase64(ItemStack[] items) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeInt(items.length);
            for (ItemStack item : items) {
                output.writeObject(item);
            }
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize items", exception);
        }
    }

    public static ItemStack[] fromBase64(String data) {
        if (data == null || data.isBlank()) {
            return new ItemStack[0];
        }
        byte[] raw = Base64.getDecoder().decode(data);
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
             BukkitObjectInputStream input = new BukkitObjectInputStream(bytes)) {
            int length = input.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) input.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to deserialize items", exception);
        }
    }
}
