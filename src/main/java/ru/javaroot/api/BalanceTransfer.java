package ru.javaroot.api;

import org.bukkit.NamespacedKey;

import java.util.Objects;
import java.util.UUID;

public record BalanceTransfer(UUID operationId, NamespacedKey source, UUID fromPlayerId,
                              UUID toPlayerId, CurrencyType currency, long amount) {
    public BalanceTransfer {
        Objects.requireNonNull(operationId, "идентификатор операции");
        Objects.requireNonNull(source, "источник");
        Objects.requireNonNull(fromPlayerId, "идентификатор отправителя");
        Objects.requireNonNull(toPlayerId, "идентификатор получателя");
        Objects.requireNonNull(currency, "валюта");
    }
}
