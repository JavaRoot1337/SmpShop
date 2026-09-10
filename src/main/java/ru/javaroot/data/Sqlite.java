package ru.javaroot.data;

import org.bukkit.plugin.Plugin;
import org.sqlite.JDBC;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Sqlite {
    private final Plugin plugin;
    private final String fileName;
    private final int timeoutSeconds;
    private volatile Thread worker;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SMPSHOP-SQLite");
        thread.setDaemon(true);
        worker = thread;
        return thread;
    });
    private final Object lifecycle = new Object();
    private Connection connection;
    private Driver driver;
    private CompletableFuture<Void> startFuture;
    private volatile State state = State.NEW;

    public Sqlite(Plugin plugin, String fileName, int timeoutSeconds) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.timeoutSeconds = timeoutSeconds;
    }

    public CompletableFuture<Void> start() {
        synchronized (lifecycle) {
            if (state != State.NEW) {
                return CompletableFuture.failedFuture(new IllegalStateException("SQLite уже запущен"));
            }
            state = State.STARTING;
            startFuture = CompletableFuture.runAsync(this::initialize, queue);
            return startFuture;
        }
    }

    private void initialize() {
        try {
            File file = new File(plugin.getDataFolder(), fileName);
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Не удалось создать папку данных плагина");
            }
            driver = new JDBC();
            DriverManager.registerDriver(driver);
            Path databaseFile = file.toPath().toAbsolutePath().normalize();
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            connection.setAutoCommit(true);
            configure(connection);
            new Migration().apply(connection, databaseFile);
            state = State.READY;
        } catch (SQLException exception) {
            closeResources();
            state = State.FAILED;
            throw new CompletionException(new IllegalStateException("Не удалось инициализировать SQLite", exception));
        }
    }

    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        if (state != State.READY) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite не готов к работе"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (connection == null || connection.isClosed()) {
                        throw new IllegalStateException("SQLite не готов к работе");
                    }
                    return work.run(connection);
                } catch (SQLException exception) {
                    throw new IllegalStateException("Не удалось выполнить операцию SQLite", exception);
                }
            }, queue);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite закрывается", exception));
        }
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
        CompletableFuture<Void> startup;
        synchronized (lifecycle) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return;
            }
            state = State.CLOSING;
            startup = startFuture;
        }
        if (Thread.currentThread() == worker) {
            closeFromQueue();
            return;
        }
        if (startup != null && !startup.isDone()) {
            try {
                startup.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                queue.shutdownNow();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                queue.shutdownNow();
            }
        }
        if (!queue.isShutdown()) {
            try {
                Future<?> closeTask = queue.submit(this::closeResources);
                queue.shutdown();
                closeTask.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                queue.shutdownNow();
                closeResources();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                queue.shutdownNow();
                closeResources();
            }
        } else {
            closeResources();
        }
        synchronized (lifecycle) {
            state = State.CLOSED;
        }
    }

    private void closeFromQueue() {
        closeResources();
        queue.shutdown();
        synchronized (lifecycle) {
            state = State.CLOSED;
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

    private enum State {
        NEW,
        STARTING,
        READY,
        FAILED,
        CLOSING,
        CLOSED
    }
}
