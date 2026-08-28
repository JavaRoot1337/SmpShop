package ru.javaroot.data;

import ru.javaroot.shop.Shop;
import ru.javaroot.currency.Currency;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ShopStore {
    private final Sqlite db;

    public ShopStore(Sqlite db) {
        this.db = db;
    }

    public CompletableFuture<List<Shop>> loadAll() {
        return db.submit(connection -> {
            List<Shop> shops = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT id, owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at FROM shops WHERE deleted_at IS NULL");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    shops.add(read(result));
                }
            }
            return shops;
        });
    }

    public CompletableFuture<Long> insert(Shop shop) {
        return db.submit(connection -> insert(connection, shop));
    }

    public CompletableFuture<Void> delete(long id) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE shops SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<ApiShopResult> apiCreate(UUID operationId, String source,
                                                       String requestHash, Shop shop) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
            if (prior != null) {
                return prior;
            }
            long id = insert(connection, shop);
            Shop created = new Shop(id, shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(), shop.price(),
                    shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt(), true);
            recordApiOperation(connection, operationId, source, requestHash, "CREATE", "SUCCESS", id);
            return new ApiShopResult("SUCCESS", created, true);
        }));
    }

    public CompletableFuture<ApiShopResult> apiResult(UUID operationId, String source, String requestHash) {
        return db.submit(connection -> apiOperation(connection, operationId, source, requestHash));
    }

    public CompletableFuture<ApiShopResult> apiUpdatePrice(UUID operationId, String source,
                                                            String requestHash, long shopId, int price) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
            if (prior != null) {
                return prior;
            }
            String status;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE shops SET price = ? WHERE id = ? AND deleted_at IS NULL")) {
                statement.setInt(1, price);
                statement.setLong(2, shopId);
                status = statement.executeUpdate() == 1 ? "SUCCESS" : "NOT_FOUND";
            }
            recordApiOperation(connection, operationId, source, requestHash, "PRICE", status, shopId);
            return new ApiShopResult(status, readById(connection, shopId), true);
        }));
    }

    public CompletableFuture<ApiShopResult> apiDelete(UUID operationId, String source,
                                                       String requestHash, long shopId) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
            if (prior != null) {
                return prior;
            }
            Shop shop = readById(connection, shopId);
            String status = "NOT_FOUND";
            if (shop != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE shops SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setLong(2, shopId);
                    status = statement.executeUpdate() == 1 ? "SUCCESS" : "NOT_FOUND";
                }
            }
            recordApiOperation(connection, operationId, source, requestHash, "DELETE", status, shopId);
            return new ApiShopResult(status, shop, true);
        }));
    }

    public CompletableFuture<Void> prepareSale(String transactionId, Shop shop, UUID buyerId,
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
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> completeSale(String transactionId, Shop shop, int items,
                                                    Map<Currency, Long> sellerPayout) {
        return db.submit(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'COMPLETED', completed_at = ? WHERE transaction_key = ? AND state = 'PREPARED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
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
                    throw new SQLException("Shop is no longer active or statistics overflowed");
                }
            }
            for (Map.Entry<Currency, Long> entry : sellerPayout.entrySet()) {
                deposit(connection, shop.ownerId(), entry.getKey(), entry.getValue());
            }
            return true;
        }));
    }

    public CompletableFuture<Void> delivered(String transactionId) {
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

    public CompletableFuture<Void> rollback(String transactionId, UUID buyerId,
                                            Map<Currency, Long> refund) {
        return db.submit(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = ? WHERE transaction_key = ? AND state = 'PREPARED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transactionId);
                if (statement.executeUpdate() != 1) {
                    return null;
                }
            }
            for (Map.Entry<Currency, Long> entry : refund.entrySet()) {
                deposit(connection, buyerId, entry.getKey(), entry.getValue());
            }
            return null;
        }));
    }

    public CompletableFuture<List<PendingDelivery>> pendingDeliveries(UUID buyerId) {
        return db.submit(connection -> {
            List<PendingDelivery> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT transaction_key, shop_id, item_snapshot FROM transactions WHERE buyer_uuid = ? AND state = 'COMPLETED' ORDER BY completed_at")) {
                statement.setString(1, buyerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        pending.add(new PendingDelivery(result.getString(1), result.getLong(2), result.getBytes(3)));
                    }
                }
            }
            return pending;
        });
    }

    public CompletableFuture<List<PreparedTransaction>> preparedTransactions() {
        return db.submit(connection -> {
            List<PreparedTransaction> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT transaction_key, shop_id, buyer_uuid, seller_uuid, item_slot, item_snapshot, diamond_ore_amount, deepslate_diamond_ore_amount FROM transactions WHERE state = 'PREPARED' ORDER BY created_at");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    EnumMap<Currency, Long> payment = new EnumMap<>(Currency.class);
                    payment.put(Currency.DIAMOND_ORE, result.getLong("diamond_ore_amount"));
                    payment.put(Currency.DEEPSLATE_DIAMOND_ORE, result.getLong("deepslate_diamond_ore_amount"));
                    pending.add(new PreparedTransaction(result.getString("transaction_key"),
                            result.getLong("shop_id"), UUID.fromString(result.getString("buyer_uuid")),
                            UUID.fromString(result.getString("seller_uuid")), result.getInt("item_slot"),
                            result.getBytes("item_snapshot"), payment));
                }
            }
            return pending;
        });
    }

    public CompletableFuture<Void> recoverRollback(PreparedTransaction transaction, boolean recoverItem) {
        return db.submit(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = ? WHERE transaction_key = ? AND state = 'PREPARED'")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, transaction.transactionId());
                if (statement.executeUpdate() != 1) {
                    return null;
                }
            }
            for (Map.Entry<Currency, Long> entry : transaction.payment().entrySet()) {
                deposit(connection, transaction.buyerId(), entry.getKey(), entry.getValue());
            }
            if (recoverItem) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO item_recovery(transaction_id, player_uuid, item_snapshot, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(transaction_id) DO NOTHING")) {
                    statement.setString(1, transaction.transactionId());
                    statement.setString(2, transaction.sellerId().toString());
                    statement.setBytes(3, transaction.itemSnapshot());
                    statement.setLong(4, System.currentTimeMillis());
                    statement.executeUpdate();
                }
            }
            return null;
        }));
    }

    public CompletableFuture<List<RecoveryItem>> recoveryItems(UUID playerId) {
        return db.submit(connection -> {
            List<RecoveryItem> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, item_snapshot FROM item_recovery WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(new RecoveryItem(result.getLong(1), result.getBytes(2)));
                    }
                }
            }
            return items;
        });
    }

    public CompletableFuture<Void> recoveryDelivered(long id) {
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

    private void deposit(java.sql.Connection connection, UUID playerId, Currency currency, long amount) throws SQLException {
        if (amount < 1) {
            return;
        }
        String column = currency == Currency.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO balances(player_uuid, diamond_ore, deepslate_diamond_ore, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET " + column + " = " + column + " + excluded." + column + ", updated_at = excluded.updated_at")) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, currency == Currency.DIAMOND_ORE ? amount : 0);
            statement.setLong(3, currency == Currency.DEEPSLATE_DIAMOND_ORE ? amount : 0);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private long insert(java.sql.Connection connection, Shop shop) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO shops(owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, shop.ownerId().toString());
            statement.setString(2, shop.ownerName());
            statement.setString(3, shop.worldId().toString());
            statement.setInt(4, shop.x1());
            statement.setInt(5, shop.y1());
            statement.setInt(6, shop.z1());
            setInteger(statement, 7, shop.x2());
            setInteger(statement, 8, shop.y2());
            setInteger(statement, 9, shop.z2());
            statement.setInt(10, shop.price());
            statement.setLong(11, shop.earnedTotal());
            statement.setLong(12, shop.soldSlots());
            statement.setLong(13, shop.soldItems());
            statement.setLong(14, shop.createdAt());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("SQLite did not return shop id");
                }
                return keys.getLong(1);
            }
        }
    }

    private ApiShopResult apiOperation(java.sql.Connection connection, UUID operationId,
                                       String source, String requestHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_hash, status, shop_id FROM api_shop_operations WHERE operation_id = ? AND source = ?")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, source);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                if (!requestHash.equals(result.getString("request_hash"))) {
                    return new ApiShopResult("CONFLICT", null, false);
                }
                long shopId = result.getLong("shop_id");
                return new ApiShopResult(result.getString("status"),
                        result.wasNull() ? null : readById(connection, shopId), false);
            }
        }
    }

    private void recordApiOperation(java.sql.Connection connection, UUID operationId, String source,
                                    String requestHash, String type, String status, Long shopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO api_shop_operations(operation_id, source, request_hash, operation_type, status, shop_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, source);
            statement.setString(3, requestHash);
            statement.setString(4, type);
            statement.setString(5, status);
            if (shopId == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, shopId);
            }
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private Shop readById(java.sql.Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at FROM shops WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private <T> T inTransaction(java.sql.Connection connection, SqlAction<T> action) throws SQLException {
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

    private Shop read(ResultSet result) throws SQLException {
        return new Shop(
                result.getLong("id"),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("owner_name"),
                UUID.fromString(result.getString("world_uuid")),
                result.getInt("x1"),
                result.getInt("y1"),
                result.getInt("z1"),
                integerOrNull(result, "x2"),
                integerOrNull(result, "y2"),
                integerOrNull(result, "z2"),
                result.getInt("price"),
                result.getLong("earned_total"),
                result.getLong("sold_slots"),
                result.getLong("sold_items"),
                result.getLong("created_at"),
                true
        );
    }

    private void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private Integer integerOrNull(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    public record ApiShopResult(String status, Shop shop, boolean applied) {
    }

    public record PendingDelivery(String transactionId, long shopId, byte[] itemSnapshot) {
        public PendingDelivery {
            itemSnapshot = itemSnapshot.clone();
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }

    public record PreparedTransaction(String transactionId, long shopId, UUID buyerId, UUID sellerId,
                                      int itemSlot, byte[] itemSnapshot, Map<Currency, Long> payment) {
        public PreparedTransaction {
            itemSnapshot = itemSnapshot.clone();
            payment = Map.copyOf(payment);
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }

    public record RecoveryItem(long id, byte[] itemSnapshot) {
        public RecoveryItem {
            itemSnapshot = itemSnapshot.clone();
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }
}
