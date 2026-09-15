package ru.javaroot.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.javaroot.SmpShop;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.data.ShopStore;
import ru.javaroot.gui.BalanceInv;
import ru.javaroot.gui.ShopListInv;
import ru.javaroot.list.ShopTrade;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class SmpShopApiImpl implements SmpShopApi {
    private final ShopMgr shops;
    private final BalanceInv balanceGui;
    private final ShopListInv listGui;
    private final Sched sched;
    private final ApiSnapshotMapper mapper;
    private final ApiBalanceSvc balanceService;
    private final ApiShopSvc shopService;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private volatile ApiState state = ApiState.STARTING;

    public SmpShopApiImpl(SmpShop plugin, ShopMgr shops, ShopStore shopStore, BalanceStore balances,
                          BalanceInv balanceGui, ShopListInv listGui, ShopTrade trade, Sched sched, Cfg cfg) {
        this(plugin, shops, shopStore, balances, balanceGui, listGui, trade, sched, cfg, new OtherMsg(plugin));
    }

    public SmpShopApiImpl(SmpShop plugin, ShopMgr shops, ShopStore shopStore, BalanceStore balances,
                          BalanceInv balanceGui, ShopListInv listGui, ShopTrade trade, Sched sched, Cfg cfg,
                          OtherMsg other) {
        this.shops = shops;
        this.balanceGui = balanceGui;
        this.listGui = listGui;
        this.sched = sched;
        mapper = new ApiSnapshotMapper();
        balanceService = new ApiBalanceSvc(balances, balanceGui, mapper, sched, this::unavailableStatus, other);
        shopService = new ApiShopSvc(shops, shopStore, trade, sched, cfg, mapper, this::unavailableStatus);
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public ApiState state() {
        return state;
    }

    @Override
    public boolean ready() {
        return state == ApiState.READY;
    }

    @Override
    public CompletionStage<Void> awaitReady() {
        return readyFuture;
    }

    @Override
    public Optional<ShopSnapshot> shop(long id) {
        return Optional.ofNullable(shops.find(id)).map(mapper::shop);
    }

    @Override
    public Optional<ShopSnapshot> shop(ShopPosition position) {
        ShopKey key = new ShopKey(position.worldId(), position.x(), position.y(), position.z());
        return Optional.ofNullable(shops.find(key)).map(mapper::shop);
    }

    @Override
    public List<ShopSnapshot> shops(UUID ownerId) {
        return shops.all().stream()
                .filter(shop -> shop.ownerId().equals(ownerId))
                .map(mapper::shop)
                .toList();
    }

    @Override
    public CompletionStage<BalanceSnapshot> balance(UUID playerId) {
        return balanceService.balance(playerId);
    }

    @Override
    public CompletionStage<BalanceResult> changeBalance(BalanceChange request) {
        return balanceService.changeBalance(request);
    }

    @Override
    public CompletionStage<BalanceResult> transferBalance(BalanceTransfer request) {
        return balanceService.transferBalance(request);
    }

    @Override
    public CompletionStage<ShopResult> createShop(ShopCreate request) {
        return shopService.createShop(request);
    }

    @Override
    public CompletionStage<ShopResult> changePrice(ShopPriceChange request) {
        return shopService.changePrice(request);
    }

    @Override
    public CompletionStage<ShopResult> removeShop(ShopRemove request) {
        return shopService.removeShop(request);
    }

    @Override
    public CompletionStage<OpenResult> openBalance(UUID playerId) {
        return open(playerId, player -> balanceGui.open(player));
    }

    @Override
    public CompletionStage<OpenResult> openShopList(UUID viewerId, UUID ownerId) {
        return open(viewerId, player -> listGui.open(player, ownerId));
    }

    public void markReady() {
        state = ApiState.READY;
        readyFuture.complete(null);
    }

    public void markFailed(Throwable error) {
        state = ApiState.FAILED;
        readyFuture.completeExceptionally(error);
    }

    public void close() {
        state = ApiState.SHUTTING_DOWN;
        readyFuture.completeExceptionally(new IllegalStateException("API SmpShop завершает работу"));
        shopService.clearOperations();
    }

    public void stopped() {
        state = ApiState.STOPPED;
    }

    private CompletionStage<OpenResult> open(UUID playerId, PlayerAction action) {
        ApiStatus unavailable = unavailableStatus();
        if (unavailable != null) {
            return CompletableFuture.completedFuture(new OpenResult(unavailable));
        }
        CompletableFuture<OpenResult> result = new CompletableFuture<>();
        if (sched.global(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                result.complete(new OpenResult(ApiStatus.NOT_FOUND));
                return;
            }
            Sched.ScheduleResult scheduled = sched.entity(player, () -> {
                action.run(player);
                result.complete(new OpenResult(ApiStatus.SUCCESS));
            }, () -> result.complete(new OpenResult(ApiStatus.NOT_FOUND)));
            if (scheduled == Sched.ScheduleResult.REJECTED) {
                result.complete(new OpenResult(ApiStatus.SHUTTING_DOWN));
            }
        }) != Sched.ScheduleResult.SCHEDULED) {
            result.complete(new OpenResult(ApiStatus.SHUTTING_DOWN));
        }
        return result;
    }

    private ApiStatus unavailableStatus() {
        return switch (state) {
            case READY -> null;
            case STARTING, FAILED -> ApiStatus.NOT_READY;
            case SHUTTING_DOWN, STOPPED -> ApiStatus.SHUTTING_DOWN;
        };
    }

    @FunctionalInterface
    private interface PlayerAction {
        void run(Player player);
    }
}
