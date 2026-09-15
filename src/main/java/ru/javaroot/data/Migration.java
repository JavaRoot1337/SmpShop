package ru.javaroot.data;

import ru.javaroot.config.DbLoc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;

class Migration {
    private static final int LATEST_VERSION = 11;
    private final DbLoc messages;

    Migration() {
        this(null);
    }

    Migration(DbLoc messages) {
        this.messages = messages;
    }

    public void apply(Connection connection, Path databaseFile) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        }
        int current = currentVersion(connection);
        if (current > LATEST_VERSION) {
            throw new SQLException(message("migration.schema-too-new", java.util.Map.of("version", current)));
        }
        if (current > 0 && current < LATEST_VERSION) {
            backup(connection, databaseFile);
        }
        for (int version = current + 1; version <= LATEST_VERSION; version++) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                applyVersion(connection, version);
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                    statement.setInt(1, version);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
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
                case 1 -> statement.executeUpdate("CREATE TABLE IF NOT EXISTS shops (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, world_uuid TEXT NOT NULL, x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER, y2 INTEGER, z2 INTEGER, price INTEGER NOT NULL, earned_total INTEGER NOT NULL DEFAULT 0, sold_slots INTEGER NOT NULL DEFAULT 0, sold_items INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, deleted_at INTEGER)");
                case 2 -> statement.executeUpdate("CREATE TABLE IF NOT EXISTS balances (player_uuid TEXT PRIMARY KEY, diamond_ore INTEGER NOT NULL DEFAULT 0, deepslate_diamond_ore INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
                case 3 -> statement.executeUpdate("CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, amount INTEGER NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER)");
                case 4 -> {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS social_links (player_uuid TEXT NOT NULL, platform TEXT NOT NULL, external_id TEXT NOT NULL, external_name TEXT, linked_at INTEGER NOT NULL, PRIMARY KEY(player_uuid, platform), UNIQUE(platform, external_id))");
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS link_codes (code_hash TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, platform TEXT NOT NULL, expires_at INTEGER NOT NULL, used_at INTEGER)");
                }
                case 5 -> statement.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id INTEGER NOT NULL, buyer_uuid TEXT NOT NULL, seller_uuid TEXT NOT NULL, state TEXT NOT NULL, price INTEGER NOT NULL, diamond_ore_amount INTEGER NOT NULL, deepslate_diamond_ore_amount INTEGER NOT NULL, item_slot INTEGER NOT NULL, item_snapshot BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)");
                case 6 -> statement.executeUpdate("CREATE TABLE IF NOT EXISTS discord_webhooks (player_uuid TEXT PRIMARY KEY, webhook_url TEXT NOT NULL, updated_at INTEGER NOT NULL)");
                case 7 -> {
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN transaction_key TEXT");
                    statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS transactions_transaction_key ON transactions(transaction_key)");
                }
                case 8 -> {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_ledger (operation_id TEXT NOT NULL, source TEXT NOT NULL, request_hash TEXT NOT NULL, operation_type TEXT NOT NULL, player_uuid TEXT NOT NULL, target_uuid TEXT, currency TEXT NOT NULL, amount INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(operation_id, source))");
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_ledger_player ON balance_ledger(player_uuid, created_at)");
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS api_shop_operations (operation_id TEXT NOT NULL, source TEXT NOT NULL, request_hash TEXT NOT NULL, operation_type TEXT NOT NULL, status TEXT NOT NULL, shop_id INTEGER, created_at INTEGER NOT NULL, PRIMARY KEY(operation_id, source))");
                    statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS balances_nonnegative_insert BEFORE INSERT ON balances WHEN NEW.diamond_ore < 0 OR NEW.deepslate_diamond_ore < 0 BEGIN SELECT RAISE(ABORT, 'negative balance'); END");
                    statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS balances_nonnegative_update BEFORE UPDATE ON balances WHEN NEW.diamond_ore < 0 OR NEW.deepslate_diamond_ore < 0 BEGIN SELECT RAISE(ABORT, 'negative balance'); END");
                }
                case 9 -> {
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN item_amount INTEGER NOT NULL DEFAULT 0");
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN delivered_at INTEGER");
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN rolled_back_at INTEGER");
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN seller_diamond_ore INTEGER NOT NULL DEFAULT 0");
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN seller_deepslate_diamond_ore INTEGER NOT NULL DEFAULT 0");
                    statement.executeUpdate("ALTER TABLE transactions ADD COLUMN adjustments_json TEXT NOT NULL DEFAULT '[]'");
                    statement.executeUpdate("ALTER TABLE notifications ADD COLUMN transaction_id TEXT");
                    statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS notifications_transaction_id ON notifications(transaction_id) WHERE transaction_id IS NOT NULL");
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS transactions_recovery_state ON transactions(state, created_at)");
                }
                case 10 -> {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS item_recovery (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT NOT NULL UNIQUE, player_uuid TEXT NOT NULL, item_snapshot BLOB NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER)");
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS item_recovery_player ON item_recovery(player_uuid, delivered_at)");
                }
                case 11 -> {
                    statement.executeUpdate("UPDATE transactions SET state = 'DELIVERED', delivered_at = COALESCE(completed_at, created_at) WHERE state = 'COMPLETED' AND item_snapshot IS NULL");
                    statement.executeUpdate("UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = created_at WHERE state = 'PREPARED' AND (transaction_key IS NULL OR item_snapshot IS NULL)");
                }
                default -> throw new SQLException(message("migration.unknown", java.util.Map.of("version", version)));
            }
        }
    }

    private void backup(Connection connection, Path databaseFile) throws SQLException {
        String name = databaseFile.getFileName() + ".before-v" + LATEST_VERSION + "-" + System.currentTimeMillis() + ".db";
        String target = databaseFile.resolveSibling(name).toAbsolutePath().toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM main INTO '" + target + "'");
        }
    }

    private String message(String key, java.util.Map<String, ?> values) {
        return messages == null ? key : messages.raw(key, values);
    }
}
