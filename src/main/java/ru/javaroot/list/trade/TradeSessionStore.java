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
    private final Set<Long> removalLocks = ConcurrentHashMap.newKeySet();
    private final Object shopLock = new Object();
    private volatile boolean accepting;

    TradeSession get(UUID playerId) {
        return sessions.get(playerId);
    }

    boolean putIfAbsent(UUID playerId, TradeSession session) {
        return sessions.putIfAbsent(playerId, session) == null;
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

    EnumMap<Currency, Long> pendingReturn(UUID playerId) {
        return pendingReturns.get(playerId);
    }

    boolean removePendingReturn(UUID playerId, EnumMap<Currency, Long> payment) {
        return pendingReturns.remove(playerId, payment);
    }

    void mergePendingReturn(UUID playerId, EnumMap<Currency, Long> payment) {
        pendingReturns.merge(playerId, payment, (current, added) -> {
            added.forEach((currency, amount) -> current.merge(currency, amount, Long::sum));
            return current;
        });
    }

    boolean isBusy(long shopId) {
        synchronized (shopLock) {
            return lockedShops.contains(shopId) || removalLocks.contains(shopId);
        }
    }

    boolean reserve(long shopId) {
        synchronized (shopLock) {
            return !removalLocks.contains(shopId) && lockedShops.add(shopId);
        }
    }

    void release(long shopId) {
        synchronized (shopLock) {
            lockedShops.remove(shopId);
        }
    }

    boolean reserveRemoval(long shopId) {
        synchronized (shopLock) {
            return !lockedShops.contains(shopId) && removalLocks.add(shopId);
        }
    }

    void releaseRemoval(long shopId) {
        synchronized (shopLock) {
            removalLocks.remove(shopId);
        }
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
