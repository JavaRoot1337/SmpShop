package ru.javaroot.api.event;

import org.bukkit.NamespacedKey;
import ru.javaroot.api.CurrencyAmounts;

import java.util.Objects;

public record PurchaseAdjustment(NamespacedKey key, AdjustmentType type, CurrencyAmounts amounts) {
    public PurchaseAdjustment {
        Objects.requireNonNull(key, "ключ корректировки");
        Objects.requireNonNull(type, "тип корректировки");
        Objects.requireNonNull(amounts, "суммы валют");
        if (amounts.diamondOre() == 0 && amounts.deepslateDiamondOre() == 0) {
            throw new IllegalArgumentException("Корректировка не может быть пустой");
        }
    }
}
