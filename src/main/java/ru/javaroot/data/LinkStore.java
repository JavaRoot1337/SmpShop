// code team-dev
package ru.javaroot.data;

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

public class LinkStore {
    private static final long CODE_BOUND = 10_000_000_000_000_000L;
    private final Sqlite db;
    private final SecureRandom random = new SecureRandom();

    public LinkStore(Sqlite db) {
        this.db = db;
    }

    public CompletableFuture<String> createCode(UUID playerId, String platform, int timeoutSeconds) {
        return db.submit(connection -> {
            for (int attempt = 0; attempt < 100; attempt++) {
                String code = String.format(Locale.ROOT, "%016d", random.nextLong(CODE_BOUND));
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO link_codes(code_hash, player_uuid, platform, expires_at, used_at) VALUES (?, ?, ?, ?, NULL)")) {
                    statement.setString(1, hash(code));
                    statement.setString(2, playerId.toString());
                    statement.setString(3, platform);
                    statement.setLong(4, System.currentTimeMillis() + timeoutSeconds * 1000L);
                    statement.executeUpdate();
                    return code;
                } catch (SQLException exception) {
                    if (!exception.getMessage().toLowerCase(Locale.ROOT).contains("constraint")) {
                        throw exception;
                    }
                }
            }
            throw new SQLException("Could not create a unique link code");
        });
    }

    public CompletableFuture<LinkResult> consume(String code, String platform, String externalId, String externalName) {
        return db.submit(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID playerId;
                try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM link_codes WHERE code_hash = ? AND platform = ? AND used_at IS NULL AND expires_at > ?")) {
                    statement.setString(1, hash(code));
                    statement.setString(2, platform);
                    statement.setLong(3, System.currentTimeMillis());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return null;
                        }
                        playerId = UUID.fromString(result.getString(1));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE link_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setString(2, hash(code));
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO social_links(player_uuid, platform, external_id, external_name, linked_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(player_uuid, platform) DO UPDATE SET external_id = excluded.external_id, external_name = excluded.external_name, linked_at = excluded.linked_at")) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, platform);
                    statement.setString(3, externalId);
                    statement.setString(4, externalName);
                    statement.setLong(5, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                connection.commit();
                return new LinkResult(playerId, externalId);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    public CompletableFuture<String> externalId(UUID playerId, String platform) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT external_id FROM social_links WHERE player_uuid = ? AND platform = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, platform);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        });
    }

    private String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format(Locale.ROOT, "%02x", item));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LinkResult(UUID playerId, String externalId) {
    }
}
