package ru.javaroot.list.trade;

import ru.javaroot.currency.Currency;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class TradeSessionStore {
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, EnumMap<Currency, Long>> pendingReturns = new ConcurrentHashMap<>();
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting;

    TradeSession get(UUID playerId) {
        return sessions.get(playerId);
    }

    void put(UUID playerId, TradeSession session) {
        sessions.put(playerId, session);
    }

    TradeSession remove(UUID playerId) {
        return sessions.remove(playerId);
    }

    Set<UUID> playerIds() {
        return Set.copyOf(sessions.keySet());
    }

    Set<UUID> pendingReturnPlayerIds() {
        return Set.copyOf(pendingReturns.keySet());
    }

    EnumMap<Currency, Long> removePendingReturn(UUID playerId) {
        return pendingReturns.remove(playerId);
    }

    void mergePendingReturn(UUID playerId, EnumMap<Currency, Long> payment) {
        pendingReturns.merge(playerId, payment, (current, added) -> {
            added.forEach((currency, amount) -> current.merge(currency, amount, Long::sum));
            return current;
        });
    }

    boolean isBusy(long shopId) {
        return lockedShops.contains(shopId);
    }

    boolean reserve(long shopId) {
        return lockedShops.add(shopId);
    }

    void release(long shopId) {
        lockedShops.remove(shopId);
    }

    boolean accepting() {
        return accepting;
    }

    void start() {
        accepting = true;
    }

    void stop() {
        accepting = false;
    }
}
