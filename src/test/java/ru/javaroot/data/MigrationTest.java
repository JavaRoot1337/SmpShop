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
            new Migration().apply(connection);
            new Migration().apply(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                result.next();
                assertEquals(7, result.getInt(1));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA table_info(transactions)")) {
                boolean found = false;
                while (result.next()) {
                    if ("transaction_key".equals(result.getString("name"))) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found);
            }
        }
    }
}
