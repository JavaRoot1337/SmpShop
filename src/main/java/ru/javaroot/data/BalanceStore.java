// code team-dev
package ru.javaroot.data;

import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BalanceStore {
    private final Sqlite db;

    public BalanceStore(Sqlite db) {
        this.db = db;
    }

    public CompletableFuture<Balance> load(UUID playerId) {
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT diamond_ore, deepslate_diamond_ore FROM balances WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return new Balance(result.getLong(1), result.getLong(2));
                    }
                }
            }
            return new Balance(0, 0);
        });
    }

    public CompletableFuture<Boolean> take(UUID playerId, Currency currency, long amount) {
        if (amount < 1) {
            return CompletableFuture.completedFuture(false);
        }
        String column = currency == Currency.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE balances SET " + column + " = " + column + " - ?, updated_at = ? WHERE player_uuid = ? AND " + column + " >= ?")) {
                statement.setLong(1, amount);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, playerId.toString());
                statement.setLong(4, amount);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Void> deposit(UUID playerId, Currency currency, long amount) {
        if (amount < 1) {
            return CompletableFuture.completedFuture(null);
        }
        String column = currency == Currency.DIAMOND_ORE ? "diamond_ore" : "deepslate_diamond_ore";
        return db.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO balances(player_uuid, diamond_ore, deepslate_diamond_ore, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(player_uuid) DO UPDATE SET " + column + " = " + column + " + excluded." + column + ", updated_at = excluded.updated_at")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, currency == Currency.DIAMOND_ORE ? amount : 0);
                statement.setLong(3, currency == Currency.DEEPSLATE_DIAMOND_ORE ? amount : 0);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
