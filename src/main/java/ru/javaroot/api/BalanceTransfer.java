package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record BalanceTransfer(UUID operationId, NamespacedKey source, UUID fromPlayerId,
                              UUID toPlayerId, CurrencyType currency, long amount) {
    public BalanceTransfer {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fromPlayerId, "fromPlayerId");
        Objects.requireNonNull(toPlayerId, "toPlayerId");
        Objects.requireNonNull(currency, "currency");
    }
}
