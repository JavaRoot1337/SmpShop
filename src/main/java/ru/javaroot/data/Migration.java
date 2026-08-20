// code team-dev
package ru.javaroot.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Migration {
    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        }
        int current = currentVersion(connection);
        for (int version = current + 1; version <= 6; version++) {
            applyVersion(connection, version);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)") ) {
                statement.setInt(1, version);
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM schema_migrations");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void applyVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            switch (version) {
                case 1 -> statement.executeUpdate("CREATE TABLE shops (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, world_uuid TEXT NOT NULL, x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER, y2 INTEGER, z2 INTEGER, price INTEGER NOT NULL, earned_total INTEGER NOT NULL DEFAULT 0, sold_slots INTEGER NOT NULL DEFAULT 0, sold_items INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, deleted_at INTEGER)");
                case 2 -> statement.executeUpdate("CREATE TABLE balances (player_uuid TEXT PRIMARY KEY, diamond_ore INTEGER NOT NULL DEFAULT 0, deepslate_diamond_ore INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
                case 3 -> statement.executeUpdate("CREATE TABLE notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, amount INTEGER NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER)");
                case 4 -> {
                    statement.executeUpdate("CREATE TABLE social_links (player_uuid TEXT NOT NULL, platform TEXT NOT NULL, external_id TEXT NOT NULL, external_name TEXT, linked_at INTEGER NOT NULL, PRIMARY KEY(player_uuid, platform), UNIQUE(platform, external_id))");
                    statement.executeUpdate("CREATE TABLE link_codes (code_hash TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, platform TEXT NOT NULL, expires_at INTEGER NOT NULL, used_at INTEGER)");
                }
                case 5 -> statement.executeUpdate("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id INTEGER NOT NULL, buyer_uuid TEXT NOT NULL, seller_uuid TEXT NOT NULL, state TEXT NOT NULL, price INTEGER NOT NULL, diamond_ore_amount INTEGER NOT NULL, deepslate_diamond_ore_amount INTEGER NOT NULL, item_slot INTEGER NOT NULL, item_snapshot BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)");
                case 6 -> statement.executeUpdate("CREATE TABLE discord_webhooks (player_uuid TEXT PRIMARY KEY, webhook_url TEXT NOT NULL, updated_at INTEGER NOT NULL)");
                default -> throw new SQLException("Unknown migration: " + version);
            }
        }
    }
}
