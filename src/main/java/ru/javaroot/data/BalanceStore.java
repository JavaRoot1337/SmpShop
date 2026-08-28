package ru.javaroot.data;

import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BalanceStore {
    private final Sqlite db;

    public BalanceStore(Sqlite db) {
        this.db = db;
    }

    public CompletableFuture<Balance> load(UUID playerId) {
        return db.submit(connection -> load(connection, playerId));
    }

    public CompletableFuture<Boolean> take(UUID playerId, Currency currency, long amount) {
        if (amount < 1) {
            return CompletableFuture.completedFuture(false);
        }
        return db.submit(connection -> update(connection, playerId, currency, -amount) == MutationStatus.SUCCESS);
    }

    public CompletableFuture<Void> deposit(UUID playerId, Currency currency, long amount) {
        if (amount < 1) {
            return CompletableFuture.completedFuture(null);
        }
        return db.submit(connection -> {
            MutationStatus status = update(connection, playerId, currency, amount);
            if (status != MutationStatus.SUCCESS) {
                throw new SQLException("Balance overflow");
            }
            return null;
        });
    }

    public CompletableFuture<MutationResult> change(UUID operationId, String source, UUID playerId,
                                                     Currency currency, long amount) {
        String requestHash = "change:" + playerId + ':' + currency + ':' + amount;
        return db.submit(connection -> transaction(connection, () -> {
            LedgerEntry prior = ledger(connection, operationId, source);
            if (prior != null) {
                return replay(connection, prior, requestHash);
            }
            MutationStatus status = amount == 0 || amount == Long.MIN_VALUE
                    ? MutationStatus.INVALID_INPUT
                    : update(connection, playerId, currency, amount);
            insertLedger(connection, operationId, source, requestHash, "CHANGE", playerId,
                    null, currency, amount, status);
            return new MutationResult(status, load(connection, playerId), null, true);
        }));
    }

    public CompletableFuture<MutationResult> transfer(UUID operationId, String source, UUID fromPlayerId,
                                                       UUID toPlayerId, Currency currency, long amount) {
        String requestHash = "transfer:" + fromPlayerId + ':' + toPlayerId + ':' + currency + ':' + amount;
        return db.submit(connection -> transaction(connection, () -> {
            LedgerEntry prior = ledger(connection, operationId, source);
            if (prior != null) {
                return replay(connection, prior, requestHash);
            }
            MutationStatus status = transfer(connection, fromPlayerId, toPlayerId, currency, amount);
            insertLedger(connection, operationId, source, requestHash, "TRANSFER", fromPlayerId,
                    toPlayerId, currency, amount, status);
            return new MutationResult(status, load(connection, fromPlayerId), load(connection, toPlayerId), true);
        }));
    }

    public CompletableFuture<MutationResult> settle(UUID operationId, UUID playerId,
                                                     long diamondDelta, long deepslateDelta) {
        String source = "smpshop:balance_gui";
        String requestHash = "settle:" + playerId + ':' + diamondDelta + ':' + deepslateDelta;
        return db.submit(connection -> transaction(connection, () -> {
            LedgerEntry prior = ledger(connection, operationId, source);
            if (prior != null) {
                return replay(connection, prior, requestHash);
            }
            ensureBalance(connection, playerId);
            Balance current = load(connection, playerId);
            MutationStatus status = status(current.diamondOre(), diamondDelta);
            if (status == MutationStatus.SUCCESS) {
                status = status(current.deepslateDiamondOre(), deepslateDelta);
            }
            if (status == MutationStatus.SUCCESS) {
                set(connection, playerId, Currency.DIAMOND_ORE, current.diamondOre() + diamondDelta);
                set(connection, playerId, Currency.DEEPSLATE_DIAMOND_ORE,
                        current.deepslateDiamondOre() + deepslateDelta);
            }
            insertLedger(connection, operationId, source, requestHash, "SETTLE", playerId,
                    null, "BOTH", 0, status);
            return new MutationResult(status, load(connection, playerId), null, true);
        }));
    }

    private MutationStatus transfer(Connection connection, UUID fromPlayerId, UUID toPlayerId,
                                    Currency currency, long amount) throws SQLException {
        if (amount < 1 || fromPlayerId.equals(toPlayerId)) {
            return MutationStatus.INVALID_INPUT;
        }
        ensureBalance(connection, fromPlayerId);
        ensureBalance(connection, toPlayerId);
        Balance from = load(connection, fromPlayerId);
        Balance to = load(connection, toPlayerId);
        long fromAmount = amount(from, currency);
        long toAmount = amount(to, currency);
        if (fromAmount < amount) {
            return MutationStatus.INSUFFICIENT_FUNDS;
        }
        if (toAmount > Long.MAX_VALUE - amount) {
            return MutationStatus.CONFLICT;
        }
        set(connection, fromPlayerId, currency, fromAmount - amount);
        set(connection, toPlayerId, currency, toAmount + amount);
        return MutationStatus.SUCCESS;
    }

    private MutationStatus update(Connection connection, UUID playerId, Currency currency, long delta) throws SQLException {
        ensureBalance(connection, playerId);
        Balance balance = load(connection, playerId);
        long current = amount(balance, currency);
        if (delta < 0 && (delta == Long.MIN_VALUE || current < -delta)) {
            return MutationStatus.INSUFFICIENT_FUNDS;
        }
        if (delta > 0 && current > Long.MAX_VALUE - delta) {
            return MutationStatus.CONFLICT;
        }
        set(connection, playerId, currency, current + delta);
        return MutationStatus.SUCCESS;
    }

    private MutationStatus status(long current, long delta) {
        if (delta == Long.MIN_VALUE) {
            return MutationStatus.INVALID_INPUT;
        }
        if (delta < 0 && current < -delta) {
            return MutationStatus.INSUFFICIENT_FUNDS;
        }
        if (delta > 0 && current > Long.MAX_VALUE - delta) {
            return MutationStatus.CONFLICT;
        }
        return MutationStatus.SUCCESS;
    }

    private long amount(Balance balance, Currency currency) {
        return currency == Currency.DIAMOND_ORE ? balance.diamondOre() : balance.deepslateDiamondOre();
    }

    private void set(Connection connection, UUID playerId, Currency currency, long amount) throws SQLException {
        String column = column(currency);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE balances SET " + column + " = ?, updated_at = ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, playerId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Balance row disappeared");
            }
        }
    }

    private void ensureBalance(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO balances(player_uuid, diamond_ore, deepslate_diamond_ore, updated_at) VALUES (?, 0, 0, ?) ON CONFLICT(player_uuid) DO NOTHING")) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private Balance load(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT diamond_ore, deepslate_diamond_ore FROM balances WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new Balance(result.getLong(1), result.getLong(2));
                }
            }
        }
        return new Balance(0, 0);
    }

    private LedgerEntry ledger(Connection connection, UUID operationId, String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_hash, status, player_uuid, target_uuid FROM balance_ledger WHERE operation_id = ? AND source = ?")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, source);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String target = result.getString("target_uuid");
                return new LedgerEntry(result.getString("request_hash"),
                        MutationStatus.valueOf(result.getString("status")),
                        UUID.fromString(result.getString("player_uuid")),
                        target == null ? null : UUID.fromString(target));
            }
        }
    }

    private MutationResult replay(Connection connection, LedgerEntry entry, String requestHash) throws SQLException {
        if (!entry.requestHash.equals(requestHash)) {
            return new MutationResult(MutationStatus.CONFLICT, null, null, false);
        }
        Balance source = load(connection, entry.playerId);
        Balance target = entry.targetId == null ? null : load(connection, entry.targetId);
        return new MutationResult(entry.status, source, target, false);
    }

    private void insertLedger(Connection connection, UUID operationId, String source, String requestHash,
                              String type, UUID playerId, UUID targetId, Currency currency,
                              long amount, MutationStatus status) throws SQLException {
        insertLedger(connection, operationId, source, requestHash, type, playerId, targetId,
                currency.name(), amount, status);
    }

    private void insertLedger(Connection connection, UUID operationId, String source, String requestHash,
                              String type, UUID playerId, UUID targetId, String currency,
                              long amount, MutationStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO balance_ledger(operation_id, source, request_hash, operation_type, player_uuid, target_uuid, currency, amount, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, operationId.toString());
            statement.setString(2, source);
            statement.setString(3, requestHash);
            statement.setString(4, type);
            statement.setString(5, playerId.toString());
            statement.setString(6, targetId == null ? null : targetId.toString());
            statement.setString(7, currency);
            statement.setLong(8, amount);
            statement.setString(9, status.name());
            statement.setLong(10, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private <T> T transaction(Connection connection, SqlAction<T> action) throws SQLException {
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
            connection.setAutoCommit(autoCommit);
        }
    }

    private String column(Currency currency) {
        return currency == Currency.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
    }

    public record MutationResult(MutationStatus status, Balance source, Balance target, boolean applied) {
    }

    public enum MutationStatus {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        CONFLICT,
        INVALID_INPUT
    }

    private record LedgerEntry(String requestHash, MutationStatus status, UUID playerId, UUID targetId) {
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }
}
