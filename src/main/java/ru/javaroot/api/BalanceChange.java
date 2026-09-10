package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record BalanceChange(UUID operationId, NamespacedKey source, UUID playerId,
                            CurrencyType currency, long amount) {
    public BalanceChange {
        Objects.requireNonNull(operationId, "идентификатор операции");
        Objects.requireNonNull(source, "источник");
        Objects.requireNonNull(playerId, "идентификатор игрока");
        Objects.requireNonNull(currency, "валюта");
    }
}
