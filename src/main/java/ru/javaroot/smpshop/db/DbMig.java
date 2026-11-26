package ru.javaroot.smpshop.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class DbMig {
    private static final int LATEST = 11;

    void apply(Connection c, Path dbFile) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        }
        int current = currentVersion(c);
        if (current > LATEST) {
            throw new SQLException("Версия схемы БД новее плагина: " + current);
        }
        if (current > 0 && current < LATEST) {
            backup(c, dbFile);
        }
        for (int v = current + 1; v <= LATEST; v++) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                applyVersion(c, v);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, v);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private int currentVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM schema_migrations");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void applyVersion(Connection c, int version) throws SQLException {
        try (Statement st = c.createStatement()) {
            switch (version) {
                case 1 -> st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS shops (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, world_uuid TEXT NOT NULL, x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL, x2 INTEGER, y2 INTEGER, z2 INTEGER, price INTEGER NOT NULL, earned_total INTEGER NOT NULL DEFAULT 0, sold_slots INTEGER NOT NULL DEFAULT 0, sold_items INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, deleted_at INTEGER)");
                case 2 -> st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS balances (player_uuid TEXT PRIMARY KEY, diamond_ore INTEGER NOT NULL DEFAULT 0, deepslate_diamond_ore INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
                case 3 -> st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, amount INTEGER NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER)");
                case 4 -> {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS social_links (player_uuid TEXT NOT NULL, platform TEXT NOT NULL, external_id TEXT NOT NULL, external_name TEXT, linked_at INTEGER NOT NULL, PRIMARY KEY(player_uuid, platform), UNIQUE(platform, external_id))");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS link_codes (code_hash TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, platform TEXT NOT NULL, expires_at INTEGER NOT NULL, used_at INTEGER)");
                }
                case 5 -> st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, shop_id INTEGER NOT NULL, buyer_uuid TEXT NOT NULL, seller_uuid TEXT NOT NULL, state TEXT NOT NULL, price INTEGER NOT NULL, diamond_ore_amount INTEGER NOT NULL, deepslate_diamond_ore_amount INTEGER NOT NULL, item_slot INTEGER NOT NULL, item_snapshot BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)");
                case 6 -> st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS discord_webhooks (player_uuid TEXT PRIMARY KEY, webhook_url TEXT NOT NULL, updated_at INTEGER NOT NULL)");
                case 7 -> {
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN transaction_key TEXT");
                    st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS transactions_transaction_key ON transactions(transaction_key)");
                }
                case 8 -> {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS balance_ledger (operation_id TEXT NOT NULL, source TEXT NOT NULL, request_hash TEXT NOT NULL, operation_type TEXT NOT NULL, player_uuid TEXT NOT NULL, target_uuid TEXT, currency TEXT NOT NULL, amount INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(operation_id, source))");
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS balance_ledger_player ON balance_ledger(player_uuid, created_at)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS api_shop_operations (operation_id TEXT NOT NULL, source TEXT NOT NULL, request_hash TEXT NOT NULL, operation_type TEXT NOT NULL, status TEXT NOT NULL, shop_id INTEGER, created_at INTEGER NOT NULL, PRIMARY KEY(operation_id, source))");
                    st.executeUpdate("CREATE TRIGGER IF NOT EXISTS balances_nonnegative_insert BEFORE INSERT ON balances WHEN NEW.diamond_ore < 0 OR NEW.deepslate_diamond_ore < 0 BEGIN SELECT RAISE(ABORT, 'negative balance'); END");
                    st.executeUpdate("CREATE TRIGGER IF NOT EXISTS balances_nonnegative_update BEFORE UPDATE ON balances WHEN NEW.diamond_ore < 0 OR NEW.deepslate_diamond_ore < 0 BEGIN SELECT RAISE(ABORT, 'negative balance'); END");
                }
                case 9 -> {
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN item_amount INTEGER NOT NULL DEFAULT 0");
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN delivered_at INTEGER");
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN rolled_back_at INTEGER");
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN seller_diamond_ore INTEGER NOT NULL DEFAULT 0");
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN seller_deepslate_diamond_ore INTEGER NOT NULL DEFAULT 0");
                    st.executeUpdate("ALTER TABLE transactions ADD COLUMN adjustments_json TEXT NOT NULL DEFAULT '[]'");
                    st.executeUpdate("ALTER TABLE notifications ADD COLUMN transaction_id TEXT");
                    st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS notifications_transaction_id ON notifications(transaction_id) WHERE transaction_id IS NOT NULL");
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS transactions_recovery_state ON transactions(state, created_at)");
                }
                case 10 -> {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS item_recovery (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT NOT NULL UNIQUE, player_uuid TEXT NOT NULL, item_snapshot BLOB NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER)");
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS item_recovery_player ON item_recovery(player_uuid, delivered_at)");
                }
                case 11 -> {
                    st.executeUpdate("UPDATE transactions SET state = 'DELIVERED', delivered_at = COALESCE(completed_at, created_at) WHERE state = 'COMPLETED' AND item_snapshot IS NULL");
                    st.executeUpdate("UPDATE transactions SET state = 'ROLLED_BACK', rolled_back_at = created_at WHERE state = 'PREPARED' AND (transaction_key IS NULL OR item_snapshot IS NULL)");
                }
                default -> throw new SQLException("Неизвестная миграция БД: " + version);
            }
        }
    }

    private void backup(Connection c, Path dbFile) throws SQLException {
        String name = dbFile.getFileName() + ".before-v" + LATEST + "-" + System.currentTimeMillis() + ".db";
        String target = dbFile.resolveSibling(name).toAbsolutePath().toString().replace("'", "''");
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM main INTO '" + target + "'");
        }
    }
}
