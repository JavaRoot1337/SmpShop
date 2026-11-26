package ru.javaroot.smpshop.shop;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingAct {
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public void put(UUID playerId, Type type, int price, int timeoutSec) {
        entries.put(playerId, new Entry(type, price, System.currentTimeMillis() + timeoutSec * 1000L));
    }

    public Entry get(UUID playerId) {
        Entry entry = entries.get(playerId);
        if (entry != null && entry.expiresAt() < System.currentTimeMillis()) {
            entries.remove(playerId, entry);
            return null;
        }
        return entry;
    }

    public void clear(UUID playerId) {
        entries.remove(playerId);
    }

    public record Entry(Type type, int price, long expiresAt) {
    }

    public enum Type {
        PLACE,
        DELETE,
        REMOVE
    }
}
