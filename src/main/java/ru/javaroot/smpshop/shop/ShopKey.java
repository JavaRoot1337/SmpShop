package ru.javaroot.smpshop.shop;

import org.bukkit.Location;

import java.util.UUID;

public record ShopKey(UUID worldId, int x, int y, int z) {
    public static ShopKey of(Location loc) {
        UUID worldId = loc.getWorld() == null ? null : loc.getWorld().getUID();
        return new ShopKey(worldId, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
