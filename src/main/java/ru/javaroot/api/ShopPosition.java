package ru.javaroot.api;

import java.util.Objects;
import java.util.UUID;

public record ShopPosition(UUID worldId, int x, int y, int z) {
    public ShopPosition {
        Objects.requireNonNull(worldId, "worldId");
    }
}
