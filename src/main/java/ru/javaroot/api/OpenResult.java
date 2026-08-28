package ru.javaroot.api;

import java.util.Objects;

public record OpenResult(ApiStatus status) {
    public OpenResult {
        Objects.requireNonNull(status, "status");
    }

    public boolean successful() {
        return status == ApiStatus.SUCCESS;
    }
}
