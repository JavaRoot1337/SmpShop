package ru.javaroot.integration;

import java.util.UUID;

public record SaleLog(UUID sellerId, long shopId, String shopName, String item, int items, long amount) {
}
