package ru.javaroot.api;

import java.util.Objects;

public record OpenResult(ApiStatus status) {
    public OpenResult {
        Objects.requireNonNull(status, "статус");
    }

    public boolean successful() {
        return status == ApiStatus.SUCCESS;
    }
}
