package ru.javaroot.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.HashMap;
import java.util.Map;

public class ShopMgr {
    private final ConcurrentMap<Long, Shop> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<ShopKey, Long> byBlock = new ConcurrentHashMap<>();
    private final Set<ShopKey> reserved = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pendingByOwner = new HashMap<>();

    public void load(Collection<Shop> shops) {
        byId.clear();
        byBlock.clear();
        synchronized (reserved) {
            reserved.clear();
            pendingByOwner.clear();
        }
        for (Shop shop : shops) {
            add(shop);
        }
    }

    public boolean tryReserve(UUID ownerId, int limit, Collection<ShopKey> keys) {
        synchronized (reserved) {
            if (count(ownerId) + pendingByOwner.getOrDefault(ownerId, 0) >= limit) {
                return false;
            }
            if (keys.stream().anyMatch(reserved::contains) || keys.stream().anyMatch(byBlock::containsKey)) {
                return false;
            }
            reserved.addAll(keys);
            pendingByOwner.merge(ownerId, 1, Integer::sum);
            return true;
        }
    }

    public void release(UUID ownerId, Collection<ShopKey> keys) {
        synchronized (reserved) {
            reserved.removeAll(keys);
            pendingByOwner.computeIfPresent(ownerId, (id, count) -> count == 1 ? null : count - 1);
        }
    }

    public void add(Shop shop) {
        if (byId.putIfAbsent(shop.id(), shop) != null || byBlock.putIfAbsent(shop.firstKey(), shop.id()) != null) {
            throw new IllegalStateException("Duplicate shop registration: " + shop.id());
        }
        if (shop.secondKey() != null) {
            if (byBlock.putIfAbsent(shop.secondKey(), shop.id()) != null) {
                byId.remove(shop.id(), shop);
                byBlock.remove(shop.firstKey(), shop.id());
                throw new IllegalStateException("Duplicate shop position: " + shop.secondKey());
            }
        }
    }

    public void remove(Shop shop) {
        byId.remove(shop.id());
        byBlock.remove(shop.firstKey(), shop.id());
        if (shop.secondKey() != null) {
            byBlock.remove(shop.secondKey(), shop.id());
        }
    }

    public Shop find(ShopKey key) {
        Long id = byBlock.get(key);
        return id == null ? null : byId.get(id);
    }

    public Shop find(long id) {
        return byId.get(id);
    }

    public int count(UUID ownerId) {
        int count = 0;
        for (Shop shop : byId.values()) {
            if (shop.ownerId().equals(ownerId)) {
                count++;
            }
        }
        return count;
    }

    public Collection<Shop> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(byId.values()));
    }

    public Shop updatePrice(long shopId, int price) {
        while (true) {
            Shop shop = byId.get(shopId);
            if (shop == null) {
                return null;
            }
            Shop updated = new Shop(shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(), price,
                    shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt(), shop.active());
            if (byId.replace(shopId, shop, updated)) {
                return updated;
            }
        }
    }

    public void recordSale(long shopId, long amount, int items) {
        byId.computeIfPresent(shopId, (id, shop) -> new Shop(
                shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(),
                shop.price(), Math.addExact(shop.earnedTotal(), amount), Math.addExact(shop.soldSlots(), 1),
                Math.addExact(shop.soldItems(), items), shop.createdAt(), shop.active()));
    }
}
