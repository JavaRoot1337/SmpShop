// code team-dev
package ru.javaroot.data;

import ru.javaroot.shop.Shop;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        return db.submit(connection -> {
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
                        throw new IllegalStateException("SQLite did not return shop id");
                    }
                    return keys.getLong(1);
                }
            }
        });
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

    public CompletableFuture<Void> recordSale(long shopId, long amount, int items) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE shops SET earned_total = earned_total + ?, sold_slots = sold_slots + 1, sold_items = sold_items + ? WHERE id = ? AND deleted_at IS NULL")) {
                statement.setLong(1, amount);
                statement.setInt(2, items);
                statement.setLong(3, shopId);
                statement.executeUpdate();
            }
            return null;
        });
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
}
