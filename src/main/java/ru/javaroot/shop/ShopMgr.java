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
    private final Map<ShopKey, UUID> reservationOwners = new HashMap<>();
    private final Map<UUID, Integer> pendingByOwner = new HashMap<>();
    private final Object indexLock = new Object();

    public void load(Collection<Shop> shops) {
        synchronized (indexLock) {
            byId.clear();
            byBlock.clear();
            synchronized (reserved) {
                reserved.clear();
                reservationOwners.clear();
                pendingByOwner.clear();
            }
            for (Shop shop : shops) {
                addLocked(shop);
            }
        }
    }

    public boolean isReserved(ShopKey key) {
        synchronized (indexLock) {
            return reserved.contains(key);
        }
    }

    private void addLocked(Shop shop) {
        if (byId.containsKey(shop.id()) || byBlock.containsKey(shop.firstKey())) {
            throw new IllegalStateException("Повторная регистрация магазина: " + shop.id());
        }
        if (shop.secondKey() != null && byBlock.containsKey(shop.secondKey())) {
            throw new IllegalStateException("Позиция магазина уже занята: " + shop.secondKey());
        }
        byId.put(shop.id(), shop);
        byBlock.put(shop.firstKey(), shop.id());
        if (shop.secondKey() != null) {
            byBlock.put(shop.secondKey(), shop.id());
        }
    }

    private void removeLocked(Shop shop) {
        byId.remove(shop.id(), shop);
        byBlock.remove(shop.firstKey(), shop.id());
        if (shop.secondKey() != null) {
            byBlock.remove(shop.secondKey(), shop.id());
        }
    }

    public boolean isProtected(ShopKey key) {
        synchronized (indexLock) {
            return isReserved(key) || byBlock.containsKey(key);
        }
    }

    public boolean isRegistered(ShopKey key) {
        synchronized (indexLock) {
            return byBlock.containsKey(key);
        }
    }

    public void add(Shop shop) {
        synchronized (indexLock) {
            addLocked(shop);
        }
    }

    public void remove(Shop shop) {
        synchronized (indexLock) {
            removeLocked(shop);
        }
    }

    public Shop find(ShopKey key) {
        synchronized (indexLock) {
            Long id = byBlock.get(key);
            return id == null ? null : byId.get(id);
        }
    }

    public Shop find(long id) {
        synchronized (indexLock) {
            return byId.get(id);
        }
    }

    public int count(UUID ownerId) {
        synchronized (indexLock) {
            int count = 0;
            for (Shop shop : byId.values()) {
                if (shop.ownerId().equals(ownerId)) {
                    count++;
                }
            }
            return count;
        }
    }

    public Collection<Shop> all() {
        synchronized (indexLock) {
            return Collections.unmodifiableCollection(new ArrayList<>(byId.values()));
        }
    }

    public Shop updatePrice(long shopId, int price) {
        synchronized (indexLock) {
            Shop shop = byId.get(shopId);
            if (shop == null) {
                return null;
            }
            Shop updated = new Shop(shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(), price,
                    shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt(), shop.active());
            byId.put(shopId, updated);
            return updated;
        }
    }

    public void recordSale(long shopId, long amount, int items) {
        synchronized (indexLock) {
            byId.computeIfPresent(shopId, (id, shop) -> new Shop(
                    shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(),
                    shop.price(), Math.addExact(shop.earnedTotal(), amount), Math.addExact(shop.soldSlots(), 1),
                    Math.addExact(shop.soldItems(), items), shop.createdAt(), shop.active()));
        }
    }

    public boolean tryReserve(UUID ownerId, int limit, Collection<ShopKey> keys) {
        synchronized (indexLock) {
            if (count(ownerId) + pendingByOwner.getOrDefault(ownerId, 0) >= limit) {
                return false;
            }
            if (keys.stream().anyMatch(reserved::contains) || keys.stream().anyMatch(byBlock::containsKey)) {
                return false;
            }
            reserved.addAll(keys);
            keys.forEach(key -> reservationOwners.put(key, ownerId));
            pendingByOwner.merge(ownerId, 1, Integer::sum);
            return true;
        }
    }

    public void release(UUID ownerId, Collection<ShopKey> keys) {
        synchronized (indexLock) {
            boolean released = false;
            for (ShopKey key : keys) {
                if (ownerId.equals(reservationOwners.get(key))) {
                    reservationOwners.remove(key);
                    reserved.remove(key);
                    released = true;
                }
            }
            if (released) {
                pendingByOwner.computeIfPresent(ownerId, (id, count) -> count == 1 ? null : count - 1);
            }
        }
    }

}
