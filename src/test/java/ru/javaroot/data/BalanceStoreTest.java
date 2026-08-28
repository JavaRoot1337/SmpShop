package ru.javaroot.data;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.javaroot.currency.Currency;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceStoreTest {
    @Test
    void changesAndTransfersBalancesIdempotently(@TempDir Path dataDir) {
        Sqlite db = new Sqlite(plugin(dataDir.toFile()), "balances.db", 5);
        db.start().join();
        try {
            BalanceStore store = new BalanceStore(db);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            UUID deposit = UUID.randomUUID();

            assertEquals(BalanceStore.MutationStatus.SUCCESS,
                    store.change(deposit, "test:balance", from, Currency.DIAMOND_ORE, 10).join().status());
            assertEquals(BalanceStore.MutationStatus.SUCCESS,
                    store.change(deposit, "test:balance", from, Currency.DIAMOND_ORE, 10).join().status());
            assertEquals(10, store.load(from).join().diamondOre());
            assertEquals(BalanceStore.MutationStatus.CONFLICT,
                    store.change(deposit, "test:balance", from, Currency.DIAMOND_ORE, 11).join().status());

            assertEquals(BalanceStore.MutationStatus.SUCCESS,
                    store.transfer(UUID.randomUUID(), "test:balance", from, to,
                            Currency.DIAMOND_ORE, 7).join().status());
            assertEquals(3, store.load(from).join().diamondOre());
            assertEquals(7, store.load(to).join().diamondOre());
            assertEquals(BalanceStore.MutationStatus.INSUFFICIENT_FUNDS,
                    store.transfer(UUID.randomUUID(), "test:balance", from, to,
                            Currency.DIAMOND_ORE, 4).join().status());
        } finally {
            db.close();
        }
    }

    @Test
    void rejectsOverflowAndSerializesConcurrentChanges(@TempDir Path dataDir) {
        Sqlite db = new Sqlite(plugin(dataDir.toFile()), "concurrent.db", 5);
        db.start().join();
        try {
            BalanceStore store = new BalanceStore(db);
            UUID playerId = UUID.randomUUID();
            assertEquals(BalanceStore.MutationStatus.SUCCESS,
                    store.change(UUID.randomUUID(), "test:balance", playerId,
                            Currency.DEEPSLATE_DIAMOND_ORE, Long.MAX_VALUE).join().status());
            assertEquals(BalanceStore.MutationStatus.CONFLICT,
                    store.change(UUID.randomUUID(), "test:balance", playerId,
                            Currency.DEEPSLATE_DIAMOND_ORE, 1).join().status());

            List<java.util.concurrent.CompletableFuture<BalanceStore.MutationResult>> changes = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                changes.add(store.change(UUID.randomUUID(), "test:concurrent", playerId,
                        Currency.DIAMOND_ORE, 1));
            }
            java.util.concurrent.CompletableFuture.allOf(changes.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            assertEquals(50, store.load(playerId).join().diamondOre());
        } finally {
            db.close();
        }
    }

    private Plugin plugin(File dataDir) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
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
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return 0;
    }
}
