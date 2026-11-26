package ru.javaroot.smpshop.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NotifyRepo {
    private static final int MAX_BATCH = 1000;
    private final Db db;

    public NotifyRepo(Db db) {
        this.db = db;
    }

    public CompletableFuture<Void> add(UUID playerId, long amount, UUID txId) {
        if (playerId == null || txId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Не заданы участники уведомления"));
        }
        if (amount < 1) {
            return CompletableFuture.completedFuture(null);
        }
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO notifications(player_uuid, amount, created_at, delivered_at, transaction_id) VALUES (?, ?, ?, NULL, ?) ON CONFLICT(transaction_id) DO NOTHING")) {
                ps.setString(1, playerId.toString());
                ps.setLong(2, amount);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, txId.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Pending> pending(UUID playerId) {
        return db.submit(c -> {
            List<Long> ids = new ArrayList<>();
            long total = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, amount FROM notifications WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id LIMIT " + MAX_BATCH)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong("id"));
                        try {
                            total = Math.addExact(total, rs.getLong("amount"));
                        } catch (ArithmeticException ex) {
                            throw new SQLException("Переполнение суммы уведомлений", ex);
                        }
                    }
                }
            }
            return new Pending(ids, total);
        });
    }

    public CompletableFuture<Void> markDelivered(List<Long> ids) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (ids.size() > MAX_BATCH) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Слишком много уведомлений в пакете"));
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE notifications SET delivered_at = ? WHERE id IN (" + placeholders + ") AND delivered_at IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(i + 2, ids.get(i));
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    public record Pending(List<Long> ids, long amount) {
        public Pending {
            ids = List.copyOf(ids);
        }
    }
}
