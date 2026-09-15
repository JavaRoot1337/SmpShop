package ru.javaroot.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.DoubleChestInventory;
import ru.javaroot.api.event.ShopChangedEvent;
import ru.javaroot.api.event.ShopCreatePrepareEvent;
import ru.javaroot.api.event.ShopRemovePrepareEvent;
import ru.javaroot.config.Cfg;
import ru.javaroot.data.ShopStore;
import ru.javaroot.list.ShopTrade;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

class ApiShopSvc {
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final ShopTrade trade;
    private final Sched sched;
    private final Cfg cfg;
    private final ApiSnapshotMapper mapper;
    private final Supplier<ApiStatus> unavailable;
    private final ConcurrentMap<OperationKey, CompletableFuture<ShopResult>> operations = new ConcurrentHashMap<>();

    ApiShopSvc(ShopMgr shops, ShopStore shopStore, ShopTrade trade, Sched sched, Cfg cfg,
                   ApiSnapshotMapper mapper, Supplier<ApiStatus> unavailable) {
        this.shops = shops;
        this.shopStore = shopStore;
        this.trade = trade;
        this.sched = sched;
        this.cfg = cfg;
        this.mapper = mapper;
        this.unavailable = unavailable;
    }

    CompletableFuture<ShopResult> createShop(ShopCreate request) {
        return operation(request.operationId(), request.source().asString(), () -> create(request));
    }

    CompletableFuture<ShopResult> changePrice(ShopPriceChange request) {
        return operation(request.operationId(), request.source().asString(), () -> changePrice0(request));
    }

    CompletableFuture<ShopResult> removeShop(ShopRemove request) {
        return operation(request.operationId(), request.source().asString(), () -> remove(request));
    }

    void clearOperations() {
        operations.values().forEach(operation -> operation.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null)));
        operations.clear();
    }

    private CompletableFuture<ShopResult> create(ShopCreate request) {
        ApiStatus unavailableStatus = unavailable.get();
        if (unavailableStatus != null) {
            return CompletableFuture.completedFuture(new ShopResult(unavailableStatus, null));
        }
        String hash = "create:" + request.ownerId() + ':' + request.ownerName() + ':'
                + request.first() + ':' + request.second() + ':' + request.price();
        if (request.ownerName().isBlank()
                || request.price() < cfg.minPrice() || request.price() > cfg.maxPrice()
                || (request.second() != null
                && (!request.second().worldId().equals(request.first().worldId())
                || request.second().equals(request.first())))) {
            return status(request.operationId(), request.source().asString(), hash,
                    "CREATE", ApiStatus.INVALID_INPUT, null);
        }
        return shopStore.apiResult(request.operationId(), request.source().asString(), hash)
                .thenCompose(prior -> prior == null ? scheduleCreate(request, hash) : completed(prior));
    }

    private CompletableFuture<ShopResult> scheduleCreate(ShopCreate request, String hash) {
        CompletableFuture<ShopResult> result = new CompletableFuture<>();
        if (sched.global(() -> {
            World world = Bukkit.getWorld(request.first().worldId());
            if (world == null) {
                forward(status(request.operationId(), request.source().asString(), hash,
                        "CREATE", ApiStatus.NOT_FOUND, null), result);
                return;
            }
            Location first = location(world, request.first());
            if (sched.region(first, () -> createOnRegion(request, hash, world, result))
                    != Sched.ScheduleResult.SCHEDULED) {
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
            forward(status(request.operationId(), request.source().asString(), hash,
                    "CREATE", ApiStatus.NOT_FOUND, null), result);
            return;
        }
        int limit = cfg.regularShopLimit();
        if (!shops.tryReserve(request.ownerId(), limit, keys)) {
            ApiStatus status = shops.count(request.ownerId()) >= limit
                    ? ApiStatus.LIMIT_REACHED : ApiStatus.CONFLICT;
            forward(status(request.operationId(), request.source().asString(), hash,
                    "CREATE", status, null), result);
            return;
        }
        ShopCreatePrepareEvent event = new ShopCreatePrepareEvent(request);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            shops.release(request.ownerId(), keys);
            forward(status(request.operationId(), request.source().asString(), hash,
                    "CREATE", ApiStatus.CONFLICT, null), result);
            return;
        }
        Shop draft = draft(request);
        shopStore.apiCreate(request.operationId(), request.source().asString(), hash, draft)
                .whenComplete((stored, error) -> {
                    if (error != null) {
                        releaseCreate(request.ownerId(), keys,
                                () -> result.completeExceptionally(error));
                        return;
                    }
                    if (!"SUCCESS".equals(stored.status()) || !stored.applied()) {
                        releaseCreate(request.ownerId(), keys, () -> result.complete(result(stored)));
                        return;
                    }
                    if (sched.global(() -> {
                        try {
                            if (shops.find(stored.shop().id()) == null) {
                                shops.add(stored.shop());
                                Bukkit.getPluginManager().callEvent(new ShopChangedEvent(request.operationId(),
                                        ShopChangedEvent.ChangeType.CREATED, mapper.shop(stored.shop())));
                            }
                            result.complete(result(stored));
                        } catch (RuntimeException exception) {
                            result.completeExceptionally(exception);
                        } finally {
                            shops.release(request.ownerId(), keys);
                        }
                    }) != Sched.ScheduleResult.SCHEDULED) {
                        shops.release(request.ownerId(), keys);
                        result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
                    }
                });
    }

    private void releaseCreate(UUID ownerId, List<ShopKey> keys, Runnable completed) {
        if (sched.global(() -> {
            shops.release(ownerId, keys);
            completed.run();
        }) != Sched.ScheduleResult.SCHEDULED) {
            shops.release(ownerId, keys);
            completed.run();
        }
    }

    private CompletableFuture<ShopResult> changePrice0(ShopPriceChange request) {
        ApiStatus unavailableStatus = unavailable.get();
        if (unavailableStatus != null) {
            return CompletableFuture.completedFuture(new ShopResult(unavailableStatus, null));
        }
        String hash = "price:" + request.shopId() + ':' + request.price();
        if (request.shopId() < 1
                || request.price() < cfg.minPrice() || request.price() > cfg.maxPrice()) {
            return status(request.operationId(), request.source().asString(), hash,
                    "PRICE", ApiStatus.INVALID_INPUT, null);
        }
        return shopStore.apiUpdatePrice(request.operationId(), request.source().asString(), hash,
                        request.shopId(), request.price())
                .thenCompose(stored -> {
                    if (!"SUCCESS".equals(stored.status()) || !stored.applied()) {
                        return CompletableFuture.completedFuture(result(stored));
                    }
                    CompletableFuture<ShopResult> applied = new CompletableFuture<>();
                    if (sched.global(() -> {
                        Shop updated = shops.updatePrice(request.shopId(), request.price());
                        if (updated == null) {
                            applied.complete(result(stored));
                            return;
                        }
                        Bukkit.getPluginManager().callEvent(new ShopChangedEvent(request.operationId(),
                                ShopChangedEvent.ChangeType.PRICE_CHANGED, mapper.shop(updated)));
                        applied.complete(new ShopResult(ApiStatus.SUCCESS, mapper.shop(updated)));
                    }) != Sched.ScheduleResult.SCHEDULED) {
                        applied.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
                    }
                    return applied;
                });
    }

    private CompletableFuture<ShopResult> remove(ShopRemove request) {
        ApiStatus unavailableStatus = unavailable.get();
        if (unavailableStatus != null) {
            return CompletableFuture.completedFuture(new ShopResult(unavailableStatus, null));
        }
        String hash = "delete:" + request.shopId();
        if (request.shopId() < 1) {
            return status(request.operationId(), request.source().asString(), hash,
                    "DELETE", ApiStatus.INVALID_INPUT, null);
        }
        return shopStore.apiResult(request.operationId(), request.source().asString(), hash)
                .thenCompose(prior -> prior == null ? removeNew(request, hash) : completed(prior));
    }

    private CompletableFuture<ShopResult> removeNew(ShopRemove request, String hash) {
        Shop shop = shops.find(request.shopId());
        if (shop == null) {
            return status(request.operationId(), request.source().asString(), hash,
                    "DELETE", ApiStatus.NOT_FOUND, null);
        }
        if (trade.isBusy(shop.id())) {
            return status(request.operationId(), request.source().asString(), hash,
                    "DELETE", ApiStatus.BUSY, shop.id());
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
                result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, mapper.shop(shop)));
            }
        }) != Sched.ScheduleResult.SCHEDULED) {
            result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, mapper.shop(shop)));
        }
        return result;
    }

    private void removeAfterPrepare(ShopRemove request, Shop shop, String hash,
                                    CompletableFuture<ShopResult> result) {
        if (!trade.reserveRemoval(shop.id())) {
            forward(status(request.operationId(), request.source().asString(), hash,
                    "DELETE", ApiStatus.BUSY, shop.id()), result);
            return;
        }
        ShopRemovePrepareEvent event = new ShopRemovePrepareEvent(request.operationId(), mapper.shop(shop));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            trade.releaseRemoval(shop.id());
            forward(status(request.operationId(), request.source().asString(), hash,
                    "DELETE", ApiStatus.CONFLICT, shop.id()), result);
            return;
        }
        shopStore.apiDelete(request.operationId(), request.source().asString(), hash, request.shopId())
                .whenComplete((stored, error) -> {
                    if (error != null) {
                        trade.releaseRemoval(shop.id());
                        result.completeExceptionally(error);
                        return;
                    }
                    if (!"SUCCESS".equals(stored.status()) || !stored.applied()) {
                        trade.releaseRemoval(shop.id());
                        result.complete(result(stored));
                        return;
                    }
                    if (sched.global(() -> {
                        try {
                            shops.remove(shop);
                            Bukkit.getPluginManager().callEvent(new ShopChangedEvent(request.operationId(),
                                    ShopChangedEvent.ChangeType.REMOVED, mapper.shop(shop)));
                            result.complete(result(stored));
                        } catch (RuntimeException exception) {
                            result.completeExceptionally(exception);
                        } finally {
                            trade.releaseRemoval(shop.id());
                        }
                    }) != Sched.ScheduleResult.SCHEDULED) {
                        trade.releaseRemoval(shop.id());
                        result.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, mapper.shop(shop)));
                    }
                });
    }

    private CompletableFuture<ShopResult> operation(UUID operationId, String source,
                                                     ShopAction action) {
        OperationKey key = new OperationKey(operationId, source);
        CompletableFuture<ShopResult> existing = operations.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<ShopResult> created = new CompletableFuture<>();
        existing = operations.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        try {
            action.run().whenComplete((value, error) -> {
                operations.remove(key, created);
                if (error == null) {
                    created.complete(value);
                } else {
                    created.completeExceptionally(error);
                }
            });
        } catch (RuntimeException exception) {
            operations.remove(key, created);
            created.completeExceptionally(exception);
        }
        return created;
    }

    private CompletableFuture<ShopResult> status(UUID operationId, String source, String hash,
                                                  String type, ApiStatus status, Long shopId) {
        return shopStore.apiStatus(operationId, source, hash, type, status.name(), shopId)
                .thenApply(this::result);
    }

    private void forward(CompletableFuture<ShopResult> source, CompletableFuture<ShopResult> target) {
        source.whenComplete((value, error) -> {
            if (error == null) {
                target.complete(value);
            } else {
                target.completeExceptionally(error);
            }
        });
    }

    private CompletableFuture<ShopResult> completed(ShopStore.ApiShopResult stored) {
        return CompletableFuture.completedFuture(result(stored));
    }

    private ShopResult result(ShopStore.ApiShopResult stored) {
        return new ShopResult(ApiStatus.valueOf(stored.status()),
                stored.shop() == null ? null : mapper.shop(stored.shop()));
    }

    private Shop draft(ShopCreate request) {
        ShopPosition first = request.first();
        ShopPosition second = request.second();
        return new Shop(0, request.ownerId(), request.ownerName(), first.worldId(), first.x(), first.y(), first.z(),
                second == null ? null : second.x(), second == null ? null : second.y(),
                second == null ? null : second.z(), request.price(), 0, 0, 0,
                System.currentTimeMillis());
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

    private record OperationKey(UUID operationId, String source) {
    }

    @FunctionalInterface
    private interface ShopAction {
        CompletableFuture<ShopResult> run();
    }
}
