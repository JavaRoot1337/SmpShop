package ru.javaroot.smpshop.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.DoubleChestInventory;
import ru.javaroot.api.ApiStatus;
import ru.javaroot.api.ShopCreate;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopPriceChange;
import ru.javaroot.api.ShopRemove;
import ru.javaroot.api.ShopResult;
import ru.javaroot.api.event.ShopChangedEvent;
import ru.javaroot.api.event.ShopCreatePrepareEvent;
import ru.javaroot.api.event.ShopRemovePrepareEvent;
import ru.javaroot.smpshop.cfg.Cfg;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.ShopRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopKey;
import ru.javaroot.smpshop.shop.ShopMgr;
import ru.javaroot.smpshop.trade.TradeSvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class ApiShopSvc {
    private final ShopMgr shops;
    private final ShopRepo shopRepo;
    private final TradeSvc trade;
    private final Exec exec;
    private final CfgLoad cfgLoad;
    private final ApiMapper mapper;
    private final Supplier<ApiStatus> unavailable;
    private final ConcurrentMap<OpKey, CompletableFuture<ShopResult>> ops = new ConcurrentHashMap<>();

    ApiShopSvc(ShopMgr shops, ShopRepo shopRepo, TradeSvc trade, Exec exec, CfgLoad cfgLoad,
               ApiMapper mapper, Supplier<ApiStatus> unavailable) {
        this.shops = shops;
        this.shopRepo = shopRepo;
        this.trade = trade;
        this.exec = exec;
        this.cfgLoad = cfgLoad;
        this.mapper = mapper;
        this.unavailable = unavailable;
    }

    CompletableFuture<ShopResult> createShop(ShopCreate req) {
        return runOp(req.operationId(), req.source().asString(), () -> create(req));
    }

    CompletableFuture<ShopResult> changePrice(ShopPriceChange req) {
        return runOp(req.operationId(), req.source().asString(), () -> changePrice0(req));
    }

    CompletableFuture<ShopResult> removeShop(ShopRemove req) {
        return runOp(req.operationId(), req.source().asString(), () -> remove(req));
    }

    void clearOperations() {
        ops.values().forEach(op -> op.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null)));
        ops.clear();
    }

    private CompletableFuture<ShopResult> create(ShopCreate req) {
        ApiStatus st = unavailable.get();
        if (st != null) {
            return CompletableFuture.completedFuture(new ShopResult(st, null));
        }
        Cfg cfg = cfgLoad.cfg();
        String hash = "create:" + req.ownerId() + ':' + req.ownerName() + ':'
                + req.first() + ':' + req.second() + ':' + req.price();
        if (req.ownerName().isBlank() || req.price() < cfg.minPrice() || req.price() > cfg.maxPrice()
                || (req.second() != null && (!req.second().worldId().equals(req.first().worldId())
                || req.second().equals(req.first())))) {
            return status(req.operationId(), req.source().asString(), hash, "CREATE", ApiStatus.INVALID_INPUT, null);
        }
        return shopRepo.apiResult(req.operationId(), req.source().asString(), hash)
                .thenCompose(prior -> prior == null ? scheduleCreate(req, hash) : CompletableFuture.completedFuture(result(prior)));
    }

    private CompletableFuture<ShopResult> scheduleCreate(ShopCreate req, String hash) {
        CompletableFuture<ShopResult> fut = new CompletableFuture<>();
        if (exec.global(() -> {
            World w = Bukkit.getWorld(req.first().worldId());
            if (w == null) {
                forward(status(req.operationId(), req.source().asString(), hash, "CREATE", ApiStatus.NOT_FOUND, null), fut);
                return;
            }
            Location first = new Location(w, req.first().x(), req.first().y(), req.first().z());
            if (exec.region(first, () -> createOnRegion(req, hash, w, fut)) != Exec.Result.SCHEDULED) {
                fut.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
            }
        }) != Exec.Result.SCHEDULED) {
            fut.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
        }
        return fut;
    }

    private void createOnRegion(ShopCreate req, String hash, World w, CompletableFuture<ShopResult> fut) {
        List<ShopKey> keys = keys(req);
        if (!validChest(w, req)) {
            forward(status(req.operationId(), req.source().asString(), hash, "CREATE", ApiStatus.NOT_FOUND, null), fut);
            return;
        }
        int limit = cfgLoad.cfg().maxPerPlayer();
        if (!shops.tryReserve(req.ownerId(), limit, keys)) {
            ApiStatus st = shops.count(req.ownerId()) >= limit ? ApiStatus.LIMIT_REACHED : ApiStatus.CONFLICT;
            forward(status(req.operationId(), req.source().asString(), hash, "CREATE", st, null), fut);
            return;
        }
        ShopCreatePrepareEvent prep = new ShopCreatePrepareEvent(req);
        Bukkit.getPluginManager().callEvent(prep);
        if (prep.isCancelled()) {
            shops.release(req.ownerId(), keys);
            forward(status(req.operationId(), req.source().asString(), hash, "CREATE", ApiStatus.CONFLICT, null), fut);
            return;
        }
        shopRepo.apiCreate(req.operationId(), req.source().asString(), hash, draft(req))
                .whenComplete((stored, err) -> {
                    if (err != null) {
                        releaseCreate(req.ownerId(), keys, () -> fut.completeExceptionally(err));
                        return;
                    }
                    if (!"SUCCESS".equals(stored.status()) || !stored.applied()) {
                        releaseCreate(req.ownerId(), keys, () -> fut.complete(result(stored)));
                        return;
                    }
                    if (exec.global(() -> {
                        try {
                            if (shops.find(stored.shop().id()) == null) {
                                shops.add(stored.shop());
                                Bukkit.getPluginManager().callEvent(new ShopChangedEvent(
                                        req.operationId(), ShopChangedEvent.ChangeType.CREATED, mapper.shop(stored.shop())));
                            }
                            fut.complete(result(stored));
                        } catch (RuntimeException ex) {
                            fut.completeExceptionally(ex);
                        } finally {
                            shops.release(req.ownerId(), keys);
                        }
                    }) != Exec.Result.SCHEDULED) {
                        shops.release(req.ownerId(), keys);
                        fut.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
                    }
                });
    }

    private void releaseCreate(UUID ownerId, List<ShopKey> keys, Runnable done) {
        if (exec.global(() -> {
            shops.release(ownerId, keys);
            done.run();
        }) != Exec.Result.SCHEDULED) {
            shops.release(ownerId, keys);
            done.run();
        }
    }

    private CompletableFuture<ShopResult> changePrice0(ShopPriceChange req) {
        ApiStatus st = unavailable.get();
        if (st != null) {
            return CompletableFuture.completedFuture(new ShopResult(st, null));
        }
        Cfg cfg = cfgLoad.cfg();
        String hash = "price:" + req.shopId() + ':' + req.price();
        if (req.shopId() < 1 || req.price() < cfg.minPrice() || req.price() > cfg.maxPrice()) {
            return status(req.operationId(), req.source().asString(), hash, "PRICE", ApiStatus.INVALID_INPUT, null);
        }
        return shopRepo.apiUpdatePrice(req.operationId(), req.source().asString(), hash, req.shopId(), req.price())
                .thenCompose(stored -> {
                    if (!"SUCCESS".equals(stored.status()) || !stored.applied()) {
                        return CompletableFuture.completedFuture(result(stored));
                    }
                    CompletableFuture<ShopResult> applied = new CompletableFuture<>();
                    if (exec.global(() -> {
                        Shop updated = shops.updatePrice(req.shopId(), req.price());
                        if (updated == null) {
                            applied.complete(result(stored));
                            return;
                        }
                        Bukkit.getPluginManager().callEvent(new ShopChangedEvent(
                                req.operationId(), ShopChangedEvent.ChangeType.PRICE_CHANGED, mapper.shop(updated)));
                        applied.complete(new ShopResult(ApiStatus.SUCCESS, mapper.shop(updated)));
                    }) != Exec.Result.SCHEDULED) {
                        applied.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, null));
                    }
                    return applied;
                });
    }

    private CompletableFuture<ShopResult> remove(ShopRemove req) {
        ApiStatus st = unavailable.get();
        if (st != null) {
            return CompletableFuture.completedFuture(new ShopResult(st, null));
        }
        String hash = "delete:" + req.shopId();
        if (req.shopId() < 1) {
            return status(req.operationId(), req.source().asString(), hash, "DELETE", ApiStatus.INVALID_INPUT, null);
        }
        return shopRepo.apiResult(req.operationId(), req.source().asString(), hash)
                .thenCompose(prior -> prior == null ? removeNew(req, hash) : CompletableFuture.completedFuture(result(prior)));
    }

    private CompletableFuture<ShopResult> removeNew(ShopRemove req, String hash) {
        Shop shop = shops.find(req.shopId());
        if (shop == null) {
            return status(req.operationId(), req.source().asString(), hash, "DELETE", ApiStatus.NOT_FOUND, null);
        }
        if (trade.isBusy(shop.id())) {
            return status(req.operationId(), req.source().asString(), hash, "DELETE", ApiStatus.BUSY, shop.id());
        }
        CompletableFuture<ShopResult> fut = new CompletableFuture<>();
        if (exec.global(() -> {
            Runnable prep = () -> removeAfterPrepare(req, shop, hash, fut);
            World w = Bukkit.getWorld(shop.worldId());
            if (w == null) {
                prep.run();
                return;
            }
            if (exec.region(new Location(w, shop.x1(), shop.y1(), shop.z1()), prep) != Exec.Result.SCHEDULED) {
                fut.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, mapper.shop(shop)));
            }
        }) != Exec.Result.SCHEDULED) {
            fut.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, mapper.shop(shop)));
        }
        return fut;
    }

    private void removeAfterPrepare(ShopRemove req, Shop shop, String hash, CompletableFuture<ShopResult> fut) {
        if (!trade.reserveRemoval(shop.id())) {
            forward(status(req.operationId(), req.source().asString(), hash, "DELETE", ApiStatus.BUSY, shop.id()), fut);
            return;
        }
        ShopRemovePrepareEvent prep = new ShopRemovePrepareEvent(req.operationId(), mapper.shop(shop));
        Bukkit.getPluginManager().callEvent(prep);
        if (prep.isCancelled()) {
            trade.releaseRemoval(shop.id());
            forward(status(req.operationId(), req.source().asString(), hash, "DELETE", ApiStatus.CONFLICT, shop.id()), fut);
            return;
        }
        shopRepo.apiDelete(req.operationId(), req.source().asString(), hash, req.shopId())
                .whenComplete((stored, err) -> {
                    if (err != null) {
                        trade.releaseRemoval(shop.id());
                        fut.completeExceptionally(err);
                        return;
                    }
                    if (!"SUCCESS".equals(stored.status()) || !stored.applied()) {
                        trade.releaseRemoval(shop.id());
                        fut.complete(result(stored));
                        return;
                    }
                    if (exec.global(() -> {
                        try {
                            shops.remove(shop);
                            Bukkit.getPluginManager().callEvent(new ShopChangedEvent(
                                    req.operationId(), ShopChangedEvent.ChangeType.REMOVED, mapper.shop(shop)));
                            fut.complete(result(stored));
                        } catch (RuntimeException ex) {
                            fut.completeExceptionally(ex);
                        } finally {
                            trade.releaseRemoval(shop.id());
                        }
                    }) != Exec.Result.SCHEDULED) {
                        trade.releaseRemoval(shop.id());
                        fut.complete(new ShopResult(ApiStatus.SHUTTING_DOWN, mapper.shop(shop)));
                    }
                });
    }

    private CompletableFuture<ShopResult> runOp(UUID opId, String src, Supplier<CompletableFuture<ShopResult>> act) {
        OpKey key = new OpKey(opId, src);
        CompletableFuture<ShopResult> existing = ops.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<ShopResult> created = new CompletableFuture<>();
        existing = ops.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        try {
            act.get().whenComplete((val, err) -> {
                ops.remove(key, created);
                if (err == null) {
                    created.complete(val);
                } else {
                    created.completeExceptionally(err);
                }
            });
        } catch (RuntimeException ex) {
            ops.remove(key, created);
            created.completeExceptionally(ex);
        }
        return created;
    }

    private CompletableFuture<ShopResult> status(UUID opId, String src, String hash, String type,
                                                 ApiStatus st, Long shopId) {
        return shopRepo.apiStatus(opId, src, hash, type, st.name(), shopId).thenApply(this::result);
    }

    private static void forward(CompletableFuture<ShopResult> src, CompletableFuture<ShopResult> dst) {
        src.whenComplete((val, err) -> {
            if (err == null) {
                dst.complete(val);
            } else {
                dst.completeExceptionally(err);
            }
        });
    }

    private ShopResult result(ShopRepo.ApiShopRes stored) {
        return new ShopResult(ApiStatus.valueOf(stored.status()),
                stored.shop() == null ? null : mapper.shop(stored.shop()));
    }

    private static Shop draft(ShopCreate req) {
        ShopPosition f = req.first();
        ShopPosition s = req.second();
        return new Shop(0, req.ownerId(), req.ownerName(), f.worldId(), f.x(), f.y(), f.z(),
                s == null ? null : s.x(), s == null ? null : s.y(), s == null ? null : s.z(),
                req.price(), 0, 0, 0, System.currentTimeMillis());
    }

    private static List<ShopKey> keys(ShopCreate req) {
        ShopPosition f = req.first();
        ShopPosition s = req.second();
        if (s == null) {
            return List.of(new ShopKey(f.worldId(), f.x(), f.y(), f.z()));
        }
        return List.of(
                new ShopKey(f.worldId(), f.x(), f.y(), f.z()),
                new ShopKey(s.worldId(), s.x(), s.y(), s.z())
        );
    }

    private static boolean validChest(World w, ShopCreate req) {
        ShopPosition f = req.first();
        if (w.getBlockAt(f.x(), f.y(), f.z()).getType() != Material.CHEST) {
            return false;
        }
        Chest chest = (Chest) w.getBlockAt(f.x(), f.y(), f.z()).getState();
        List<ShopKey> actual;
        if (chest.getInventory() instanceof DoubleChestInventory inv) {
            actual = Stream.of(inv.getLeftSide().getHolder(), inv.getRightSide().getHolder())
                    .filter(Chest.class::isInstance)
                    .map(Chest.class::cast)
                    .map(c -> ShopKey.of(c.getLocation()))
                    .toList();
        } else {
            actual = List.of(new ShopKey(f.worldId(), f.x(), f.y(), f.z()));
        }
        List<ShopKey> requested = keys(req);
        return actual.size() == requested.size() && actual.containsAll(requested);
    }

    private record OpKey(UUID opId, String src) {
    }
}
