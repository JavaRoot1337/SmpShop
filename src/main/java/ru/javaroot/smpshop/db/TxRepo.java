package ru.javaroot.smpshop.db;

import ru.javaroot.smpshop.shop.CurType;
import ru.javaroot.smpshop.shop.Shop;

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

public final class TxRepo {
    private static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    private static final int MAX_ADJ_CHARS = 64 * 1024;
    private final Db db;

    public TxRepo(Db db) {
        this.db = db;
    }

    public CompletableFuture<Void> prepareSale(String txId, Shop shop, UUID buyerId, int slot, int amount,
                                                 long price, Map<CurType, Long> pay, Map<CurType, Long> payout,
                                                 byte[] snapshot, String adjJson) {
        if (txId == null || txId.isBlank() || txId.length() > 128
                || shop == null || buyerId == null || slot < 0 || amount < 1 || amount > 64
                || price < 1 || snapshot == null || snapshot.length > MAX_SNAPSHOT_BYTES
                || adjJson == null || adjJson.length() > MAX_ADJ_CHARS
                || !validAmounts(pay) || !validAmounts(payout)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Неверные параметры сделки"));
        }
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(transaction_key, shop_id, buyer_uuid, seller_uuid, state, price, diamond_ore_amount, deepslate_diamond_ore_amount, item_slot, item_snapshot, item_amount, seller_diamond_ore, seller_deepslate_diamond_ore, adjustments_json, created_at) VALUES (?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(transaction_key) DO NOTHING")) {
                ps.setString(1, txId);
                ps.setLong(2, shop.id());
                ps.setString(3, buyerId.toString());
                ps.setString(4, shop.ownerId().toString());
                ps.setLong(5, price);
                ps.setLong(6, pay.getOrDefault(CurType.DIAMOND_ORE, 0L));
                ps.setLong(7, pay.getOrDefault(CurType.DEEPSLATE_DIAMOND_ORE, 0L));
                ps.setInt(8, slot);
                ps.setBytes(9, snapshot);
                ps.setInt(10, amount);
                ps.setLong(11, payout.getOrDefault(CurType.DIAMOND_ORE, 0L));
                ps.setLong(12, payout.getOrDefault(CurType.DEEPSLATE_DIAMOND_ORE, 0L));
                ps.setString(13, adjJson);
                ps.setLong(14, System.currentTimeMillis());
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("ID сделки уже использован: " + txId);
                }
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> completeSale(String txId, Shop shop, int items, Map<CurType, Long> payout) {
        if (txId == null || txId.isBlank() || txId.length() > 128
                || shop == null || items < 1 || items > 64 || !validAmounts(payout)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Неверные данные завершения сделки"));
        }
        return db.submit(c -> db.tx(c, () -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET state = 'COMPLETED', completed_at = ? WHERE transaction_key = ? AND state = 'PREPARED' AND shop_id = ? AND seller_uuid = ? AND price = ? AND item_amount = ? AND seller_diamond_ore = ? AND seller_deepslate_diamond_ore = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, txId);
                ps.setLong(3, shop.id());
                ps.setString(4, shop.ownerId().toString());
                ps.setLong(5, shop.price());
                ps.setInt(6, items);
                ps.setLong(7, payout.getOrDefault(CurType.DIAMOND_ORE, 0L));
                ps.setLong(8, payout.getOrDefault(CurType.DEEPSLATE_DIAMOND_ORE, 0L));
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE shops SET earned_total = earned_total + ?, sold_slots = sold_slots + 1, sold_items = sold_items + ? WHERE id = ? AND deleted_at IS NULL AND earned_total <= ? AND sold_slots < ? AND sold_items <= ?")) {
                ps.setLong(1, shop.price());
                ps.setInt(2, items);
                ps.setLong(3, shop.id());
                ps.setLong(4, Long.MAX_VALUE - shop.price());
                ps.setLong(5, Long.MAX_VALUE);
                ps.setLong(6, Long.MAX_VALUE - items);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Магазин удалён или переполнена статистика");
                }
            }
            for (Map.Entry<CurType, Long> entry : payout.entrySet()) {
                depositSync(c, shop.ownerId(), entry.getKey(), entry.getValue());
            }
            return true;
        }));
    }

    public CompletableFuture<Boolean> delivered(String txId, UUID buyerId) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET state = 'DELIVERED', delivered_at = ? WHERE transaction_key = ? AND buyer_uuid = ? AND state = 'COMPLETED'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, txId);
                ps.setString(3, buyerId.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> rollback(String txId, UUID buyerId, boolean recoverItem) {
        return db.submit(c -> db.tx(c, () -> {
            UUID sellerId;
            byte[] snapshot;
            long diamondRefund;
            long deepslateRefund;
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT seller_uuid, item_snapshot, diamond_ore_amount, deepslate_diamond_ore_amount FROM transactions WHERE transaction_key = ? AND buyer_uuid = ? AND state = 'PREPARED'")) {
                q.setString(1, txId);
                q.setString(2, buyerId.toString());
                try (ResultSet rs = q.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    sellerId = UUID.fromString(rs.getString("seller_uuid"));
                    snapshot = rs.getBytes("item_snapshot");
                    diamondRefund = rs.getLong("diamond_ore_amount");
                    deepslateRefund = rs.getLong("deepslate_diamond_ore_amount");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = ? WHERE transaction_key = ? AND buyer_uuid = ? AND state = 'PREPARED'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, txId);
                ps.setString(3, buyerId.toString());
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            depositSync(c, buyerId, CurType.DIAMOND_ORE, diamondRefund);
            depositSync(c, buyerId, CurType.DEEPSLATE_DIAMOND_ORE, deepslateRefund);
            if (recoverItem) {
                addRecoverySync(c, txId, sellerId, snapshot);
            }
            return true;
        }));
    }

    public CompletableFuture<List<PendingDelivery>> pendingDeliveries(UUID buyerId) {
        return db.submit(c -> {
            List<PendingDelivery> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT transaction_key, shop_id, item_snapshot FROM transactions WHERE buyer_uuid = ? AND state = 'COMPLETED' AND transaction_key IS NOT NULL AND item_snapshot IS NOT NULL ORDER BY completed_at")) {
                ps.setString(1, buyerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PendingDelivery(rs.getString(1), rs.getLong(2), rs.getBytes(3)));
                    }
                }
            }
            return list;
        });
    }

    public CompletableFuture<List<String>> rolledBackTransactions(UUID buyerId) {
        return db.submit(c -> {
            List<String> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT transaction_key FROM transactions WHERE buyer_uuid = ? AND state = 'ROLLED_BACK' AND transaction_key IS NOT NULL")) {
                ps.setString(1, buyerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                }
            }
            return list;
        });
    }

    public CompletableFuture<List<PreparedTx>> preparedTransactions() {
        return db.submit(c -> {
            List<PreparedTx> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT transaction_key, shop_id, buyer_uuid, seller_uuid, item_slot, item_snapshot, diamond_ore_amount, deepslate_diamond_ore_amount FROM transactions WHERE state = 'PREPARED' AND transaction_key IS NOT NULL AND item_snapshot IS NOT NULL ORDER BY created_at");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EnumMap<CurType, Long> pay = new EnumMap<>(CurType.class);
                    pay.put(CurType.DIAMOND_ORE, rs.getLong("diamond_ore_amount"));
                    pay.put(CurType.DEEPSLATE_DIAMOND_ORE, rs.getLong("deepslate_diamond_ore_amount"));
                    list.add(new PreparedTx(
                            rs.getString("transaction_key"),
                            rs.getLong("shop_id"),
                            UUID.fromString(rs.getString("buyer_uuid")),
                            UUID.fromString(rs.getString("seller_uuid")),
                            rs.getInt("item_slot"),
                            rs.getBytes("item_snapshot"),
                            pay
                    ));
                }
            }
            return list;
        });
    }

    public CompletableFuture<Boolean> recoverRollback(PreparedTx tx, boolean recoverItem) {
        return db.submit(c -> db.tx(c, () -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = ? WHERE transaction_key = ? AND buyer_uuid = ? AND state = 'PREPARED'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, tx.txId());
                ps.setString(3, tx.buyerId().toString());
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            for (Map.Entry<CurType, Long> entry : tx.payment().entrySet()) {
                depositSync(c, tx.buyerId(), entry.getKey(), entry.getValue());
            }
            if (recoverItem) {
                addRecoverySync(c, tx.txId(), tx.sellerId(), tx.itemSnapshot());
            }
            return true;
        }));
    }

    public CompletableFuture<List<RecoveryItem>> recoveryItems(UUID playerId) {
        return db.submit(c -> {
            List<RecoveryItem> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, item_snapshot FROM item_recovery WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id")) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new RecoveryItem(rs.getLong(1), rs.getBytes(2)));
                    }
                }
            }
            return list;
        });
    }

    public CompletableFuture<Boolean> recoveryDelivered(long id, UUID playerId) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE item_recovery SET delivered_at = ? WHERE id = ? AND player_uuid = ? AND delivered_at IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                ps.setString(3, playerId.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static void depositSync(Connection c, UUID playerId, CurType cur, long amount) throws SQLException {
        if (amount < 1) {
            return;
        }
        String col = cur == CurType.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO balances(player_uuid, diamond_ore, deepslate_diamond_ore, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET "
                        + col + " = " + col + " + excluded." + col + ", updated_at = excluded.updated_at WHERE " + col + " <= ? - excluded." + col)) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, cur == CurType.DIAMOND_ORE ? amount : 0);
            ps.setLong(3, cur == CurType.DEEPSLATE_DIAMOND_ORE ? amount : 0);
            ps.setLong(4, System.currentTimeMillis());
            ps.setLong(5, Long.MAX_VALUE);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Переполнение баланса при возврате");
            }
        }
    }

    private static boolean validAmounts(Map<CurType, Long> amounts) {
        if (amounts == null) {
            return false;
        }
        for (Long val : amounts.values()) {
            if (val == null || val < 0) {
                return false;
            }
        }
        return true;
    }

    private static void addRecoverySync(Connection c, String txId, UUID playerId, byte[] snapshot) throws SQLException {
        if (playerId == null || snapshot == null) {
            throw new SQLException("Нет снимка предмета для возврата");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO item_recovery(transaction_id, player_uuid, item_snapshot, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(transaction_id) DO NOTHING")) {
            ps.setString(1, txId);
            ps.setString(2, playerId.toString());
            ps.setBytes(3, snapshot);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public record PendingDelivery(String txId, long shopId, byte[] itemSnapshot) {
        public PendingDelivery {
            itemSnapshot = itemSnapshot.clone();
        }

        @Override
        public byte[] itemSnapshot() {
            return itemSnapshot.clone();
        }
    }

    public record PreparedTx(String txId, long shopId, UUID buyerId, UUID sellerId,
                             int itemSlot, byte[] itemSnapshot, Map<CurType, Long> payment) {
        public PreparedTx {
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
}
