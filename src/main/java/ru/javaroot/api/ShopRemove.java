package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record ShopRemove(UUID operationId, NamespacedKey source, long shopId) {
    public ShopRemove {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(source, "source");
    }
}
