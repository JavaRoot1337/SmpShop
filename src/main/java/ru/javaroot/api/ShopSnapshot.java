package ru.javaroot.api;

import java.util.Objects;
import java.util.UUID;

public record ShopSnapshot(long id, UUID ownerId, String ownerName, ShopPosition first,
                           ShopPosition second, int price, long earnedTotal,
                           long soldSlots, long soldItems, long createdAt) {
    public ShopSnapshot {
        if (id < 1 || price < 1 || earnedTotal < 0 || soldSlots < 0 || soldItems < 0) {
            throw new IllegalArgumentException("Invalid shop snapshot");
        }
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(first, "first");
    }
}
