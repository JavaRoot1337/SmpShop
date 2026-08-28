package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record BalanceChange(UUID operationId, NamespacedKey source, UUID playerId,
                            CurrencyType currency, long amount) {
    public BalanceChange {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currency, "currency");
    }
}
