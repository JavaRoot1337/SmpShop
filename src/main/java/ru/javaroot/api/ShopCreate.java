package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record ShopCreate(UUID operationId, NamespacedKey source, UUID ownerId, String ownerName,
                         ShopPosition first, ShopPosition second, int price) {
    public ShopCreate {
        Objects.requireNonNull(operationId, "идентификатор операции");
        Objects.requireNonNull(source, "источник");
        Objects.requireNonNull(ownerId, "идентификатор владельца");
        Objects.requireNonNull(ownerName, "имя владельца");
        Objects.requireNonNull(first, "первая позиция");
    }
}
