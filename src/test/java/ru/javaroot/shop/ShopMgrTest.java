package ru.javaroot.shop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopMgrTest {
    @Test
    void rejectsDuplicateRegistrationWithoutPartialIndexes() {
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Shop first = shop(1, owner, world, 1);
        Shop duplicateId = shop(1, owner, world, 2);
        Shop duplicateBlock = shop(2, owner, world, 1);
        ShopMgr shops = new ShopMgr();
        shops.add(first);

        assertThrows(IllegalStateException.class, () -> shops.add(duplicateId));
        assertThrows(IllegalStateException.class, () -> shops.add(duplicateBlock));
        assertEquals(first, shops.find(first.firstKey()));
        assertEquals(1, shops.count(owner));
        assertFalse(shops.isRegistered(duplicateId.firstKey()));
    }

    @Test
    void reservationBlocksRegistrationUntilReleased() {
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        ShopKey key = new ShopKey(world, 1, 64, 1);
        ShopMgr shops = new ShopMgr();

        assertTrue(shops.tryReserve(owner, 1, List.of(key)));
        assertTrue(shops.isProtected(key));
        assertFalse(shops.tryReserve(UUID.randomUUID(), 1, List.of(key)));
        shops.release(owner, List.of(key));
        assertFalse(shops.isProtected(key));

        UUID nextOwner = UUID.randomUUID();
        assertTrue(shops.tryReserve(nextOwner, 1, List.of(key)));
        shops.release(owner, List.of(key));
        assertTrue(shops.isProtected(key));
        shops.release(nextOwner, List.of(key));
        assertFalse(shops.isProtected(key));
    }

    private Shop shop(long id, UUID owner, UUID world, int x) {
        return new Shop(id, owner, "owner", world, x, 64, 1,
                null, null, null, 10, 0, 0, 0, 1, true);
    }
}
