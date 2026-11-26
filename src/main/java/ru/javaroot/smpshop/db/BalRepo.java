package ru.javaroot.smpshop.db;

import ru.javaroot.smpshop.shop.Bal;
import ru.javaroot.smpshop.shop.CurType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BalRepo {
    private final Db db;

    public BalRepo(Db db) {
        this.db = db;
    }

    public CompletableFuture<Bal> load(UUID playerId) {
        return db.submit(c -> loadSync(c, playerId));
    }

    public CompletableFuture<Void> deposit(UUID playerId, CurType cur, long amount) {
        if (amount < 1) {
            return CompletableFuture.completedFuture(null);
        }
        return db.submit(c -> {
            if (updateSync(c, playerId, cur, amount) != Status.SUCCESS) {
                throw new SQLException("Переполнение баланса игрока " + playerId);
            }
            return null;
        });
    }

    public CompletableFuture<MutRes> change(UUID opId, String src, UUID playerId, CurType cur, long amount) {
        String hash = "change:" + playerId + ':' + cur + ':' + amount;
        return db.submit(c -> db.tx(c, () -> {
            Ledger prior = ledger(c, opId, src);
            if (prior != null) {
                return replay(c, prior, hash);
            }
            Status st = amount == 0 || amount == Long.MIN_VALUE
                    ? Status.INVALID_INPUT
                    : updateSync(c, playerId, cur, amount);
            insertLedger(c, opId, src, hash, "CHANGE", playerId, null, cur.name(), amount, st);
            return new MutRes(st, loadSync(c, playerId), null, true);
        }));
    }

    public CompletableFuture<MutRes> transfer(UUID opId, String src, UUID fromId, UUID toId,
                                              CurType cur, long amount) {
        String hash = "transfer:" + fromId + ':' + toId + ':' + cur + ':' + amount;
        return db.submit(c -> db.tx(c, () -> {
            Ledger prior = ledger(c, opId, src);
            if (prior != null) {
                return replay(c, prior, hash);
            }
            Status st = transferSync(c, fromId, toId, cur, amount);
            insertLedger(c, opId, src, hash, "TRANSFER", fromId, toId, cur.name(), amount, st);
            return new MutRes(st, loadSync(c, fromId), loadSync(c, toId), true);
        }));
    }

    public CompletableFuture<MutRes> settle(UUID opId, UUID playerId, long diamondDelta, long deepslateDelta) {
        String src = "smpshop:balance_gui";
        String hash = "settle:" + playerId + ':' + diamondDelta + ':' + deepslateDelta;
        return db.submit(c -> db.tx(c, () -> {
            Ledger prior = ledger(c, opId, src);
            if (prior != null) {
                return replay(c, prior, hash);
            }
            ensureRow(c, playerId);
            Bal cur = loadSync(c, playerId);
            Status st = checkDelta(cur.diamondOre(), diamondDelta);
            if (st == Status.SUCCESS) {
                st = checkDelta(cur.deepslateDiamondOre(), deepslateDelta);
            }
            if (st == Status.SUCCESS) {
                setSync(c, playerId, CurType.DIAMOND_ORE, cur.diamondOre() + diamondDelta);
                setSync(c, playerId, CurType.DEEPSLATE_DIAMOND_ORE, cur.deepslateDiamondOre() + deepslateDelta);
            }
            insertLedger(c, opId, src, hash, "SETTLE", playerId, null, "BOTH", 0, st);
            return new MutRes(st, loadSync(c, playerId), null, true);
        }));
    }

    private Status transferSync(Connection c, UUID fromId, UUID toId, CurType cur, long amount) throws SQLException {
        if (amount < 1 || fromId.equals(toId)) {
            return Status.INVALID_INPUT;
        }
        ensureRow(c, fromId);
        ensureRow(c, toId);
        Bal from = loadSync(c, fromId);
        Bal to = loadSync(c, toId);
        long fromVal = pick(from, cur);
        long toVal = pick(to, cur);
        if (fromVal < amount) {
            return Status.INSUFFICIENT_FUNDS;
        }
        if (toVal > Long.MAX_VALUE - amount) {
            return Status.CONFLICT;
        }
        setSync(c, fromId, cur, fromVal - amount);
        setSync(c, toId, cur, toVal + amount);
        return Status.SUCCESS;
    }

    private Status updateSync(Connection c, UUID playerId, CurType cur, long delta) throws SQLException {
        ensureRow(c, playerId);
        Bal bal = loadSync(c, playerId);
        long current = pick(bal, cur);
        Status st = checkDelta(current, delta);
        if (st != Status.SUCCESS) {
            return st;
        }
        setSync(c, playerId, cur, current + delta);
        return Status.SUCCESS;
    }

    private static Status checkDelta(long current, long delta) {
        if (delta == Long.MIN_VALUE) {
            return Status.INVALID_INPUT;
        }
        if (delta < 0 && current < -delta) {
            return Status.INSUFFICIENT_FUNDS;
        }
        if (delta > 0 && current > Long.MAX_VALUE - delta) {
            return Status.CONFLICT;
        }
        return Status.SUCCESS;
    }

    private static long pick(Bal bal, CurType cur) {
        return cur == CurType.DIAMOND_ORE ? bal.diamondOre() : bal.deepslateDiamondOre();
    }

    private void setSync(Connection c, UUID playerId, CurType cur, long amount) throws SQLException {
        String col = cur == CurType.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE balances SET " + col + " = ?, updated_at = ? WHERE player_uuid = ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, playerId.toString());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Строка баланса не найдена при обновлении");
            }
        }
    }

    private void ensureRow(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO balances(player_uuid, diamond_ore, deepslate_diamond_ore, updated_at) VALUES (?, 0, 0, ?) ON CONFLICT(player_uuid) DO NOTHING")) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private Bal loadSync(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT diamond_ore, deepslate_diamond_ore FROM balances WHERE player_uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Bal(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return new Bal(0, 0);
    }

    private Ledger ledger(Connection c, UUID opId, String src) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT request_hash, status, player_uuid, target_uuid FROM balance_ledger WHERE operation_id = ? AND source = ?")) {
            ps.setString(1, opId.toString());
            ps.setString(2, src);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String target = rs.getString("target_uuid");
                return new Ledger(
                        rs.getString("request_hash"),
                        Status.valueOf(rs.getString("status")),
                        UUID.fromString(rs.getString("player_uuid")),
                        target == null ? null : UUID.fromString(target)
                );
            }
        }
    }

    private MutRes replay(Connection c, Ledger entry, String hash) throws SQLException {
        if (!entry.hash.equals(hash)) {
            return new MutRes(Status.CONFLICT, null, null, false);
        }
        Bal src = loadSync(c, entry.playerId);
        Bal dst = entry.targetId == null ? null : loadSync(c, entry.targetId);
        return new MutRes(entry.status, src, dst, false);
    }

    private void insertLedger(Connection c, UUID opId, String src, String hash, String type,
                              UUID playerId, UUID targetId, String cur, long amount, Status st) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO balance_ledger(operation_id, source, request_hash, operation_type, player_uuid, target_uuid, currency, amount, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, opId.toString());
            ps.setString(2, src);
            ps.setString(3, hash);
            ps.setString(4, type);
            ps.setString(5, playerId.toString());
            ps.setString(6, targetId == null ? null : targetId.toString());
            ps.setString(7, cur);
            ps.setLong(8, amount);
            ps.setString(9, st.name());
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public record MutRes(Status status, Bal source, Bal target, boolean applied) {
    }

    public enum Status {
        SUCCESS, INSUFFICIENT_FUNDS, CONFLICT, INVALID_INPUT
    }

    private record Ledger(String hash, Status status, UUID playerId, UUID targetId) {
    }
}
