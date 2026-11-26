package ru.javaroot.smpshop.db;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class Db {
    private final Plugin plug;
    private final String fileName;
    private final int timeoutSec;
    private final int shutdownTimeoutSec;
    private volatile Thread worker;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "SmpShop-SQLite");
        t.setDaemon(true);
        worker = t;
        return t;
    });
    private final Object lock = new Object();
    private Connection conn;
    private Driver driver;
    private volatile State state = State.NEW;

    public Db(Plugin plug, String fileName, int timeoutSec, int shutdownTimeoutSec) {
        this.plug = plug;
        this.fileName = fileName;
        this.timeoutSec = timeoutSec;
        this.shutdownTimeoutSec = shutdownTimeoutSec;
    }

    public CompletableFuture<Void> start() {
        synchronized (lock) {
            if (state != State.NEW) {
                return CompletableFuture.failedFuture(new IllegalStateException("SQLite уже запущен"));
            }
            state = State.STARTING;
            return CompletableFuture.runAsync(this::init, queue);
        }
    }

    private void init() {
        try {
            File file = new File(plug.getDataFolder(), fileName);
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Не удалось создать папку базы данных");
            }
            driver = new JDBC();
            DriverManager.registerDriver(driver);
            Path dbFile = file.toPath().toAbsolutePath().normalize();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            conn.setAutoCommit(true);
            configure(conn);
            new DbMig().apply(conn, dbFile);
            synchronized (lock) {
                if (state == State.CLOSING || state == State.CLOSED) {
                    closeResources();
                    return;
                }
                state = State.READY;
            }
        } catch (Throwable ex) {
            closeResources();
            synchronized (lock) {
                if (state != State.CLOSING && state != State.CLOSED) {
                    state = State.FAILED;
                }
            }
            throw new CompletionException(new IllegalStateException("Не удалось запустить SQLite", ex));
        }
    }

    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        synchronized (lock) {
            if (state != State.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException("SQLite не готов к работе"));
            }
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (conn == null || conn.isClosed()) {
                        throw new IllegalStateException("Соединение SQLite закрыто");
                    }
                    return work.run(conn);
                } catch (SQLException ex) {
                    throw new IllegalStateException("Ошибка запроса SQLite", ex);
                }
            }, queue);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException("SQLite останавливается", ex));
        }
    }

    public <T> T tx(Connection c, TxWork<T> action) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T res = action.run();
            c.commit();
            return res;
        } catch (SQLException | RuntimeException ex) {
            try {
                c.rollback();
            } catch (SQLException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        } finally {
            try {
                c.setAutoCommit(autoCommit);
            } catch (SQLException restoreEx) {
                plug.getLogger().log(Level.WARNING, "Не удалось вернуть autoCommit в SQLite", restoreEx);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return;
            }
            state = State.CLOSING;
        }
        if (Thread.currentThread() == worker) {
            closeResources();
            queue.shutdown();
            synchronized (lock) {
                state = State.CLOSED;
            }
            return;
        }
        if (!queue.isShutdown()) {
            try {
                queue.submit(this::closeResources);
                queue.shutdown();
                if (!queue.awaitTermination(shutdownTimeoutSec, TimeUnit.SECONDS)) {
                    queue.shutdownNow();
                    plug.getLogger().warning("Таймаут остановки очереди SQLite");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                queue.shutdownNow();
            }
        }
        synchronized (lock) {
            state = State.CLOSED;
        }
    }

    private void configure(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = " + timeoutSec * 1000L);
        }
    }

    private void closeResources() {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ex) {
                plug.getLogger().log(Level.WARNING, "Не удалось закрыть SQLite", ex);
            } finally {
                conn = null;
            }
        }
        if (driver != null) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException ex) {
                plug.getLogger().log(Level.WARNING, "Не удалось выгрузить драйвер SQLite", ex);
            } finally {
                driver = null;
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T run() throws SQLException;
    }

    private enum State {
        NEW, STARTING, READY, FAILED, CLOSING, CLOSED
    }
}
