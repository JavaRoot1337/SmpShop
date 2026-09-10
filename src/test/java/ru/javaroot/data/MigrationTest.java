package ru.javaroot.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationTest {
    @Test
    void appliesAllVersionsAndCanRunAgain() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new Migration().apply(connection, java.nio.file.Path.of("memory.db"));
            new Migration().apply(connection, java.nio.file.Path.of("memory.db"));

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                result.next();
                assertEquals(11, result.getInt(1));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA table_info(transactions)")) {
                boolean found = false;
                while (result.next()) {
                    if ("item_snapshot".equals(result.getString("name"))) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found);
            }
        }
    }

    @Test
    void excludesLegacyCompletedSalesFromDelivery(@TempDir Path dir) throws Exception {
        Path database = dir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE transactions (state TEXT NOT NULL, item_snapshot BLOB, completed_at INTEGER, created_at INTEGER NOT NULL, delivered_at INTEGER, rolled_back_at INTEGER, transaction_key TEXT)");
                for (int version = 1; version <= 10; version++) {
                    statement.executeUpdate("INSERT INTO schema_migrations VALUES (" + version + ", 1)");
                }
                statement.executeUpdate("INSERT INTO transactions(state, created_at, completed_at) VALUES ('COMPLETED', 1, 2)");
            }

            new Migration().apply(connection, database);

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT state, delivered_at FROM transactions")) {
                result.next();
                assertEquals("DELIVERED", result.getString(1));
                assertEquals(2, result.getLong(2));
            }
        }
    }

    @Test
    void rejectsDatabaseSchemaFromTheFuture() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
                statement.executeUpdate("INSERT INTO schema_migrations VALUES (12, 1)");
            }

            assertThrows(java.sql.SQLException.class,
                    () -> new Migration().apply(connection, Path.of("future.db")));
        }
    }
}
