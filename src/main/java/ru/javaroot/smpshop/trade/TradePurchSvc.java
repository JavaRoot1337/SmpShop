package ru.javaroot.smpshop.trade;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopSnapshot;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.api.event.ShopPurchaseCompletedEvent;
import ru.javaroot.api.event.ShopPurchasePrepareEvent;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.TxRepo;
import ru.javaroot.smpshop.hook.HookHub;
import ru.javaroot.smpshop.notify.NotifySvc;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.plat.SoundSvc;
import ru.javaroot.smpshop.shop.SaleLog;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopMgr;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

final class TradePurchSvc {
    private final Logger log;
    private final ShopMgr shops;
    private final TxRepo txRepo;
    private final HookHub hooks;
    private final NotifySvc notify;
    private final SoundSvc sounds;
    private final CfgLoad cfgLoad;
    private final Exec exec;
    private final TradeStore store;
    private final TradePaySvc paySvc;
    private final TradeDelivSvc delivSvc;
    private final Gson gson = new Gson();

    TradePurchSvc(Logger log, ShopMgr shops, TxRepo txRepo, HookHub hooks, NotifySvc notify,
                  SoundSvc sounds, CfgLoad cfgLoad, Exec exec, TradeStore store,
                  TradePaySvc paySvc, TradeDelivSvc delivSvc) {
        this.log = log;
        this.shops = shops;
        this.txRepo = txRepo;
        this.hooks = hooks;
        this.notify = notify;
        this.sounds = sounds;
        this.cfgLoad = cfgLoad;
        this.exec = exec;
        this.store = store;
        this.paySvc = paySvc;
        this.delivSvc = delivSvc;
    }

    void startPendingProduct(InventoryClickEvent event, Player p, TradeSess sess) {
        int slot = event.getRawSlot();
        Shop shop = shops.find(sess.shop.id());
        ItemStack product = sess.getInventory().getItem(slot);
        ItemStack expected = sess.snapshot.get(slot);
        if (shop == null || product == null || expected == null || !product.isSimilar(expected)
                || product.getAmount() != expected.getAmount() || product.getAmount() < 1
                || product.getAmount() > product.getMaxStackSize()) {
            return;
        }
        if (paySvc.paymentTotal(sess) < shop.price()) {
            cfgLoad.msg().send(p, "purchase-insufficient");
            return;
        }
        preparePurchase(p, sess, shop, slot, product.clone());
    }

    void handlePendingClick(InventoryClickEvent event, Player p, TradeSess sess) {
        UUID txId = sess.txId;
        if (isPendingReturn(event, sess)) {
            returnPendingProduct(p, sess);
            return;
        }
        if (!sess.prepared || !isPendingPlacement(event, sess)) {
            return;
        }
        sess.destinationSlot = event.getRawSlot() - sess.getInventory().getSize();
        event.setCancelled(false);
        exec.entity(p, () -> completeManualPlacement(p, sess),
                () -> cancelPreparedRetired(p.getUniqueId(), sess, txId));
    }

    void handlePendingDrag(InventoryDragEvent event, Player p, TradeSess sess) {
        UUID txId = sess.txId;
        if (isPendingReturn(event, sess)) {
            event.setCancelled(false);
            exec.entity(p, () -> {
                if (store.get(p.getUniqueId()) == sess && sess.purchasePending
                        && TradeItem.isEmpty(p.getItemOnCursor())
                        && delivSvc.isPendingSource(sess.getInventory().getItem(sess.pendingSlot), sess.pendingProduct)) {
                    returnPendingProduct(p, sess);
                }
            }, () -> cancelPreparedRetired(p.getUniqueId(), sess, txId));
            return;
        }
        if (!sess.prepared || !isPendingPlacement(event, sess)) {
            return;
        }
        sess.destinationSlot = event.getRawSlots().iterator().next() - sess.getInventory().getSize();
        event.setCancelled(false);
        exec.entity(p, () -> completeManualPlacement(p, sess),
                () -> cancelPreparedRetired(p.getUniqueId(), sess, txId));
    }

    void removePendingPlayerItem(Player p, TradeSess sess, UUID txId) {
        String txMarker = TradeMarkers.transaction(txId.toString());
        if (txMarker.equals(delivSvc.marker(p.getItemOnCursor()))
                || delivSvc.isPendingSource(p.getItemOnCursor(), sess.pendingProduct)) {
            p.getOpenInventory().setCursor(null);
        }
        if (txId != null) {
            delivSvc.removeMarker(p.getInventory(), txMarker);
        }
        if (sess.destinationSlot < 0) {
            return;
        }
        ItemStack item = p.getInventory().getItem(sess.destinationSlot);
        if (delivSvc.isPendingSource(item, sess.pendingProduct)) {
            p.getInventory().setItem(sess.destinationSlot, null);
        }
    }

    void finishPending(UUID playerId, TradeSess sess) {
        sess.purchasePending = false;
        sess.pendingProduct = null;
        sess.pendingSlot = -1;
        sess.prepared = false;
        sess.productPlaced = false;
        sess.destinationSlot = -1;
        sess.payment = null;
        sess.adjustments = List.of();
        sess.txId = null;
        sess.escrowed = false;
        if (store.get(playerId) != sess) {
            store.release(sess.shop.id());
        }
    }

    private void preparePurchase(Player p, TradeSess sess, Shop shop, int slot, ItemStack product) {
        UUID txId = UUID.randomUUID();
        var base = paySvc.paymentPlan(sess, shop.price());
        ShopPurchasePrepareEvent prepEvent = new ShopPurchasePrepareEvent(
                txId, p.getUniqueId(), toSnapshot(shop), paySvc.amounts(base));
        Bukkit.getPluginManager().callEvent(prepEvent);
        if (prepEvent.isCancelled()) {
            return;
        }
        TradePaySvc.Payment adjusted = paySvc.adjustedPayment(sess, base, prepEvent.adjustments());
        if (adjusted == null) {
            cfgLoad.msg().send(p, "purchase-insufficient");
            return;
        }
        sess.purchasePending = true;
        sess.txId = txId;
        sess.pendingSlot = slot;
        sess.pendingProduct = product.clone();
        sess.payment = adjusted;
        sess.adjustments = List.copyOf(prepEvent.adjustments());
        sess.prepared = false;
        sess.productPlaced = false;
        sess.destinationSlot = -1;
        sess.escrowed = false;
        sess.getInventory().setItem(slot, null);
        p.getOpenInventory().setCursor(delivSvc.deliveryItem(product, TradeMarkers.pending(txId)));

        txRepo.prepareSale(txId.toString(), shop, p.getUniqueId(), slot, product.getAmount(),
                        shop.price(), adjusted.buyer(), adjusted.seller(),
                        product.serializeAsBytes(), gson.toJson(prepEvent.adjustments()))
                .thenRun(() -> exec.entity(p, () -> {
                    if (store.get(p.getUniqueId()) == sess && sess.purchasePending && txId.equals(sess.txId)) {
                        sess.prepared = true;
                        return;
                    }
                    cancelPrepared(p, sess, txId);
                }, () -> cancelPreparedRetired(p.getUniqueId(), sess, txId)))
                .exceptionally(err -> {
                    exec.entity(p, () -> failPreparation(p, sess), () -> finishPending(p.getUniqueId(), sess));
                    log.log(Level.SEVERE, "Не удалось подготовить продажу в магазине #" + shop.id(), err);
                    return null;
                });
    }

    private void completeManualPlacement(Player p, TradeSess sess) {
        if (store.get(p.getUniqueId()) != sess || !sess.purchasePending || !sess.prepared
                || sess.productPlaced || sess.pendingProduct == null || sess.destinationSlot < 0
                || !TradeItem.isEmpty(p.getItemOnCursor())) {
            return;
        }
        ItemStack placed = p.getInventory().getItem(sess.destinationSlot);
        if (!delivSvc.isPendingSource(placed, sess.pendingProduct)) {
            cancelPrepared(p, sess, sess.txId);
            return;
        }
        p.getInventory().setItem(sess.destinationSlot,
                delivSvc.deliveryItem(sess.pendingProduct, TradeMarkers.transaction(sess.txId.toString())));
        sess.productPlaced = true;
        escrowAndComplete(p, sess, sess.shop, sess.pendingSlot, sess.pendingProduct,
                sess.txId, sess.payment, sess.adjustments);
    }

    private void failPreparation(Player p, TradeSess sess) {
        if (store.get(p.getUniqueId()) == sess && sess.purchasePending) {
            cancelPrepared(p, sess, sess.txId);
        }
    }

    private void escrowAndComplete(Player p, TradeSess sess, Shop shop, int slot, ItemStack product,
                                   UUID txId, TradePaySvc.Payment pay, List<PurchaseAdjustment> adj) {
        if (store.get(p.getUniqueId()) != sess || !sess.purchasePending) {
            cancelPrepared(p, sess, txId);
            return;
        }
        if (!sess.productPlaced
                || !delivSvc.hasMarker(p.getInventory(), TradeMarkers.transaction(txId.toString()))
                || !paySvc.hasPayment(sess, pay.buyer())) {
            cancelPrepared(p, sess, txId);
            return;
        }
        paySvc.takePayment(sess, pay.buyer());
        sess.escrowed = true;
        if (exec.region(sess.loc, () -> takePhysicalAndComplete(p, sess, shop, slot, product, txId, pay, adj))
                != Exec.Result.SCHEDULED) {
            restoreFailed(p, sess, shop, product, txId, slot, false,
                    new IllegalStateException("Регион магазина недоступен"));
        }
    }

    private void takePhysicalAndComplete(Player p, TradeSess sess, Shop shop, int slot, ItemStack product,
                                         UUID txId, TradePaySvc.Payment pay, List<PurchaseAdjustment> adj) {
        ItemStack physical = sess.physical.getItem(slot);
        if (physical == null || !physical.isSimilar(product) || physical.getAmount() != product.getAmount()) {
            restoreFailed(p, sess, shop, product, txId, slot, true,
                    new IllegalStateException("Товар в сундуке изменился до завершения покупки"));
            return;
        }
        sess.physical.setItem(slot, null);
        completePurchase(p, sess, shop, product, txId, pay, adj, slot);
    }

    private void completePurchase(Player p, TradeSess sess, Shop shop, ItemStack product, UUID txId,
                                  TradePaySvc.Payment pay, List<PurchaseAdjustment> adj, int itemSlot) {
        int items = product.getAmount();
        long amount = shop.price();
        SaleLog sale = new SaleLog(shop.ownerId(), shop.id(), "#" + shop.id(), product.getType(), items, amount);
        txRepo.completeSale(txId.toString(), shop, items, pay.seller())
                .thenAccept(completed -> {
                    if (!completed) {
                        restoreFailed(p, sess, shop, product, txId, itemSlot, true,
                                new IllegalStateException("Сделка больше не находится в состоянии PREPARED"));
                        return;
                    }
                    CompletableFuture<Void> pub = new CompletableFuture<>();
                    if (exec.global(() -> {
                        try {
                            shops.recordSale(shop.id(), amount, items);
                            hooks.sale(sale);
                            notify.sale(sale, txId);
                            Bukkit.getPluginManager().callEvent(new ShopPurchaseCompletedEvent(
                                    txId, p.getUniqueId(), toSnapshot(shop), amount,
                                    paySvc.amounts(pay.buyer()), paySvc.amounts(pay.seller()), adj));
                            pub.complete(null);
                        } catch (RuntimeException ex) {
                            pub.completeExceptionally(ex);
                        }
                    }) != Exec.Result.SCHEDULED) {
                        pub.complete(null);
                        log.warning("Пропущена публикация события покупки #" + shop.id() + ": сервер останавливается");
                    }
                    pub.handle((ignored, err) -> {
                        if (err != null) {
                            log.log(Level.WARNING, "Ошибка публикации покупки #" + shop.id(), err);
                        }
                        exec.entity(p, () -> {
                            finishPending(p.getUniqueId(), sess);
                            if (delivSvc.hasMarker(p.getInventory(), TradeMarkers.transaction(txId.toString()))) {
                                delivSvc.confirmDelivery(p, txId.toString(), () -> {
                                    sounds.buyerPurchase(p);
                                    Component itemComp = Component.translatable(product.getType().translationKey());
                                    cfgLoad.msg().send(
                                            p, "purchase-success",
                                            Map.of("items", items, "amount", amount),
                                            Map.of("item", itemComp)
                                    );
                                });
                            }
                        }, () -> finishPending(p.getUniqueId(), sess));
                        return null;
                    });
                })
                .exceptionally(err -> {
                    restoreFailed(p, sess, shop, product, txId, itemSlot, true, err);
                    return null;
                });
    }

    private void restoreFailed(Player p, TradeSess sess, Shop shop, ItemStack product, UUID txId,
                               int itemSlot, boolean productRemoved, Throwable err) {
        log.log(Level.SEVERE, "Сбой завершения покупки в магазине #" + shop.id(), err);
        exec.entity(p, () -> {
            if (store.get(p.getUniqueId()) == sess && sess.getInventory().getItem(itemSlot) == null) {
                sess.getInventory().setItem(itemSlot, product.clone());
            }
            finishPending(p.getUniqueId(), sess);
        }, () -> finishPending(p.getUniqueId(), sess));
        if (!productRemoved) {
            rollbackFailed(p, shop, txId, false);
            return;
        }
        Runnable restore = () -> {
            ItemStack cur = sess.physical.getItem(itemSlot);
            boolean recoverItem = cur != null && (!cur.isSimilar(product) || cur.getAmount() != product.getAmount());
            if (cur == null) {
                sess.physical.setItem(itemSlot, product.clone());
            }
            rollbackFailed(p, shop, txId, recoverItem);
        };
        if (exec.ownsRegion(sess.loc)) {
            restore.run();
        } else if (exec.region(sess.loc, restore) != Exec.Result.SCHEDULED) {
            rollbackFailed(p, shop, txId, true);
        }
    }

    private void rollbackFailed(Player p, Shop shop, UUID txId, boolean recoverItem) {
        txRepo.rollback(txId.toString(), p.getUniqueId(), recoverItem).thenAccept(applied -> {
            if (applied) {
                exec.entity(p, () -> {
                    delivSvc.removeMarker(p.getInventory(), TradeMarkers.transaction(txId.toString()));
                    delivSvc.removeMarker(p.getInventory(), TradeMarkers.pending(txId));
                }, () -> { });
                exec.global(() -> Bukkit.getPluginManager().callEvent(
                        new ShopTransactionRolledBackEvent(txId, shop.id(), p.getUniqueId())));
            }
        });
    }

    private void cancelPrepared(Player p, TradeSess sess, UUID txId) {
        boolean active = txId != null && store.get(p.getUniqueId()) == sess
                && sess.purchasePending && txId.equals(sess.txId);
        if (active) {
            if (sess.pendingProduct != null && TradeItem.isEmpty(sess.getInventory().getItem(sess.pendingSlot))) {
                sess.getInventory().setItem(sess.pendingSlot, sess.pendingProduct.clone());
            }
            finishPending(p.getUniqueId(), sess);
        }
        if (txId == null) {
            return;
        }
        txRepo.rollback(txId.toString(), p.getUniqueId(), false).thenAccept(applied -> {
            if (applied) {
                exec.entity(p, () -> {
                    removePendingPlayerItem(p, sess, txId);
                    delivSvc.removeMarker(p.getInventory(), TradeMarkers.pending(txId));
                }, () -> { });
                exec.global(() -> Bukkit.getPluginManager().callEvent(
                        new ShopTransactionRolledBackEvent(txId, sess.shop.id(), p.getUniqueId())));
            }
        });
    }

    private void cancelPreparedRetired(UUID playerId, TradeSess sess, UUID txId) {
        if (store.get(playerId) == sess && sess.purchasePending && txId != null && txId.equals(sess.txId)) {
            finishPending(playerId, sess);
        }
        if (txId == null) {
            return;
        }
        txRepo.rollback(txId.toString(), playerId, false).thenAccept(applied -> {
            if (applied) {
                exec.global(() -> Bukkit.getPluginManager().callEvent(
                        new ShopTransactionRolledBackEvent(txId, sess.shop.id(), playerId)));
            }
        });
    }

    private void returnPendingProduct(Player p, TradeSess sess) {
        if (!sess.purchasePending || sess.pendingProduct == null) {
            return;
        }
        sess.getInventory().setItem(sess.pendingSlot, sess.pendingProduct.clone());
        p.getOpenInventory().setCursor(null);
        cancelPrepared(p, sess, sess.txId);
    }

    private boolean isPendingReturn(InventoryClickEvent event, TradeSess sess) {
        return event.getClickedInventory() == sess.getInventory()
                && event.getRawSlot() == sess.pendingSlot
                && event.getClick() == ClickType.LEFT
                && event.getAction() == InventoryAction.PLACE_ALL
                && TradeItem.isEmpty(event.getCurrentItem())
                && delivSvc.isPendingSource(event.getCursor(), sess.pendingProduct);
    }

    private boolean isPendingReturn(InventoryDragEvent event, TradeSess sess) {
        return event.getType() == DragType.EVEN
                && event.getRawSlots().size() == 1
                && event.getRawSlots().contains(sess.pendingSlot)
                && delivSvc.isPendingSource(event.getOldCursor(), sess.pendingProduct)
                && TradeItem.isEmpty(sess.getInventory().getItem(sess.pendingSlot));
    }

    private boolean isPendingPlacement(InventoryClickEvent event, TradeSess sess) {
        return event.getClickedInventory() == event.getView().getBottomInventory()
                && event.getClick() == ClickType.LEFT
                && event.getAction() == InventoryAction.PLACE_ALL
                && TradeItem.isEmpty(event.getCurrentItem())
                && delivSvc.isPendingSource(event.getCursor(), sess.pendingProduct);
    }

    private boolean isPendingPlacement(InventoryDragEvent event, TradeSess sess) {
        if (event.getType() != DragType.EVEN || event.getRawSlots().size() != 1) {
            return false;
        }
        int raw = event.getRawSlots().iterator().next();
        int topSize = sess.getInventory().getSize();
        return raw >= topSize
                && delivSvc.isPendingSource(event.getOldCursor(), sess.pendingProduct)
                && TradeItem.isEmpty(event.getView().getBottomInventory().getItem(raw - topSize));
    }

    private static ShopSnapshot toSnapshot(Shop shop) {
        ShopPosition first = new ShopPosition(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
        ShopPosition second = shop.x2() == null ? null : new ShopPosition(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
        return new ShopSnapshot(shop.id(), shop.ownerId(), shop.ownerName(), first, second,
                shop.price(), shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt());
    }
}
