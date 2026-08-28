package ru.javaroot.api.event;

import org.bukkit.NamespacedKey;
import ru.javaroot.api.CurrencyAmounts;

import java.util.Objects;

public record PurchaseAdjustment(NamespacedKey key, AdjustmentType type, CurrencyAmounts amounts) {
    public PurchaseAdjustment {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amounts, "amounts");
        if (amounts.diamondOre() == 0 && amounts.deepslateDiamondOre() == 0) {
            throw new IllegalArgumentException("Adjustment cannot be empty");
        }
    }
}
