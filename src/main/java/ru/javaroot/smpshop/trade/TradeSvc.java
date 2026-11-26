package ru.javaroot.smpshop.trade;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.BalRepo;
import ru.javaroot.smpshop.db.TxRepo;
import ru.javaroot.smpshop.hook.HookHub;
import ru.javaroot.smpshop.notify.NotifySvc;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.plat.SoundSvc;
import ru.javaroot.smpshop.shop.CurType;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopKey;
import ru.javaroot.smpshop.shop.ShopMgr;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TradeSvc {
    private final Logger log;
    private final ShopMgr shops;
    private final TxRepo txRepo;
    private final BalRepo balances;
    private final CfgLoad cfgLoad;
    private final Exec exec;
    private final TradeStore store = new TradeStore();
    private final TradePaySvc paySvc = new TradePaySvc();
    private final TradeDelivSvc delivSvc;
    private final TradePurchSvc purchSvc;

    public TradeSvc(Plugin plug, ShopMgr shops, TxRepo txRepo, BalRepo balances, HookHub hooks,
                    NotifySvc notify, SoundSvc sounds, CfgLoad cfgLoad, Exec exec) {
        this.log = plug.getLogger();
        this.shops = shops;
        this.txRepo = txRepo;
        this.balances = balances;
        this.cfgLoad = cfgLoad;
        this.exec = exec;
        this.delivSvc = new TradeDelivSvc(plug, shops, txRepo, exec, store);
        this.purchSvc = new TradePurchSvc(log, shops, txRepo, hooks, notify, sounds, cfgLoad, exec,
                store, paySvc, delivSvc);
    }

    public boolean isBusy(long shopId) {
        return store.isBusy(shopId);
    }

    public boolean reserveRemoval(long shopId) {
        return store.reserveRemoval(shopId);
    }

    public void releaseRemoval(long shopId) {
        store.releaseRemoval(shopId);
    }

    public void start() {
        store.start();
    }

    public void stopInput() {
        store.stop();
    }

    public CompletableFuture<Void> recover() {
        return txRepo.preparedTransactions().thenCompose(txs -> {
            List<CompletableFuture<Void>> futures = txs.stream().map(delivSvc::recover).toList();
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        });
    }

    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (UUID playerId : store.playerIds()) {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || exec.ownsEntity(p)) {
                futures.add(close(playerId, p, true));
                continue;
            }
            CompletableFuture<Void> closing = new CompletableFuture<>();
            futures.add(closing);
            exec.entity(p,
                    () -> close(playerId, p, true).whenComplete((ignored, err) -> complete(closing, err)),
                    () -> close(playerId, null, true).whenComplete((ignored, err) -> complete(closing, err)));
        }
        for (UUID playerId : store.pendingReturnPlayerIds()) {
            futures.add(depositPendingReturn(playerId));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (!store.accepting() && (holder instanceof Chest || holder instanceof DoubleChest)) {
            event.setCancelled(true);
            return;
        }
        Shop shop = findShop(event.getInventory());
        if (shop == null) {
            return;
        }
        if (shop.ownerId().equals(p.getUniqueId())) {
            if (store.isBusy(shop.id())) {
                event.setCancelled(true);
                cfgLoad.msg().send(p, "shop-busy");
            }
            return;
        }
        if (store.isBusy(shop.id()) || store.get(p.getUniqueId()) != null) {
            event.setCancelled(true);
            cfgLoad.msg().send(p, "shop-busy");
            return;
        }
        if (event.getInventory().getViewers().stream().anyMatch(v -> v.getUniqueId().equals(shop.ownerId()))) {
            event.setCancelled(true);
            cfgLoad.msg().send(p, "shop-busy");
            return;
        }
        World w = Bukkit.getWorld(shop.worldId());
        if (w == null) {
            event.setCancelled(true);
            return;
        }
        TradeSess sess = new TradeSess(shop, event.getInventory(), event.getView().title(),
                new Location(w, shop.x1(), shop.y1(), shop.z1()));
        if (!store.reserve(shop.id())) {
            event.setCancelled(true);
            cfgLoad.msg().send(p, "shop-busy");
            return;
        }
        if (!store.putIfAbsent(p.getUniqueId(), sess)) {
            store.release(shop.id());
            event.setCancelled(true);
            cfgLoad.msg().send(p, "shop-busy");
            return;
        }
        event.setCancelled(true);
        exec.entity(p, () -> {
            if (store.get(p.getUniqueId()) == sess) {
                p.openInventory(sess.getInventory());
            }
        }, () -> close(p.getUniqueId(), null, false));
    }

    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        TradeSess sess = store.get(p.getUniqueId());
        String delivMarker = delivSvc.marker(event.getCursor());
        boolean pendingCursor = sess != null && sess.purchasePending
                && delivMarker != null && delivMarker.equals(TradeMarkers.pending(sess.txId));
        if ((delivSvc.isDeliveryItem(event.getCurrentItem()) || delivMarker != null) && !pendingCursor) {
            if (delivMarker != null && delivMarker.startsWith(TradeMarkers.PENDING_PREFIX)) {
                event.setCancelled(true);
                return;
            }
            if (delivMarker != null && delivSvc.isDeliveryPlacement(event)) {
                event.setCancelled(false);
                delivSvc.checkPlacement(p, delivMarker);
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (sess == null || event.getView().getTopInventory().getHolder() != sess) {
            if (pendingCursor) {
                event.setCancelled(true);
            }
            return;
        }
        if (sess.purchasePending) {
            event.setCancelled(true);
            purchSvc.handlePendingClick(event, p, sess);
            return;
        }
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0) {
            return;
        }
        if (raw >= sess.getInventory().getSize()) {
            if (paySvc.isCurrencyPickup(event) || paySvc.isCurrencyPlacement(event)) {
                event.setCancelled(false);
            } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                paySvc.movePaymentItem(event, sess);
            }
            return;
        }
        if (paySvc.isPaymentPickup(event, sess) || paySvc.isPaymentClick(event, sess)) {
            event.setCancelled(false);
            return;
        }
        if (paySvc.isPaymentShiftPickup(event, sess)) {
            paySvc.movePaymentToPlayer(event, p);
            return;
        }
        if (isProductPickup(event, sess)) {
            purchSvc.startPendingProduct(event, p, sess);
        }
    }

    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        TradeSess sess = store.get(p.getUniqueId());
        String delivMarker = delivSvc.marker(event.getOldCursor());
        boolean pendingCursor = sess != null && sess.purchasePending
                && delivMarker != null && delivMarker.equals(TradeMarkers.pending(sess.txId));
        if (delivMarker != null && !pendingCursor) {
            event.setCancelled(true);
            if (!delivMarker.startsWith(TradeMarkers.PENDING_PREFIX) && delivSvc.isDeliveryPlacement(event)) {
                event.setCancelled(false);
                delivSvc.checkPlacement(p, delivMarker);
            }
            return;
        }
        if (pendingCursor && (sess == null || event.getView().getTopInventory().getHolder() != sess)) {
            event.setCancelled(true);
            return;
        }
        if (sess != null && event.getView().getTopInventory().getHolder() == sess) {
            event.setCancelled(true);
            if (sess.purchasePending) {
                purchSvc.handlePendingDrag(event, p, sess);
                return;
            }
            if (paySvc.isPaymentDrag(event, sess)) {
                event.setCancelled(false);
            }
        }
    }

    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (delivSvc.isDeliveryItem(item) || delivSvc.isPendingItem(event.getPlayer(), item)) {
            event.setCancelled(true);
        }
    }

    public void onUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (delivSvc.isDeliveryItem(item) || delivSvc.isPendingItem(event.getPlayer(), item)) {
            event.setCancelled(true);
        }
    }

    public void onMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (delivSvc.isDeliveryItem(item) || delivSvc.isPendingItem(item)) {
            event.setCancelled(true);
        }
    }

    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (delivSvc.isDeliveryItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    public void onEntityPickup(EntityPickupItemEvent event) {
        if (delivSvc.isDeliveryItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(delivSvc::isDeliveryItem);
    }

    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (delivSvc.isDeliveryItem(event.getMainHandItem())
                || delivSvc.isDeliveryItem(event.getOffHandItem())
                || delivSvc.isPendingItem(p, event.getMainHandItem())
                || delivSvc.isPendingItem(p, event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p) {
            String delivMarker = delivSvc.marker(p.getItemOnCursor());
            close(p.getUniqueId(), p, false);
            if (delivMarker != null) {
                delivSvc.checkPlacement(p, delivMarker);
            }
        }
    }

    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        close(p.getUniqueId(), p, false);
    }

    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        txRepo.rolledBackTransactions(p.getUniqueId()).whenComplete((txIds, err) -> exec.entity(p, () -> {
            if (store.get(p.getUniqueId()) != null) {
                return;
            }
            if (err != null) {
                log.log(Level.WARNING, "Не удалось очистить маркеры сделок игрока " + p.getUniqueId(), err);
                return;
            }
            delivSvc.removeTemporaryMarkers(p);
            for (String txId : txIds) {
                delivSvc.removeMarker(p.getInventory(), TradeMarkers.transaction(txId));
            }
            delivSvc.deliverPending(p);
            delivSvc.deliverRecovery(p);
        }));
    }

    private CompletableFuture<Void> close(UUID playerId, Player p, boolean persistPay) {
        TradeSess sess = store.remove(playerId);
        if (sess == null) {
            return CompletableFuture.completedFuture(null);
        }
        boolean cancel = sess.purchasePending && !sess.escrowed;
        UUID txId = sess.txId;
        if (cancel && p != null) {
            purchSvc.removePendingPlayerItem(p, sess, txId);
        }
        if (cancel) {
            purchSvc.finishPending(playerId, sess);
        } else if (!sess.purchasePending) {
            store.release(sess.shop.id());
        }
        CompletableFuture<Void> rollback = CompletableFuture.completedFuture(null);
        if (cancel && txId != null) {
            rollback = txRepo.rollback(txId.toString(), playerId, false).thenAccept(applied -> {
                if (applied) {
                    if (p != null) {
                        exec.entity(p, () -> purchSvc.removePendingPlayerItem(p, sess, txId), () -> { });
                    }
                    exec.global(() -> Bukkit.getPluginManager().callEvent(
                            new ShopTransactionRolledBackEvent(txId, sess.shop.id(), playerId)));
                }
            });
        }
        EnumMap<CurType, Long> returned = collectPayment(sess);
        store.mergePendingReturn(playerId, returned);
        if (persistPay || p == null || !p.isOnline()) {
            return CompletableFuture.allOf(rollback, depositPendingReturn(playerId));
        }
        exec.entity(p, () -> {
            EnumMap<CurType, Long> pay = store.pendingReturn(playerId);
            if (pay != null) {
                if (p.isOnline()) {
                    paySvc.givePayment(p, pay);
                    store.removePendingReturn(playerId, pay);
                } else {
                    deposit(playerId, pay).thenAccept(ignored -> store.removePendingReturn(playerId, pay));
                }
            }
        }, () -> depositPendingReturn(playerId));
        return rollback;
    }

    private static EnumMap<CurType, Long> collectPayment(TradeSess sess) {
        EnumMap<CurType, Long> returned = new EnumMap<>(CurType.class);
        Inventory top = sess.getInventory();
        for (int slot : sess.paymentSlots) {
            ItemStack item = top.getItem(slot);
            if (item == null) {
                continue;
            }
            CurType cur = CurType.from(item.getType());
            if (cur != null) {
                returned.merge(cur, (long) item.getAmount(), Long::sum);
                top.setItem(slot, null);
            }
        }
        return returned;
    }

    private CompletableFuture<Void> depositPendingReturn(UUID playerId) {
        EnumMap<CurType, Long> pay = store.pendingReturn(playerId);
        return pay == null ? CompletableFuture.completedFuture(null)
                : deposit(playerId, pay).thenRun(() -> store.removePendingReturn(playerId, pay));
    }

    private CompletableFuture<Void> deposit(UUID playerId, Map<CurType, Long> pay) {
        List<CompletableFuture<Void>> deposits = pay.entrySet().stream()
                .map(e -> balances.deposit(playerId, e.getKey(), e.getValue()))
                .toList();
        return CompletableFuture.allOf(deposits.toArray(CompletableFuture[]::new));
    }

    private static void complete(CompletableFuture<Void> fut, Throwable err) {
        if (err == null) {
            fut.complete(null);
        } else {
            fut.completeExceptionally(err);
        }
    }

    private static boolean isProductPickup(InventoryClickEvent event, TradeSess sess) {
        return event.getClick() == ClickType.LEFT
                && event.getAction() == InventoryAction.PICKUP_ALL
                && TradeItem.isEmpty(event.getCursor())
                && sess.snapshot.containsKey(event.getRawSlot());
    }

    private Shop findShop(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof Chest chest) {
            return shops.find(ShopKey.of(chest.getLocation()));
        }
        if (holder instanceof DoubleChest dbl) {
            Shop left = findHolder(dbl.getLeftSide());
            return left != null ? left : findHolder(dbl.getRightSide());
        }
        return null;
    }

    private Shop findHolder(InventoryHolder holder) {
        return holder instanceof Chest chest ? shops.find(ShopKey.of(chest.getLocation())) : null;
    }
}
