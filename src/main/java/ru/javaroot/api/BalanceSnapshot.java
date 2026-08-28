package ru.javaroot.api;

import java.util.Objects;
import java.util.UUID;

public record BalanceSnapshot(UUID playerId, CurrencyAmounts amounts) {
    public BalanceSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amounts, "amounts");
    }
}
