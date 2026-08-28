package ru.javaroot.data;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationTest {
    @Test
    void appliesAllVersionsAndCanRunAgain() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new Migration().apply(connection, java.nio.file.Path.of("memory.db"));
            new Migration().apply(connection, java.nio.file.Path.of("memory.db"));

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                result.next();
                assertEquals(10, result.getInt(1));
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
}
