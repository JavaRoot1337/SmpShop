// code team-dev
package ru.javaroot.data;

import org.bukkit.plugin.Plugin;
import org.sqlite.JDBC;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Sqlite {
    private final Plugin plugin;
    private final String fileName;
    private final int timeoutSeconds;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SMPSHOP-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;
    private Driver driver;

    public Sqlite(Plugin plugin, String fileName, int timeoutSeconds) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.timeoutSeconds = timeoutSeconds;
    }

    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                File file = new File(plugin.getDataFolder(), fileName);
                File parent = file.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) {
                    throw new SQLException("Cannot create plugin data folder");
                }
                driver = new JDBC();
                DriverManager.registerDriver(driver);
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.getPath());
                connection.setAutoCommit(true);
                configure(connection);
                new Migration().apply(connection);
            } catch (SQLException exception) {
                closeResources();
                throw new CompletionException(new IllegalStateException("SQLite initialization failed", exception));
            }
        });
    }

    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (connection == null || connection.isClosed()) {
                    throw new IllegalStateException("SQLite is not ready");
                }
                return work.run(connection);
            } catch (SQLException exception) {
                throw new IllegalStateException("SQLite operation failed", exception);
            }
        }, queue);
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + timeoutSeconds * 1000L);
        }
    }

    public void close() {
        if (!queue.isShutdown()) {
            queue.submit(() -> {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException exception) {
                        plugin.getLogger().warning("Не удалось закрыть SQLite: " + exception.getMessage());
                    }
                }
            });
            queue.shutdown();
            try {
                if (!queue.awaitTermination(10, TimeUnit.SECONDS)) {
                    queue.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                queue.shutdownNow();
            }
            closeResources();
        }
    }

    private void closeResources() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException exception) {
                plugin.getLogger().warning("Не удалось закрыть SQLite: " + exception.getMessage());
            } finally {
                connection = null;
            }
        }
        if (driver != null) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Не удалось отключить драйвер SQLite: " + exception.getMessage());
            } finally {
                driver = null;
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
