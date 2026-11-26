package ru.javaroot.smpshop.db;

import ru.javaroot.smpshop.shop.Shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ShopRepo {
    private final Db db;

    public ShopRepo(Db db) {
        this.db = db;
    }

    public CompletableFuture<List<Shop>> loadAll() {
        return db.submit(c -> {
            List<Shop> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at FROM shops WHERE deleted_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(read(rs));
                }
            }
            return list;
        });
    }

    public CompletableFuture<Long> insert(Shop shop) {
        return db.submit(c -> insertSync(c, shop));
    }

    public CompletableFuture<Void> delete(long id) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shops SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<ApiShopRes> apiCreate(UUID opId, String src, String hash, Shop shop) {
        return db.submit(c -> db.tx(c, () -> {
            ApiShopRes prior = apiOp(c, opId, src, hash);
            if (prior != null) {
                return prior;
            }
            long id = insertSync(c, shop);
            Shop created = new Shop(id, shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(), shop.price(),
                    shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt());
            recordApiOp(c, opId, src, hash, "CREATE", "SUCCESS", id);
            return new ApiShopRes("SUCCESS", created, true);
        }));
    }

    public CompletableFuture<ApiShopRes> apiResult(UUID opId, String src, String hash) {
        return db.submit(c -> apiOp(c, opId, src, hash));
    }

    public CompletableFuture<ApiShopRes> apiStatus(UUID opId, String src, String hash,
                                                    String type, String status, Long shopId) {
        return db.submit(c -> db.tx(c, () -> {
            ApiShopRes prior = apiOp(c, opId, src, hash);
            if (prior != null) {
                return prior;
            }
            recordApiOp(c, opId, src, hash, type, status, shopId);
            return new ApiShopRes(status, shopId == null ? null : readById(c, shopId), true);
        }));
    }

    public CompletableFuture<ApiShopRes> apiUpdatePrice(UUID opId, String src, String hash, long shopId, int price) {
        return db.submit(c -> db.tx(c, () -> {
            ApiShopRes prior = apiOp(c, opId, src, hash);
            if (prior != null) {
                return prior;
            }
            String status;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shops SET price = ? WHERE id = ? AND deleted_at IS NULL")) {
                ps.setInt(1, price);
                ps.setLong(2, shopId);
                status = ps.executeUpdate() == 1 ? "SUCCESS" : "NOT_FOUND";
            }
            recordApiOp(c, opId, src, hash, "PRICE", status, shopId);
            return new ApiShopRes(status, readById(c, shopId), true);
        }));
    }

    public CompletableFuture<ApiShopRes> apiDelete(UUID opId, String src, String hash, long shopId) {
        return db.submit(c -> db.tx(c, () -> {
            ApiShopRes prior = apiOp(c, opId, src, hash);
            if (prior != null) {
                return prior;
            }
            Shop shop = readById(c, shopId);
            String status = "NOT_FOUND";
            if (shop != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE shops SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setLong(2, shopId);
                    status = ps.executeUpdate() == 1 ? "SUCCESS" : "NOT_FOUND";
                }
            }
            recordApiOp(c, opId, src, hash, "DELETE", status, shopId);
            return new ApiShopRes(status, shop, true);
        }));
    }

    private long insertSync(Connection c, Shop shop) throws SQLException {
        ensureFree(c, shop);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shops(owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, shop.ownerId().toString());
            ps.setString(2, shop.ownerName());
            ps.setString(3, shop.worldId().toString());
            ps.setInt(4, shop.x1());
            ps.setInt(5, shop.y1());
            ps.setInt(6, shop.z1());
            setInt(ps, 7, shop.x2());
            setInt(ps, 8, shop.y2());
            setInt(ps, 9, shop.z2());
            ps.setInt(10, shop.price());
            ps.setLong(11, shop.earnedTotal());
            ps.setLong(12, shop.soldSlots());
            ps.setLong(13, shop.soldItems());
            ps.setLong(14, shop.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("SQLite не вернул ID магазина");
                }
                return keys.getLong(1);
            }
        }
    }

    private void ensureFree(Connection c, Shop shop) throws SQLException {
        if (shop.secondKey() != null && shop.firstKey().equals(shop.secondKey())) {
            throw new SQLException("Позиция сундука уже занята");
        }
        List<int[]> coords = new ArrayList<>();
        coords.add(new int[]{shop.x1(), shop.y1(), shop.z1()});
        if (shop.x2() != null) {
            coords.add(new int[]{shop.x2(), shop.y2(), shop.z2()});
        }
        StringBuilder sql = new StringBuilder("SELECT 1 FROM shops WHERE deleted_at IS NULL AND world_uuid = ? AND (");
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(x1 = ? AND y1 = ? AND z1 = ?) OR (x2 = ? AND y2 = ? AND z2 = ?)");
        }
        sql.append(") LIMIT 1");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, shop.worldId().toString());
            for (int[] pos : coords) {
                for (int v : pos) {
                    ps.setInt(idx++, v);
                }
                for (int v : pos) {
                    ps.setInt(idx++, v);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new SQLException("Позиция сундука уже занята");
                }
            }
        }
    }

    private ApiShopRes apiOp(Connection c, UUID opId, String src, String hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT request_hash, status, shop_id FROM api_shop_operations WHERE operation_id = ? AND source = ?")) {
            ps.setString(1, opId.toString());
            ps.setString(2, src);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                if (!hash.equals(rs.getString("request_hash"))) {
                    return new ApiShopRes("CONFLICT", null, false);
                }
                long shopId = rs.getLong("shop_id");
                return new ApiShopRes(rs.getString("status"), rs.wasNull() ? null : readById(c, shopId), false);
            }
        }
    }

    private void recordApiOp(Connection c, UUID opId, String src, String hash,
                             String type, String status, Long shopId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO api_shop_operations(operation_id, source, request_hash, operation_type, status, shop_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, opId.toString());
            ps.setString(2, src);
            ps.setString(3, hash);
            ps.setString(4, type);
            ps.setString(5, status);
            if (shopId == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, shopId);
            }
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private Shop readById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner_uuid, owner_name, world_uuid, x1, y1, z1, x2, y2, z2, price, earned_total, sold_slots, sold_items, created_at FROM shops WHERE id = ? AND deleted_at IS NULL")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private static Shop read(ResultSet rs) throws SQLException {
        return new Shop(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                UUID.fromString(rs.getString("world_uuid")),
                rs.getInt("x1"),
                rs.getInt("y1"),
                rs.getInt("z1"),
                intOrNull(rs, "x2"),
                intOrNull(rs, "y2"),
                intOrNull(rs, "z2"),
                rs.getInt("price"),
                rs.getLong("earned_total"),
                rs.getLong("sold_slots"),
                rs.getLong("sold_items"),
                rs.getLong("created_at")
        );
    }

    private static void setInt(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, val);
        }
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int val = rs.getInt(col);
        return rs.wasNull() ? null : val;
    }

    public record ApiShopRes(String status, Shop shop, boolean applied) {
    }
}
