package ru.javaroot.smpshop.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LinkRepo {
    private static final long CODE_BOUND = 10_000_000_000_000_000L;
    private final Db db;
    private final SecureRandom rng = new SecureRandom();

    public LinkRepo(Db db) {
        this.db = db;
    }

    public CompletableFuture<String> createCode(UUID playerId, String platform, int timeoutSec) {
        return db.submit(c -> {
            for (int i = 0; i < 100; i++) {
                String code = String.format(Locale.ROOT, "%016d", rng.nextLong(CODE_BOUND));
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO link_codes(code_hash, player_uuid, platform, expires_at, used_at) VALUES (?, ?, ?, ?, NULL)")) {
                    ps.setString(1, hash(code));
                    ps.setString(2, playerId.toString());
                    ps.setString(3, platform);
                    ps.setLong(4, System.currentTimeMillis() + timeoutSec * 1000L);
                    ps.executeUpdate();
                    return code;
                } catch (SQLException ex) {
                    if (ex.getErrorCode() != 19) {
                        throw ex;
                    }
                }
            }
            throw new SQLException("Не удалось сгенерировать уникальный код привязки");
        });
    }

    public CompletableFuture<LinkRes> consume(String code, String platform, String extId, String extName) {
        String codeHash = hash(code);
        return db.submit(c -> db.tx(c, () -> {
            UUID playerId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_uuid FROM link_codes WHERE code_hash = ? AND platform = ? AND used_at IS NULL AND expires_at > ?")) {
                ps.setString(1, codeHash);
                ps.setString(2, platform);
                ps.setLong(3, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    playerId = UUID.fromString(rs.getString(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE link_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, codeHash);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO social_links(player_uuid, platform, external_id, external_name, linked_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(player_uuid, platform) DO UPDATE SET external_id = excluded.external_id, external_name = excluded.external_name, linked_at = excluded.linked_at")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, platform);
                ps.setString(3, extId);
                ps.setString(4, extName);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return new LinkRes(playerId, extId);
        }));
    }

    public CompletableFuture<String> externalId(UUID playerId, String platform) {
        return db.submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT external_id FROM social_links WHERE player_uuid = ? AND platform = ?")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, platform);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Алгоритм SHA-256 недоступен", ex);
        }
    }

    public record LinkRes(UUID playerId, String extId) {
    }
}
