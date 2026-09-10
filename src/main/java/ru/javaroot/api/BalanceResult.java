package ru.javaroot.api;

import java.util.Objects;

public record BalanceResult(ApiStatus status, BalanceSnapshot source, BalanceSnapshot target) {
    public BalanceResult {
        Objects.requireNonNull(status, "статус");
    }

    public boolean successful() {
        return status == ApiStatus.SUCCESS;
    }
}
