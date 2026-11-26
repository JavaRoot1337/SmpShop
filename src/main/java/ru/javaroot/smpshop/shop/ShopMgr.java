package ru.javaroot.smpshop.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ShopMgr {
    private final Map<Long, Shop> byId = new HashMap<>();
    private final Map<ShopKey, Long> byBlock = new HashMap<>();
    private final Set<ShopKey> reserved = new HashSet<>();
    private final Map<ShopKey, UUID> reservationOwners = new HashMap<>();
    private final Map<UUID, Integer> pendingByOwner = new HashMap<>();
    private final Object lock = new Object();

    public void load(Collection<Shop> shops) {
        synchronized (lock) {
            byId.clear();
            byBlock.clear();
            reserved.clear();
            reservationOwners.clear();
            pendingByOwner.clear();
            for (Shop shop : shops) {
                addLocked(shop);
            }
        }
    }

    public boolean isReserved(ShopKey key) {
        synchronized (lock) {
            return reserved.contains(key);
        }
    }

    public boolean isProtected(ShopKey key) {
        synchronized (lock) {
            return reserved.contains(key) || byBlock.containsKey(key);
        }
    }

    public boolean isRegistered(ShopKey key) {
        synchronized (lock) {
            return byBlock.containsKey(key);
        }
    }

    public void add(Shop shop) {
        synchronized (lock) {
            addLocked(shop);
        }
    }

    public void remove(Shop shop) {
        synchronized (lock) {
            byId.remove(shop.id(), shop);
            byBlock.remove(shop.firstKey(), shop.id());
            if (shop.secondKey() != null) {
                byBlock.remove(shop.secondKey(), shop.id());
            }
        }
    }

    public Shop find(ShopKey key) {
        synchronized (lock) {
            Long id = byBlock.get(key);
            return id == null ? null : byId.get(id);
        }
    }

    public Shop find(long id) {
        synchronized (lock) {
            return byId.get(id);
        }
    }

    public UUID findOwnerByName(String name) {
        synchronized (lock) {
            for (Shop shop : byId.values()) {
                if (shop.ownerName() != null && shop.ownerName().equalsIgnoreCase(name)) {
                    return shop.ownerId();
                }
            }
            return null;
        }
    }

    public int count(UUID ownerId) {
        synchronized (lock) {
            int cnt = 0;
            for (Shop shop : byId.values()) {
                if (shop.ownerId().equals(ownerId)) {
                    cnt++;
                }
            }
            return cnt;
        }
    }

    public Collection<Shop> all() {
        synchronized (lock) {
            return Collections.unmodifiableCollection(new ArrayList<>(byId.values()));
        }
    }

    public Shop updatePrice(long shopId, int price) {
        synchronized (lock) {
            Shop shop = byId.get(shopId);
            if (shop == null) {
                return null;
            }
            Shop updated = new Shop(
                    shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(),
                    price, shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt()
            );
            byId.put(shopId, updated);
            return updated;
        }
    }

    public void recordSale(long shopId, long amount, int items) {
        synchronized (lock) {
            byId.computeIfPresent(shopId, (id, shop) -> new Shop(
                    shop.id(), shop.ownerId(), shop.ownerName(), shop.worldId(),
                    shop.x1(), shop.y1(), shop.z1(), shop.x2(), shop.y2(), shop.z2(),
                    shop.price(),
                    Math.addExact(shop.earnedTotal(), amount),
                    Math.addExact(shop.soldSlots(), 1),
                    Math.addExact(shop.soldItems(), items),
                    shop.createdAt()
            ));
        }
    }

    public boolean tryReserve(UUID ownerId, int limit, Collection<ShopKey> keys) {
        synchronized (lock) {
            if (count(ownerId) + pendingByOwner.getOrDefault(ownerId, 0) >= limit) {
                return false;
            }
            for (ShopKey key : keys) {
                if (reserved.contains(key) || byBlock.containsKey(key)) {
                    return false;
                }
            }
            reserved.addAll(keys);
            for (ShopKey key : keys) {
                reservationOwners.put(key, ownerId);
            }
            pendingByOwner.merge(ownerId, 1, Integer::sum);
            return true;
        }
    }

    public void release(UUID ownerId, Collection<ShopKey> keys) {
        synchronized (lock) {
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
}
