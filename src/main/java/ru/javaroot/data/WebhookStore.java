package ru.javaroot.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class WebhookStore {
    private final Sqlite db;

    public WebhookStore(Sqlite db) {
        this.db = db;
    }

    public CompletableFuture<Map<UUID, String>> loadAll() {
        return db.submit(connection -> {
            Map<UUID, String> webhooks = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, webhook_url FROM discord_webhooks");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    webhooks.put(UUID.fromString(result.getString("player_uuid")), result.getString("webhook_url"));
                }
            }
            return webhooks;
        });
    }

    public CompletableFuture<Void> save(UUID playerId, String webhook) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO discord_webhooks(player_uuid, webhook_url, updated_at) VALUES (?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET webhook_url = excluded.webhook_url, updated_at = excluded.updated_at")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, webhook);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
