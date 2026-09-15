package ru.javaroot.data;

import org.bukkit.plugin.Plugin;
import org.sqlite.JDBC;
import ru.javaroot.config.DbLoc;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Sqlite {
    private final Plugin plugin;
    private final String fileName;
    private final int timeoutSeconds;
    private final int shutdownTimeoutSeconds;
    private final DbLoc messages;
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
        this(plugin, fileName, timeoutSeconds, null);
    }

    public Sqlite(Plugin plugin, String fileName, int timeoutSeconds, DbLoc messages) {
        this(plugin, fileName, timeoutSeconds, 10, messages);
    }

    public Sqlite(Plugin plugin, String fileName, int timeoutSeconds, int shutdownTimeoutSeconds, DbLoc messages) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.timeoutSeconds = timeoutSeconds;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.messages = messages;
    }

    public CompletableFuture<Void> start() {
        synchronized (lifecycle) {
            if (state != State.NEW) {
                return CompletableFuture.failedFuture(new IllegalStateException(message("sqlite.already-started")));
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
                throw new SQLException(message("sqlite.data-folder-create-failed"));
            }
            driver = new JDBC();
            DriverManager.registerDriver(driver);
            Path databaseFile = file.toPath().toAbsolutePath().normalize();
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            connection.setAutoCommit(true);
            configure(connection);
            new Migration(messages).apply(connection, databaseFile);
            synchronized (lifecycle) {
                if (state == State.CLOSING || state == State.CLOSED) {
                    closeResources();
                    return;
                }
                state = State.READY;
            }
        } catch (Throwable exception) {
            closeResources();
            synchronized (lifecycle) {
                if (state != State.CLOSING && state != State.CLOSED) {
                    state = State.FAILED;
                }
            }
            throw new CompletionException(new IllegalStateException(message("sqlite.initialization-failed"), exception));
        }
    }

    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        synchronized (lifecycle) {
            if (state != State.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException(message("sqlite.not-ready")));
            }
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (connection == null || connection.isClosed()) {
                        throw new IllegalStateException(message("sqlite.not-ready"));
                    }
                    return work.run(connection);
                } catch (SQLException exception) {
                    throw new IllegalStateException(message("sqlite.operation-failed"), exception);
                }
            }, queue);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException(message("sqlite.closing"), exception));
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
        synchronized (lifecycle) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return;
            }
            state = State.CLOSING;
        }
        if (Thread.currentThread() == worker) {
            closeFromQueue();
            return;
        }
        if (!queue.isShutdown()) {
            try {
                queue.submit(this::closeResources);
                queue.shutdown();
                if (!queue.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    queue.shutdownNow();
                    plugin.getLogger().log(java.util.logging.Level.WARNING, message("sqlite.close-failed"));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                queue.shutdownNow();
            }
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
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        message("sqlite.close-failed"), exception);
            } finally {
                connection = null;
            }
        }
        if (driver != null) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        message("sqlite.driver-deregister-failed"), exception);
            } finally {
                driver = null;
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    <T> T transaction(Connection connection, TransactionWork<T> action) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                exception.addSuppressed(rollbackError);
            }
            throw exception;
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException restoreError) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        message("sqlite.operation-failed"), restoreError);
            }
        }
    }

    @FunctionalInterface
    interface TransactionWork<T> {
        T run() throws SQLException;
    }

    String message(String key) {
        return messages == null ? key : messages.raw(key);
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
