package ru.javaroot.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface SmpShopApi {
    int VERSION = 1;

    int version();

    ApiState state();

    boolean ready();

    CompletionStage<Void> awaitReady();

    Optional<ShopSnapshot> shop(long id);

    Optional<ShopSnapshot> shop(ShopPosition position);

    List<ShopSnapshot> shops(UUID ownerId);

    CompletionStage<BalanceSnapshot> balance(UUID playerId);

    CompletionStage<BalanceResult> changeBalance(BalanceChange request);

    CompletionStage<BalanceResult> transferBalance(BalanceTransfer request);

    CompletionStage<ShopResult> createShop(ShopCreate request);

    CompletionStage<ShopResult> changePrice(ShopPriceChange request);

    CompletionStage<ShopResult> removeShop(ShopRemove request);

    CompletionStage<OpenResult> openBalance(UUID playerId);

    CompletionStage<OpenResult> openShopList(UUID viewerId, UUID ownerId);
}
