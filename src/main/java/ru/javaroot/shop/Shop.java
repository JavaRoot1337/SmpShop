package ru.javaroot.shop;

import java.util.UUID;

public record Shop(long id, UUID ownerId, String ownerName, UUID worldId,
                   int x1, int y1, int z1, Integer x2, Integer y2, Integer z2,
                   int price, long earnedTotal, long soldSlots, long soldItems,
                   long createdAt, boolean active) {
    public Shop {
        if (id < 0 || price < 1 || ownerId == null || worldId == null) {
            throw new IllegalArgumentException("Некорректные данные магазина");
        }
        boolean hasX = x2 != null;
        if (hasX != (y2 != null && z2 != null)) {
            throw new IllegalArgumentException("Некорректная позиция второго сундука магазина");
        }
    }

    public ShopKey firstKey() {
        return new ShopKey(worldId, x1, y1, z1);
    }

    public ShopKey secondKey() {
        return x2 == null ? null : new ShopKey(worldId, x2, y2, z2);
    }
}
