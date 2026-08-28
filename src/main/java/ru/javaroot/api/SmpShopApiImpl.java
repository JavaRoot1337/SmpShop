package ru.javaroot.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.block.Chest;
import org.bukkit.inventory.DoubleChestInventory;
import ru.javaroot.SmpShop;
import ru.javaroot.config.Cfg;
import ru.javaroot.api.event.BalanceChangedEvent;
import ru.javaroot.api.event.ShopChangedEvent;
import ru.javaroot.api.event.ShopCreatePrepareEvent;
import ru.javaroot.api.event.ShopRemovePrepareEvent;
import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SmpShopApiImpl implements SmpShopApi {
    private final SmpShop plugin;
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final BalanceStore balances;
    private final BalanceInv balanceGui;
    private final ShopListInv listGui;
    private final ShopTrade trade;
    private final Sched sched;
    private final Cfg cfg;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private final ConcurrentMap<OperationKey, CompletableFuture<ShopResult>> shopOperations = new ConcurrentHashMap<>();
    private volatile ApiState state = ApiState.STARTING;

    public SmpShopApiImpl(SmpShop plugin, ShopMgr shops, ShopStore shopStore, BalanceStore balances,
                          BalanceInv balanceGui, ShopListInv listGui, ShopTrade trade, Sched sched, Cfg cfg) {
        this.plugin = plugin;
        this.shops = shops;
        this.shopStore = shopStore;
        this.balances = balances;
        this.balanceGui = balanceGui;
        this.listGui = listGui;
        this.trade = trade;
        this.sched = sched;
        this.cfg = cfg;
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
        return Optional.ofNullable(shops.find(id)).map(this::snapshot);
    }

    @Override
    public Optional<ShopSnapshot> shop(ShopPosition position) {
        ShopKey key = new ShopKey(position.worldId(), position.x(), position.y(), position.z());
        return Optional.ofNullable(shops.find(key)).map(this::snapshot);
    }

    @Override
    public List<ShopSnapshot> shops(UUID ownerId) {
        return shops.all().stream()
                .filter(shop -> shop.ownerId().equals(ownerId))
                .map(this::snapshot)
                .toList();
    }

    @Override
    public CompletionStage<BalanceSnapshot> balance(UUID playerId) {
        if (!ready()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SmpShop API is not ready"));
        }
        return balances.load(playerId).thenApply(balance -> snapshot(playerId, balance));
    }

    @Override
    public CompletionStage<BalanceResult> changeBalance(BalanceChange request) {
        ApiStatus unavailable = unavailableStatus();
        if (unavailable != null) {
            return CompletableFuture.completedFuture(new BalanceResult(unavailable, null, null));
        }
        if (balanceGui.isOpen(request.playerId())) {
            return CompletableFuture.completedFuture(new BalanceResult(ApiStatus.BUSY, null, null));
        }
        Currency currency = currency(request.currency());
        return balances.change(request.operationId(), request.source().asString(), request.playerId(),
                        currency, request.amount())
                .thenApply(result -> {
                    BalanceSnapshot source = result.source() == null ? null : snapshot(request.playerId(), result.source());
                    ApiStatus status = status(result.status());
                    if (status == ApiStatus.SUCCESS && result.applied()) {
                        Bukkit.getPluginManager().callEvent(new BalanceChangedEvent(
                                request.operationId(), request.source(), source));
                    }
                    return new BalanceResult(status, source, null);
                });
    }

    @Override
    public CompletionStage<BalanceResult> transferBalance(BalanceTransfer request) {
        ApiStatus unavailable = unavailableStatus();
        if (unavailable != null) {
            return CompletableFuture.completedFuture(new BalanceResult(unavailable, null, null));
        }
        if (balanceGui.isOpen(request.fromPlayerId()) || balanceGui.isOpen(request.toPlayerId())) {
            return CompletableFuture.completedFuture(new BalanceResult(ApiStatus.BUSY, null, null));
        }
        return balances.transfer(request.operationId(), request.source().asString(), request.fromPlayerId(),
                        request.toPlayerId(), currency(request.currency()), request.amount())
                .thenApply(result -> {
                    BalanceSnapshot source = result.source() == null ? null : snapshot(request.fromPlayerId(), result.source());
                    BalanceSnapshot target = result.target() == null ? null : snapshot(request.toPlayerId(), result.target());
                    ApiStatus status = status(result.status());
                    if (status == ApiStatus.SUCCESS && result.applied()) {
                        Bukkit.getPluginManager().callEvent(new BalanceChangedEvent(
                                request.operationId(), request.source(), source));
                        Bukkit.getPluginManager().callEvent(new BalanceChangedEvent(
                                request.operationId(), request.source(), target));
                    }
                    return new BalanceResult(status, source, target);
                });
    }

    @Override
    public CompletionStage<ShopResult> createShop(ShopCreate request) {
        return shopOperation(request.operationId(), request.source().asString(), () -> create(request));
    }

    @Override
    public CompletionStage<ShopResult> changePrice(ShopPriceChange request) {
        return shopOperation(request.operationId(), request.source().asString(), () -> changePrice0(request));
    }

    @Override
    public CompletionStage<ShopResult> removeShop(ShopRemove request) {
        return shopOperation(request.operationId(), request.source().asString(), () -> remove(request));
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
        shopOperations.clear();
    }

    public void stopped() {
        state = ApiState.STOPPED;
    }

    private CompletableFuture<ShopResult> create(ShopCreate request) {
        ApiStatus unavailable = unavailableStatus();
        if (unavailable != null) {
            return CompletableFuture.completedFuture(new ShopResult(unavailable, null));
        }
        if (request.ownerName().isBlank()
                || request.price() < cfg.minPrice() || request.price() > cfg.maxPrice()
                || (request.second() != null
                && !request.second().worldId().equals(request.first().worldId()))) {
            return CompletableFuture.completedFuture(new ShopResult(ApiStatus.INVALID_INPUT, null));
        }
        String hash = "create:" + request.ownerId() + ':' + request.ownerName() + ':'
                + request.first() + ':' + request.second() + ':' + request.price();
        return shopStore.apiResult(request.operationId(), request.source().asString(), hash)
                .thenCompose(prior -> prior == null ? scheduleCreate(request, hash) : completed(prior));
    }

    private CompletableFuture<ShopResult> scheduleCreate(ShopCreate request, String hash) {
        CompletableFuture<ShopResult> result = new CompletableFuture<>();
        if (sched.global(() -> {
            World world = Bukkit.getWorld(request.first().worldId());
            if (world == null) {
                result.complete(new ShopResult(ApiStatus.NOT_FOUND, null));
                return;
            }
            Location first = location(world, request.first());
            if (sched.region(first, () -> createOnRegion(request, hash, world, result)) != Sched.ScheduleResult.SCHEDULED) {
                result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
            }
        }) != Sched.ScheduleResult.SCHEDULED) {
            result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
        }
        return result;
    }

    private void createOnRegion(ShopCreate request, String hash, World world,
                                CompletableFuture<ShopResult> result) {
        List<ShopKey> keys = keys(request);
        if (!validShopChest(world, request)) {
            result.complete(new ShopResult(ApiStatus.NOT_FOUND, null));
            return;
        }
        int limit = cfg.regularShopLimit();
        if (!shops.tryReserve(request.ownerId(), limit, keys)) {
            ApiStatus status = shops.count(request.ownerId()) >= limit
                    ? ApiStatus.LIMIT_REACHED : ApiStatus.CONFLICT;
            result.complete(new ShopResult(status, null));
            return;
        }
        ShopCreatePrepareEvent event = new ShopCreatePrepareEvent(request);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            shops.release(request.ownerId(), keys);
            result.complete(new ShopResult(ApiStatus.CONFLICT, null));
            return;
        }
        Shop draft = draft(request);
        shopStore.apiCreate(request.operationId(), request.source().asString(), hash, draft)
                .whenComplete((stored, error) -> {
                    if (error != null) {
                        shops.release(request.ownerId(), keys);
                        result.completeExceptionally(error);
                        return;
                    }
                    try {
                        if ("SUCCESS".equals(stored.status()) && stored.applied()
                                && shops.find(stored.shop().id()) == null) {
                            shops.add(stored.shop());
                            Bukkit.getPluginManager().callEvent(new ShopChangedEvent(request.operationId(),
                                    ShopChangedEvent.ChangeType.CREATED, snapshot(stored.shop())));
                        }
                        result.complete(result(stored));
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    } finally {
                        shops.release(request.ownerId(), keys);
                    }
                });
    }

    private CompletableFuture<ShopResult> changePrice0(ShopPriceChange request) {
        ApiStatus unavailable = unavailableStatus();
        if (unavailable != null) {
            return CompletableFuture.completedFuture(new ShopResult(unavailable, null));
        }
        if (request.shopId() < 1
                || request.price() < cfg.minPrice() || request.price() > cfg.maxPrice()) {
            return CompletableFuture.completedFuture(new ShopResult(ApiStatus.INVALID_INPUT, null));
        }
        String hash = "price:" + request.shopId() + ':' + request.price();
        return shopStore.apiUpdatePrice(request.operationId(), request.source().asString(), hash,
                        request.shopId(), request.price())
                .thenApply(stored -> {
                    if ("SUCCESS".equals(stored.status()) && stored.applied()) {
                        Shop updated = shops.updatePrice(request.shopId(), request.price());
                        if (updated != null) {
                            Bukkit.getPluginManager().callEvent(new ShopChangedEvent(request.operationId(),
                                    ShopChangedEvent.ChangeType.PRICE_CHANGED, snapshot(updated)));
                            return new ShopResult(ApiStatus.SUCCESS, snapshot(updated));
                        }
                    }
                    return result(stored);
                });
    }

    private CompletableFuture<ShopResult> remove(ShopRemove request) {
        ApiStatus unavailable = unavailableStatus();
        if (unavailable != null) {
            return CompletableFuture.completedFuture(new ShopResult(unavailable, null));
        }
        if (request.shopId() < 1) {
            return CompletableFuture.completedFuture(new ShopResult(ApiStatus.INVALID_INPUT, null));
        }
        String hash = "delete:" + request.shopId();
        return shopStore.apiResult(request.operationId(), request.source().asString(), hash)
                .thenCompose(prior -> prior == null ? removeNew(request, hash) : completed(prior));
    }

    private CompletableFuture<ShopResult> removeNew(ShopRemove request, String hash) {
        Shop shop = shops.find(request.shopId());
        if (shop == null) {
            return CompletableFuture.completedFuture(new ShopResult(ApiStatus.NOT_FOUND, null));
        }
        if (trade.isBusy(shop.id())) {
            return CompletableFuture.completedFuture(new ShopResult(ApiStatus.BUSY, snapshot(shop)));
        }
        CompletableFuture<ShopResult> result = new CompletableFuture<>();
        if (sched.global(() -> {
            Runnable prepare = () -> removeAfterPrepare(request, shop, hash, result);
            World world = Bukkit.getWorld(shop.worldId());
            if (world == null) {
                prepare.run();
                return;
            }
            if (sched.region(new Location(world, shop.x1(), shop.y1(), shop.z1()), prepare)
                    != Sched.ScheduleResult.SCHEDULED) {
                result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, snapshot(shop)));
            }
        }) != Sched.ScheduleResult.SCHEDULED) {
            result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, snapshot(shop)));
        }
        return result;
    }

    private void removeAfterPrepare(ShopRemove request, Shop shop, String hash,
                                    CompletableFuture<ShopResult> result) {
        ShopRemovePrepareEvent event = new ShopRemovePrepareEvent(request.operationId(), snapshot(shop));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            result.complete(new ShopResult(ApiStatus.CONFLICT, snapshot(shop)));
            return;
        }
        shopStore.apiDelete(request.operationId(), request.source().asString(), hash, request.shopId())
                .whenComplete((stored, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    if ("SUCCESS".equals(stored.status()) && stored.applied()) {
                        shops.remove(shop);
                        Bukkit.getPluginManager().callEvent(new ShopChangedEvent(request.operationId(),
                                ShopChangedEvent.ChangeType.REMOVED, snapshot(shop)));
                    }
                    result.complete(result(stored));
                });
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

    private CompletionStage<ShopResult> shopOperation(UUID operationId, String source,
                                                       ShopAction action) {
        OperationKey key = new OperationKey(operationId, source);
        CompletableFuture<ShopResult> existing = shopOperations.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<ShopResult> created = new CompletableFuture<>();
        existing = shopOperations.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        action.run().whenComplete((value, error) -> {
            shopOperations.remove(key, created);
            if (error == null) {
                created.complete(value);
            } else {
                created.completeExceptionally(error);
            }
        });
        return created;
    }

    private CompletableFuture<ShopResult> completed(ShopStore.ApiShopResult stored) {
        return CompletableFuture.completedFuture(result(stored));
    }

    private ShopResult result(ShopStore.ApiShopResult stored) {
        return new ShopResult(ApiStatus.valueOf(stored.status()),
                stored.shop() == null ? null : snapshot(stored.shop()));
    }

    private Shop draft(ShopCreate request) {
        ShopPosition first = request.first();
        ShopPosition second = request.second();
        return new Shop(0, request.ownerId(), request.ownerName(), first.worldId(), first.x(), first.y(), first.z(),
                second == null ? null : second.x(), second == null ? null : second.y(),
                second == null ? null : second.z(), request.price(), 0, 0, 0,
                System.currentTimeMillis(), true);
    }

    private List<ShopKey> keys(ShopCreate request) {
        ShopPosition first = request.first();
        ShopPosition second = request.second();
        if (second == null) {
            return List.of(new ShopKey(first.worldId(), first.x(), first.y(), first.z()));
        }
        return List.of(new ShopKey(first.worldId(), first.x(), first.y(), first.z()),
                new ShopKey(second.worldId(), second.x(), second.y(), second.z()));
    }

    private boolean validShopChest(World world, ShopCreate request) {
        ShopPosition first = request.first();
        if (world.getBlockAt(first.x(), first.y(), first.z()).getType() != Material.CHEST) {
            return false;
        }
        Chest chest = (Chest) world.getBlockAt(first.x(), first.y(), first.z()).getState();
        List<ShopKey> actual;
        if (chest.getInventory() instanceof DoubleChestInventory inventory) {
            actual = java.util.stream.Stream.of(inventory.getLeftSide().getHolder(), inventory.getRightSide().getHolder())
                    .filter(Chest.class::isInstance)
                    .map(Chest.class::cast)
                    .map(value -> ShopKey.of(value.getLocation()))
                    .toList();
        } else {
            actual = List.of(new ShopKey(first.worldId(), first.x(), first.y(), first.z()));
        }
        List<ShopKey> requested = keys(request);
        return actual.size() == requested.size() && actual.containsAll(requested);
    }

    private Location location(World world, ShopPosition position) {
        return new Location(world, position.x(), position.y(), position.z());
    }

    private ShopSnapshot snapshot(Shop shop) {
        ShopPosition first = new ShopPosition(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
        ShopPosition second = shop.x2() == null ? null
                : new ShopPosition(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
        return new ShopSnapshot(shop.id(), shop.ownerId(), shop.ownerName(), first, second,
                shop.price(), shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt());
    }

    private BalanceSnapshot snapshot(UUID playerId, Balance balance) {
        return new BalanceSnapshot(playerId,
                new CurrencyAmounts(balance.diamondOre(), balance.deepslateDiamondOre()));
    }

    private Currency currency(CurrencyType currency) {
        return currency == CurrencyType.DIAMOND_ORE ? Currency.DIAMOND_ORE : Currency.DEEPSLATE_DIAMOND_ORE;
    }

    private ApiStatus status(BalanceStore.MutationStatus status) {
        return ApiStatus.valueOf(status.name());
    }

    private ApiStatus unavailableStatus() {
        return switch (state) {
            case READY -> null;
            case STARTING, FAILED -> ApiStatus.NOT_READY;
            case SHUTTING_DOWN, STOPPED -> ApiStatus.SHUTTING_DOWN;
        };
    }

    private record OperationKey(UUID operationId, String source) {
    }

    @FunctionalInterface
    private interface ShopAction {
        CompletableFuture<ShopResult> run();
    }

    @FunctionalInterface
    private interface PlayerAction {
        void run(Player player);
    }
}
