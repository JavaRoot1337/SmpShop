package ru.javaroot.smpshop.shop;

import org.bukkit.Material;

import java.util.UUID;

public record SaleLog(UUID sellerId, long shopId, String shopName, Material material, int items, long amount) {
}
