package ru.javaroot.api;

import ru.javaroot.currency.Balance;
import ru.javaroot.shop.Shop;

import java.util.UUID;

class ApiSnapshotMapper {
    ShopSnapshot shop(Shop shop) {
        ShopPosition first = new ShopPosition(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
        ShopPosition second = shop.x2() == null ? null
                : new ShopPosition(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
        return new ShopSnapshot(shop.id(), shop.ownerId(), shop.ownerName(), first, second,
                shop.price(), shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt());
    }

    BalanceSnapshot balance(UUID playerId, Balance balance) {
        return new BalanceSnapshot(playerId,
                new CurrencyAmounts(balance.diamondOre(), balance.deepslateDiamondOre()));
    }
}
