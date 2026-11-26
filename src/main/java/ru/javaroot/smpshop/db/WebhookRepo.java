package ru.javaroot.smpshop.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class WebhookRepo {
    private final Db db;

    public WebhookRepo(Db db) {
        this.db = db;
    }

    public CompletableFuture<Map<UUID, String>> loadAll() {
        return db.submit(c -> {
            Map<UUID, String> map = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, webhook_url FROM discord_webhooks");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(UUID.fromString(rs.getString("player_uuid")), rs.getString("webhook_url"));
                }
            }
            return map;
        });
    }

    public CompletableFuture<Void> save(UUID playerId, String webhook) {
        if (playerId == null || webhook == null || webhook.length() > 2048) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Неверный вебхук Discord"));
        }
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO discord_webhooks(player_uuid, webhook_url, updated_at) VALUES (?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET webhook_url = excluded.webhook_url, updated_at = excluded.updated_at")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, webhook);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
