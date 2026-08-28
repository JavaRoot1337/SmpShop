package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record ShopCreate(UUID operationId, NamespacedKey source, UUID ownerId, String ownerName,
                         ShopPosition first, ShopPosition second, int price) {
    public ShopCreate {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(first, "first");
    }
}
