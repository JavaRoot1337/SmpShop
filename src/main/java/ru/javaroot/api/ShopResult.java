package ru.javaroot.api;

import java.util.Objects;

public record ShopResult(ApiStatus status, ShopSnapshot shop) {
    public ShopResult {
        Objects.requireNonNull(status, "статус");
    }

    public boolean successful() {
        return status == ApiStatus.SUCCESS;
    }
}
