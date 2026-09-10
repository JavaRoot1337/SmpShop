package ru.javaroot.data;

import ru.javaroot.shop.Shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class ShopRepo {
    private final Sqlite db;

    ShopRepo(Sqlite db) {
        this.db = db;
    }

    CompletableFuture<List<Shop>> loadAll() {
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

    CompletableFuture<Long> insert(Shop shop) {
        return db.submit(connection -> insert(connection, shop));
    }

    CompletableFuture<Void> delete(long id) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE shops SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setLong(2, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<ShopStore.ApiShopResult> apiCreate(UUID operationId, String source,
                                                          String requestHash, Shop shop) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ShopStore.ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
            if (prior != null) {
                return prior;
            }
            long id = insert(connection, shop);
            Shop created = new Shop(id, shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(), shop.price(),
                    shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt(), true);
            recordApiOperation(connection, operationId, source, requestHash, "CREATE", "SUCCESS", id);
            return new ShopStore.ApiShopResult("SUCCESS", created, true);
        }));
    }

    CompletableFuture<ShopStore.ApiShopResult> apiResult(UUID operationId, String source,
                                                          String requestHash) {
        return db.submit(connection -> apiOperation(connection, operationId, source, requestHash));
    }

    CompletableFuture<ShopStore.ApiShopResult> apiStatus(UUID operationId, String source,
                                                          String requestHash, String type,
                                                          String status, Long shopId) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ShopStore.ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
            if (prior != null) {
                return prior;
            }
            recordApiOperation(connection, operationId, source, requestHash, type, status, shopId);
            return new ShopStore.ApiShopResult(status, shopId == null ? null : readById(connection, shopId), true);
        }));
    }

    CompletableFuture<ShopStore.ApiShopResult> apiUpdatePrice(UUID operationId, String source,
                                                               String requestHash, long shopId, int price) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ShopStore.ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
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
            return new ShopStore.ApiShopResult(status, readById(connection, shopId), true);
        }));
    }

    CompletableFuture<ShopStore.ApiShopResult> apiDelete(UUID operationId, String source,
                                                          String requestHash, long shopId) {
        return db.submit(connection -> inTransaction(connection, () -> {
            ShopStore.ApiShopResult prior = apiOperation(connection, operationId, source, requestHash);
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
            return new ShopStore.ApiShopResult(status, shop, true);
        }));
    }

    private long insert(Connection connection, Shop shop) throws SQLException {
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
                    throw new SQLException("SQLite не вернул идентификатор магазина");
                }
                return keys.getLong(1);
            }
        }
    }

    private ShopStore.ApiShopResult apiOperation(Connection connection, UUID operationId,
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
                    return new ShopStore.ApiShopResult("CONFLICT", null, false);
                }
                long shopId = result.getLong("shop_id");
                return new ShopStore.ApiShopResult(result.getString("status"),
                        result.wasNull() ? null : readById(connection, shopId), false);
            }
        }
    }

    private void recordApiOperation(Connection connection, UUID operationId, String source,
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

    private Shop readById(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at FROM shops WHERE id = ? AND deleted_at IS NULL")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
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

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }
}
