package ru.javaroot.smpshop.trade;

import ru.javaroot.smpshop.shop.CurType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TradeStore {
    private final Map<UUID, TradeSess> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, EnumMap<CurType, Long>> pendingReturns = new ConcurrentHashMap<>();
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();
    private final Set<Long> removalLocks = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private volatile boolean accepting;

    TradeSess get(UUID playerId) {
        return sessions.get(playerId);
    }

    boolean putIfAbsent(UUID playerId, TradeSess sess) {
        return sessions.putIfAbsent(playerId, sess) == null;
    }

    TradeSess remove(UUID playerId) {
        return sessions.remove(playerId);
    }

    Set<UUID> playerIds() {
        return Set.copyOf(sessions.keySet());
    }

    Set<UUID> pendingReturnPlayerIds() {
        return Set.copyOf(pendingReturns.keySet());
    }

    EnumMap<CurType, Long> pendingReturn(UUID playerId) {
        return pendingReturns.get(playerId);
    }

    boolean removePendingReturn(UUID playerId, EnumMap<CurType, Long> pay) {
        return pendingReturns.remove(playerId, pay);
    }

    void mergePendingReturn(UUID playerId, EnumMap<CurType, Long> pay) {
        if (pay.isEmpty()) {
            return;
        }
        pendingReturns.merge(playerId, pay, (cur, added) -> {
            added.forEach((k, v) -> cur.merge(k, v, Long::sum));
            return cur;
        });
    }

    boolean isBusy(long shopId) {
        synchronized (lock) {
            return lockedShops.contains(shopId) || removalLocks.contains(shopId);
        }
    }

    boolean reserve(long shopId) {
        synchronized (lock) {
            return !removalLocks.contains(shopId) && lockedShops.add(shopId);
        }
    }

    void release(long shopId) {
        synchronized (lock) {
            lockedShops.remove(shopId);
        }
    }

    boolean reserveRemoval(long shopId) {
        synchronized (lock) {
            return !lockedShops.contains(shopId) && removalLocks.add(shopId);
        }
    }

    void releaseRemoval(long shopId) {
        synchronized (lock) {
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
