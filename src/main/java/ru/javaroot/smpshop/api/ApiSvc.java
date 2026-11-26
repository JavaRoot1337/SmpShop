package ru.javaroot.smpshop.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.javaroot.api.ApiState;
import ru.javaroot.api.ApiStatus;
import ru.javaroot.api.BalanceChange;
import ru.javaroot.api.BalanceResult;
import ru.javaroot.api.BalanceSnapshot;
import ru.javaroot.api.BalanceTransfer;
import ru.javaroot.api.OpenResult;
import ru.javaroot.api.ShopCreate;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopPriceChange;
import ru.javaroot.api.ShopRemove;
import ru.javaroot.api.ShopResult;
import ru.javaroot.api.ShopSnapshot;
import ru.javaroot.api.SmpShopApi;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.BalRepo;
import ru.javaroot.smpshop.db.ShopRepo;
import ru.javaroot.smpshop.gui.BalGui;
import ru.javaroot.smpshop.gui.ListGui;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.ShopKey;
import ru.javaroot.smpshop.shop.ShopMgr;
import ru.javaroot.smpshop.trade.TradeSvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class ApiSvc implements SmpShopApi {
    private final ShopMgr shops;
    private final BalGui balGui;
    private final ListGui listGui;
    private final Exec exec;
    private final ApiMapper mapper = new ApiMapper();
    private final ApiBalSvc balSvc;
    private final ApiShopSvc shopSvc;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private volatile ApiState state = ApiState.STARTING;

    public ApiSvc(ShopMgr shops, ShopRepo shopRepo, BalRepo balances, BalGui balGui,
                  ListGui listGui, TradeSvc trade, Exec exec, CfgLoad cfgLoad) {
        this.shops = shops;
        this.balGui = balGui;
        this.listGui = listGui;
        this.exec = exec;
        this.balSvc = new ApiBalSvc(balances, balGui, mapper, exec, this::unavailableStatus);
        this.shopSvc = new ApiShopSvc(shops, shopRepo, trade, exec, cfgLoad, mapper, this::unavailableStatus);
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
    public Optional<ShopSnapshot> shop(ShopPosition pos) {
        return Optional.ofNullable(shops.find(new ShopKey(pos.worldId(), pos.x(), pos.y(), pos.z()))).map(mapper::shop);
    }

    @Override
    public List<ShopSnapshot> shops(UUID ownerId) {
        return shops.all().stream()
                .filter(s -> s.ownerId().equals(ownerId))
                .map(mapper::shop)
                .toList();
    }

    @Override
    public CompletionStage<BalanceSnapshot> balance(UUID playerId) {
        return balSvc.balance(playerId);
    }

    @Override
    public CompletionStage<BalanceResult> changeBalance(BalanceChange req) {
        return balSvc.changeBalance(req);
    }

    @Override
    public CompletionStage<BalanceResult> transferBalance(BalanceTransfer req) {
        return balSvc.transferBalance(req);
    }

    @Override
    public CompletionStage<ShopResult> createShop(ShopCreate req) {
        return shopSvc.createShop(req);
    }

    @Override
    public CompletionStage<ShopResult> changePrice(ShopPriceChange req) {
        return shopSvc.changePrice(req);
    }

    @Override
    public CompletionStage<ShopResult> removeShop(ShopRemove req) {
        return shopSvc.removeShop(req);
    }

    @Override
    public CompletionStage<OpenResult> openBalance(UUID playerId) {
        return openMenu(playerId, balGui::open);
    }

    @Override
    public CompletionStage<OpenResult> openShopList(UUID viewerId, UUID ownerId) {
        return openMenu(viewerId, p -> listGui.open(p, ownerId));
    }

    public void markReady() {
        state = ApiState.READY;
        readyFuture.complete(null);
    }

    public void markFailed(Throwable err) {
        state = ApiState.FAILED;
        readyFuture.completeExceptionally(err);
    }

    public void close() {
        state = ApiState.SHUTTING_DOWN;
        readyFuture.completeExceptionally(new IllegalStateException("API SmpShop завершает работу"));
        shopSvc.clearOperations();
    }

    public void stopped() {
        state = ApiState.STOPPED;
    }

    private CompletionStage<OpenResult> openMenu(UUID playerId, Consumer<Player> action) {
        ApiStatus st = unavailableStatus();
        if (st != null) {
            return CompletableFuture.completedFuture(new OpenResult(st));
        }
        CompletableFuture<OpenResult> fut = new CompletableFuture<>();
        if (exec.global(() -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null) {
                fut.complete(new OpenResult(ApiStatus.NOT_FOUND));
                return;
            }
            Exec.Result res = exec.entity(p, () -> {
                action.accept(p);
                fut.complete(new OpenResult(ApiStatus.SUCCESS));
            }, () -> fut.complete(new OpenResult(ApiStatus.NOT_FOUND)));
            if (res == Exec.Result.REJECTED) {
                fut.complete(new OpenResult(ApiStatus.SHUTTING_DOWN));
            }
        }) != Exec.Result.SCHEDULED) {
            fut.complete(new OpenResult(ApiStatus.SHUTTING_DOWN));
        }
        return fut;
    }

    private ApiStatus unavailableStatus() {
        return switch (state) {
            case READY -> null;
            case STARTING, FAILED -> ApiState.NOT_READY;
            case SHUTTING_DOWN, STOPPED -> ApiState.SHUTTING_DOWN;
        };
    }
}
