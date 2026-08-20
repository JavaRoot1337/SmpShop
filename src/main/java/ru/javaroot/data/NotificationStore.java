package ru.javaroot.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class NotificationStore {
    private final Sqlite db;

    public NotificationStore(Sqlite db) {
        this.db = db;
    }

    public CompletableFuture<Void> add(UUID playerId, long amount) {
        if (amount < 1) {
            return CompletableFuture.completedFuture(null);
        }
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO notifications(player_uuid, amount, created_at, delivered_at) VALUES (?, ?, ?, NULL)")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, amount);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Pending> pending(UUID playerId) {
        return db.submit(connection -> {
            List<Long> ids = new ArrayList<>();
            long amount = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, amount FROM notifications WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        ids.add(result.getLong("id"));
                        amount += result.getLong("amount");
                    }
                }
            }
            return new Pending(ids, amount);
        });
    }

    public CompletableFuture<Void> markDelivered(List<Long> ids) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notifications SET delivered_at = ? WHERE id IN (" + placeholders + ") AND delivered_at IS NULL")) {
                statement.setLong(1, System.currentTimeMillis());
                for (int index = 0; index < ids.size(); index++) {
                    statement.setLong(index + 2, ids.get(index));
                }
                statement.executeUpdate();
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
