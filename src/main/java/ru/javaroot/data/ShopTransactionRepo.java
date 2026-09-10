package ru.javaroot.data;

import ru.javaroot.currency.Currency;
import ru.javaroot.shop.Shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class ShopTransactionRepo {
    private final Sqlite db;

    ShopTransactionRepo(Sqlite db) {
        this.db = db;
    }

    CompletableFuture<Void> prepareSale(String transactionId, Shop shop, UUID buyerId,
                                         int itemSlot, int itemAmount, long price,
                                         Map<Currency, Long> payment, Map<Currency, Long> sellerPayout,
                                         byte[] itemSnapshot, String adjustments) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO transactions(transaction_key, shop_id, buyer_uuid, seller_uuid, state, price, diamond_ore_amount, deepslate_diamond_ore_amount, item_slot, item_snapshot, item_amount, seller_diamond_ore, seller_deepslate_diamond_ore, adjustments_json, created_at) VALUES (?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(transaction_key) DO NOTHING")) {
                statement.setString(1, transactionId);
                statement.setLong(2, shop.id());
                statement.setString(3, buyerId.toString());
                statement.setString(4, shop.ownerId().toString());
                statement.setLong(5, price);
                statement.setLong(6, payment.getOrDefault(Currency.DIAMOND_ORE, 0L));
                statement.setLong(7, payment.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L));
                statement.setInt(8, itemSlot);
                statement.setBytes(9, itemSnapshot);
                statement.setInt(10, itemAmount);
                statement.setLong(11, sellerPayout.getOrDefault(Currency.DIAMOND_ORE, 0L));
                statement.setLong(12, sellerPayout.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L));
                statement.setString(13, adjustments);
                statement.setLong(14, System.currentTimeMillis());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Идентификатор транзакции уже использован");
                }
            }
            return null;
        });
    }

    CompletableFuture<Boolean> completeSale(String transactionId, Shop shop, int items,
                                             Map<Currency, Long> sellerPayout) {
        return db.submit(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'COMPLETED', completed_at = ? WHERE transaction_key = ? AND state = 'PREPARED' AND shop_id = ? AND seller_uuid = ? AND price = ? AND item_amount = ? AND seller_diamond_ore = ? AND seller_deepslate_diamond_ore = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
                statement.setLong(3, shop.id());
                statement.setString(4, shop.ownerId().toString());
                statement.setLong(5, shop.price());
                statement.setInt(6, items);
                statement.setLong(7, sellerPayout.getOrDefault(Currency.DIAMOND_ORE, 0L));
                statement.setLong(8, sellerPayout.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L));
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shops SET earned_total = earned_total + ?, sold_slots = sold_slots + 1, sold_items = sold_items + ? WHERE id = ? AND deleted_at IS NULL AND earned_total <= ? AND sold_slots < ? AND sold_items <= ?")) {
                statement.setLong(1, shop.price());
                statement.setInt(2, items);
                statement.setLong(3, shop.id());
                statement.setLong(4, Long.MAX_VALUE - shop.price());
                statement.setLong(5, Long.MAX_VALUE);
                statement.setLong(6, Long.MAX_VALUE - items);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Магазин больше не активен или статистика переполнена");
                }
            }
            for (Map.Entry<Currency, Long> entry : sellerPayout.entrySet()) {
                deposit(connection, shop.ownerId(), entry.getKey(), entry.getValue());
            }
            return true;
        }));
    }

    CompletableFuture<Void> delivered(String transactionId) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'DELIVERED', delivered_at = ? WHERE transaction_key = ? AND state = 'COMPLETED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Boolean> delivered(String transactionId, UUID buyerId) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'DELIVERED', delivered_at = ? WHERE transaction_key = ? AND buyer_uuid = ? AND state = 'COMPLETED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
                statement.setString(3, buyerId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    CompletableFuture<Boolean> rollback(String transactionId, UUID buyerId,
                                        Map<Currency, Long> refund, UUID sellerId,
                                        byte[] itemSnapshot, boolean recoverItem) {
        return db.submit(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = ? WHERE transaction_key = ? AND state = 'PREPARED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            for (Map.Entry<Currency, Long> entry : refund.entrySet()) {
                deposit(connection, buyerId, entry.getKey(), entry.getValue());
            }
            if (recoverItem) {
                addRecovery(connection, transactionId, sellerId, itemSnapshot);
            }
            return true;
        }));
    }

    CompletableFuture<List<ShopStore.PendingDelivery>> pendingDeliveries(UUID buyerId) {
        return db.submit(connection -> {
            List<ShopStore.PendingDelivery> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT transaction_key, shop_id, item_snapshot FROM transactions WHERE buyer_uuid = ? AND state = 'COMPLETED' AND transaction_key IS NOT NULL AND item_snapshot IS NOT NULL ORDER BY completed_at")) {
                statement.setString(1, buyerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        pending.add(new ShopStore.PendingDelivery(result.getString(1), result.getLong(2), result.getBytes(3)));
                    }
                }
            }
            return pending;
        });
    }

    CompletableFuture<List<String>> rolledBackTransactions(UUID buyerId) {
        return db.submit(connection -> {
            List<String> transactions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT transaction_key FROM transactions WHERE buyer_uuid = ? AND state = 'ROLLED_BACK' AND transaction_key IS NOT NULL")) {
                statement.setString(1, buyerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        transactions.add(result.getString(1));
                    }
                }
            }
            return transactions;
        });
    }

    CompletableFuture<List<ShopStore.PreparedTransaction>> preparedTransactions() {
        return db.submit(connection -> {
            List<ShopStore.PreparedTransaction> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT transaction_key, shop_id, buyer_uuid, seller_uuid, item_slot, item_snapshot, diamond_ore_amount, deepslate_diamond_ore_amount FROM transactions WHERE state = 'PREPARED' AND transaction_key IS NOT NULL AND item_snapshot IS NOT NULL ORDER BY created_at");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    EnumMap<Currency, Long> payment = new EnumMap<>(Currency.class);
                    payment.put(Currency.DIAMOND_ORE, result.getLong("diamond_ore_amount"));
                    payment.put(Currency.DEEPSLATE_DIAMOND_ORE, result.getLong("deepslate_diamond_ore_amount"));
                    pending.add(new ShopStore.PreparedTransaction(result.getString("transaction_key"),
                            result.getLong("shop_id"), UUID.fromString(result.getString("buyer_uuid")),
                            UUID.fromString(result.getString("seller_uuid")), result.getInt("item_slot"),
                            result.getBytes("item_snapshot"), payment));
                }
            }
            return pending;
        });
    }

    CompletableFuture<Boolean> recoverRollback(ShopStore.PreparedTransaction transaction,
                                                boolean recoverItem) {
        return db.submit(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = ? WHERE transaction_key = ? AND state = 'PREPARED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transaction.transactionId());
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            for (Map.Entry<Currency, Long> entry : transaction.payment().entrySet()) {
                deposit(connection, transaction.buyerId(), entry.getKey(), entry.getValue());
            }
            if (recoverItem) {
                addRecovery(connection, transaction.transactionId(), transaction.sellerId(),
                        transaction.itemSnapshot());
            }
            return true;
        }));
    }

    CompletableFuture<List<ShopStore.RecoveryItem>> recoveryItems(UUID playerId) {
        return db.submit(connection -> {
            List<ShopStore.RecoveryItem> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, item_snapshot FROM item_recovery WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(new ShopStore.RecoveryItem(result.getLong(1), result.getBytes(2)));
                    }
                }
            }
            return items;
        });
    }

    CompletableFuture<Void> recoveryDelivered(long id) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE item_recovery SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Boolean> recoveryDelivered(long id, UUID playerId) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE item_recovery SET delivered_at = ? WHERE id = ? AND player_uuid = ? AND delivered_at IS NULL")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, id);
                statement.setString(3, playerId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    private void deposit(Connection connection, UUID playerId, Currency currency, long amount) throws SQLException {
        if (amount < 1) {
            return;
        }
        String column = currency == Currency.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO balances(player_uuid, diamond_ore, deepslate_diamond_ore, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET " + column + " = " + column + " + excluded." + column + ", updated_at = excluded.updated_at WHERE " + column + " <= ? - excluded." + column)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, currency == Currency.DIAMOND_ORE ? amount : 0);
            statement.setLong(3, currency == Currency.DEEPSLATE_DIAMOND_ORE ? amount : 0);
            statement.setLong(4, System.currentTimeMillis());
            statement.setLong(5, Long.MAX_VALUE);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Переполнение баланса");
            }
        }
    }

    private void addRecovery(Connection connection, String transactionId, UUID playerId,
                             byte[] itemSnapshot) throws SQLException {
        if (playerId == null || itemSnapshot == null) {
            throw new SQLException("Отсутствуют данные для восстановления предмета");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO item_recovery(transaction_id, player_uuid, item_snapshot, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(transaction_id) DO NOTHING")) {
            statement.setString(1, transactionId);
            statement.setString(2, playerId.toString());
            statement.setBytes(3, itemSnapshot);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private <T> T inTransaction(Connection connection, SqlAction<T> action) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                exception.addSuppressed(rollbackError);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }
}
