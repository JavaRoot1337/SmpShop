package ru.javaroot.data;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.javaroot.currency.Currency;
import ru.javaroot.shop.Shop;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopStoreTest {
    @Test
    void settlesShopStatsBalanceAndJournalInOneDatabaseOperation(@TempDir Path dataDir) {
        UUID ownerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Plugin plugin = plugin(dataDir.toFile());
        Sqlite db = new Sqlite(plugin, "shops.db", 5);
        db.start().join();
        try {
            ShopStore store = new ShopStore(db);
            Shop draft = new Shop(0, ownerId, "owner", UUID.randomUUID(), 1, 64, 1,
                    null, null, null, 10, 0, 0, 0, System.currentTimeMillis(), true);
            long shopId = store.insert(draft).join();
            Shop shop = new Shop(shopId, draft.ownerId(), draft.ownerName(), draft.worldId(),
                    draft.x1(), draft.y1(), draft.z1(), draft.x2(), draft.y2(), draft.z2(),
                    draft.price(), draft.earnedTotal(), draft.soldSlots(), draft.soldItems(), draft.createdAt(), true);

            Map<Currency, Long> payment = Map.of(
                    Currency.DIAMOND_ORE, 7L, Currency.DEEPSLATE_DIAMOND_ORE, 3L);
            store.prepareSale("test-transaction", shop, buyerId, 3, 2, 10,
                    payment, payment, new byte[]{1, 2, 3}, "[]").join();
            store.completeSale("test-transaction", shop, 2, payment).join();
            store.completeSale("test-transaction", shop, 2, payment).join();

            var values = db.submit(connection -> {
                long earned;
                long sold;
                try (var statement = connection.prepareStatement("SELECT earned_total, sold_items FROM shops WHERE id = ?")) {
                    statement.setLong(1, shopId);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        earned = result.getLong(1);
                        sold = result.getLong(2);
                    }
                }
                try (var statement = connection.prepareStatement("SELECT diamond_ore, deepslate_diamond_ore FROM balances WHERE player_uuid = ?")) {
                    statement.setString(1, ownerId.toString());
                    try (var result = statement.executeQuery()) {
                        result.next();
                        return new long[]{earned, sold, result.getLong(1), result.getLong(2)};
                    }
                }
            }).join();
            assertEquals(10L, values[0]);
            assertEquals(2L, values[1]);
            assertEquals(7L, values[2]);
            assertEquals(3L, values[3]);
        } finally {
            db.close();
        }
    }

    private Plugin plugin(File dataDir) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dataDir;
                    case "getLogger" -> Logger.getAnonymousLogger();
                    case "toString" -> "test-plugin";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }
}
