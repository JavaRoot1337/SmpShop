package ru.javaroot.api;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import ru.javaroot.api.event.AdjustmentType;
import ru.javaroot.api.event.ShopPurchasePrepareEvent;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPurchasePrepareEventTest {
    @Test
    void rejectsDuplicateAdjustmentKey() {
        UUID world = UUID.randomUUID();
        ShopSnapshot shop = new ShopSnapshot(1, UUID.randomUUID(), "owner",
                new ShopPosition(world, 1, 2, 3), null, 10, 0, 0, 0, 1);
        ShopPurchasePrepareEvent event = new ShopPurchasePrepareEvent(UUID.randomUUID(),
                UUID.randomUUID(), shop, new CurrencyAmounts(10, 0));
        NamespacedKey key = new NamespacedKey("test", "fee");

        assertTrue(event.addAdjustment(key, AdjustmentType.BUYER_SURCHARGE,
                new CurrencyAmounts(1, 0)));
        assertFalse(event.addAdjustment(key, AdjustmentType.SELLER_DEDUCTION,
                new CurrencyAmounts(1, 0)));
    }
}
