package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record ShopPriceChange(UUID operationId, NamespacedKey source, long shopId, int price) {
    public ShopPriceChange {
        Objects.requireNonNull(operationId, "идентификатор операции");
        Objects.requireNonNull(source, "источник");
    }
}
