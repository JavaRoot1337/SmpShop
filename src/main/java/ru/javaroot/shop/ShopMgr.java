package ru.javaroot.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ShopMgr {
    private final ConcurrentMap<Long, Shop> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<ShopKey, Long> byBlock = new ConcurrentHashMap<>();
    private final Set<ShopKey> reserved = ConcurrentHashMap.newKeySet();

    public void load(Collection<Shop> shops) {
        byId.clear();
        byBlock.clear();
        for (Shop shop : shops) {
            add(shop);
        }
    }

    public boolean tryReserve(Collection<ShopKey> keys) {
        synchronized (reserved) {
            if (keys.stream().anyMatch(reserved::contains) || keys.stream().anyMatch(byBlock::containsKey)) {
                return false;
            }
            reserved.addAll(keys);
            return true;
        }
    }

    public void release(Collection<ShopKey> keys) {
        reserved.removeAll(keys);
    }

    public void add(Shop shop) {
        byId.put(shop.id(), shop);
        byBlock.put(shop.firstKey(), shop.id());
        if (shop.secondKey() != null) {
            byBlock.put(shop.secondKey(), shop.id());
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

    public void recordSale(long shopId, long amount, int items) {
        byId.computeIfPresent(shopId, (id, shop) -> new Shop(
                shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(),
                shop.price(), shop.earnedTotal() + amount, shop.soldSlots() + 1,
                shop.soldItems() + items, shop.createdAt(), shop.active()));
    }
}
