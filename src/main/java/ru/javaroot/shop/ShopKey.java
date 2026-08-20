package ru.javaroot.shop;

import org.bukkit.Location;

import java.util.UUID;

public record ShopKey(UUID worldId, int x, int y, int z) {
    public static ShopKey of(Location location) {
        UUID worldId = location.getWorld() == null ? null : location.getWorld().getUID();
        return new ShopKey(worldId, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
