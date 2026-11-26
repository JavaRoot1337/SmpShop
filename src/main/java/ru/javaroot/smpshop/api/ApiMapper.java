package ru.javaroot.smpshop.api;

import ru.javaroot.api.BalanceSnapshot;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopSnapshot;
import ru.javaroot.smpshop.shop.Bal;
import ru.javaroot.smpshop.shop.Shop;

import java.util.UUID;

final class ApiMapper {
    ShopSnapshot shop(Shop shop) {
        ShopPosition first = new ShopPosition(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
        ShopPosition second = shop.x2() == null ? null : new ShopPosition(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
        return new ShopSnapshot(
                shop.id(), shop.ownerId(), shop.ownerName(), first, second,
                shop.price(), shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt()
        );
    }

    BalanceSnapshot balance(UUID playerId, Bal bal) {
        return new BalanceSnapshot(playerId, new CurrencyAmounts(bal.diamondOre(), bal.deepslateDiamondOre()));
    }
}
