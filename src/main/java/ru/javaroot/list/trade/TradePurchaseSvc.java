package ru.javaroot.list.trade;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopSnapshot;
import ru.javaroot.api.event.ShopPurchaseCompletedEvent;
import ru.javaroot.api.event.ShopPurchasePrepareEvent;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.ShopStore;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.integration.SaleLog;
import ru.javaroot.item.ItemName;
import ru.javaroot.notification.NotifySvc;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopMgr;
import ru.javaroot.config.Msg;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

class TradePurchaseSvc {
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final IntegrationHub integrations;
    private final NotifySvc notifications;
    private final SoundSvc sounds;
    private final Msg msg;
    private final ItemName itemNames;
    private final Sched sched;
    private final Logger logger;
    private final TradeSessionStore sessions;
    private final TradePaymentSvc payment;
    private final TradeDeliverySvc delivery;
    private final Gson gson = new Gson();

    TradePurchaseSvc(ShopMgr shops, ShopStore shopStore, IntegrationHub integrations,
                                NotifySvc notifications, SoundSvc sounds, Msg msg, ItemName itemNames,
                                Sched sched, Logger logger, TradeSessionStore sessions,
                                TradePaymentSvc payment, TradeDeliverySvc delivery) {
        this.shops = shops;
        this.shopStore = shopStore;
        this.integrations = integrations;
        this.notifications = notifications;
        this.sounds = sounds;
        this.msg = msg;
        this.itemNames = itemNames;
        this.sched = sched;
        this.logger = logger;
        this.sessions = sessions;
        this.payment = payment;
        this.delivery = delivery;
    }

    void startPendingProduct(InventoryClickEvent event, Player player, TradeSession session) {
        int slot = event.getRawSlot();
        Shop shop = shops.find(session.shop.id());
        ItemStack product = session.inventory.getItem(slot);
        ItemStack expected = session.snapshot.get(slot);
        if (shop == null || product == null || expected == null || !product.isSimilar(expected)
                || product.getAmount() != expected.getAmount() || product.getAmount() < 1
                || product.getAmount() > product.getMaxStackSize()) {
            return;
        }
        long paid = payment.paymentTotal(session);
        if (paid < shop.price()) {
            msg.send(player, "purchase-insufficient");
            return;
        }
        preparePurchase(player, session, shop, slot, product.clone());
    }

    void handlePendingClick(InventoryClickEvent event, Player player, TradeSession session) {
        UUID transactionId = session.transactionId;
        if (isPendingReturn(event, session)) {
            returnPendingProduct(player, session);
            return;
        }
        if (!session.prepared || !isPendingPlacement(event, session)) {
            return;
        }
        session.destinationSlot = event.getRawSlot() - session.inventory.getSize();
        event.setCancelled(false);
        sched.entity(player, () -> completeManualPlacement(player, session),
                () -> cancelPreparedRetired(player.getUniqueId(), session, transactionId, Map.of()));
    }

    void handlePendingDrag(InventoryDragEvent event, Player player, TradeSession session) {
        UUID transactionId = session.transactionId;
        if (isPendingReturn(event, session)) {
            event.setCancelled(false);
            sched.entity(player, () -> {
                if (sessions.get(player.getUniqueId()) == session && session.purchasePending
                        && TradeItem.isEmpty(player.getItemOnCursor())
                        && delivery.isPendingSource(session.inventory.getItem(session.pendingSlot),
                                session.pendingProduct)) {
                    returnPendingProduct(player, session);
                }
            }, () -> cancelPreparedRetired(player.getUniqueId(), session, transactionId, Map.of()));
            return;
        }
        if (!session.prepared || !isPendingPlacement(event, session)) {
            return;
        }
        session.destinationSlot = event.getRawSlots().iterator().next() - session.inventory.getSize();
        event.setCancelled(false);
        sched.entity(player, () -> completeManualPlacement(player, session),
                () -> cancelPreparedRetired(player.getUniqueId(), session, transactionId, Map.of()));
    }

    void preparePurchase(Player player, TradeSession session, Shop shop, int slot, ItemStack product) {
        UUID transactionId = UUID.randomUUID();
        EnumMap<Currency, Long> basePayment = payment.paymentPlan(session, shop.price());
        ShopPurchasePrepareEvent event = new ShopPurchasePrepareEvent(transactionId, player.getUniqueId(),
                snapshot(shop), payment.amounts(basePayment));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        TradePaymentSvc.Payment adjusted = payment.adjustedPayment(session, basePayment, event.adjustments());
        if (adjusted == null) {
            msg.send(player, "purchase-insufficient");
            return;
        }
        session.purchasePending = true;
        session.transactionId = transactionId;
        session.pendingSlot = slot;
        session.pendingProduct = product.clone();
        session.payment = adjusted;
        session.adjustments = List.copyOf(event.adjustments());
        session.prepared = false;
        session.productPlaced = false;
        session.destinationSlot = -1;
        session.escrowed = false;
        session.inventory.setItem(slot, null);
        player.getOpenInventory().setCursor(delivery.deliveryItem(product, "pending:" + transactionId));
        shopStore.prepareSale(transactionId.toString(), shop, player.getUniqueId(), slot,
                        product.getAmount(), shop.price(), adjusted.buyer(), adjusted.seller(),
                        product.serializeAsBytes(), gson.toJson(event.adjustments()))
                .thenRun(() -> sched.entity(player, () -> {
                    if (sessions.get(player.getUniqueId()) == session
                            && session.purchasePending && transactionId.equals(session.transactionId)) {
                        session.prepared = true;
                        return;
                    }
                    cancelPrepared(player, session, transactionId, Map.of());
                }, () -> cancelPreparedRetired(player.getUniqueId(), session, transactionId, Map.of())))
                .exceptionally(error -> {
                    sched.entity(player, () -> failPreparation(player, session),
                            () -> finishPending(player.getUniqueId(), session));
                    logger.log(Level.SEVERE, "Не удалось подготовить продажу #" + shop.id(), error);
                    return null;
                });
    }

    void completeManualPlacement(Player player, TradeSession session) {
        if (sessions.get(player.getUniqueId()) != session || !session.purchasePending
                || !session.prepared || session.productPlaced || session.pendingProduct == null
                || session.destinationSlot < 0 || !TradeItem.isEmpty(player.getItemOnCursor())) {
            return;
        }
        ItemStack placed = player.getInventory().getItem(session.destinationSlot);
        if (!delivery.isPendingSource(placed, session.pendingProduct)) {
            cancelPrepared(player, session, session.transactionId, Map.of());
            return;
        }
        player.getInventory().setItem(session.destinationSlot,
                delivery.deliveryItem(session.pendingProduct, "transaction:" + session.transactionId));
        session.productPlaced = true;
        escrowAndComplete(player, session, session.shop, session.pendingSlot, session.pendingProduct,
                session.transactionId, session.payment, session.adjustments);
    }

    void failPreparation(Player player, TradeSession session) {
        if (sessions.get(player.getUniqueId()) != session || !session.purchasePending) {
            return;
        }
        cancelPrepared(player, session, session.transactionId, Map.of());
    }

    void escrowAndComplete(Player player, TradeSession session, Shop shop, int slot,
                                  ItemStack product, UUID transactionId, TradePaymentSvc.Payment pay,
                                  List<PurchaseAdjustment> adjustments) {
        if (sessions.get(player.getUniqueId()) != session || !session.purchasePending) {
            cancelPrepared(player, session, transactionId, Map.of());
            return;
        }
        if (!session.productPlaced || !delivery.hasMarker(player.getInventory(), "transaction:" + transactionId)
                || !payment.hasPayment(session, pay.buyer())) {
            cancelPrepared(player, session, transactionId, Map.of());
            return;
        }
        payment.takePayment(session, pay.buyer());
        session.escrowed = true;
        if (sched.region(session.location, () -> takePhysicalAndComplete(player, session, shop, slot,
                product, transactionId, pay, adjustments)) != Sched.ScheduleResult.SCHEDULED) {
            restoreFailedPurchase(player, session, shop, product, transactionId, pay.buyer(), slot, false,
                    new IllegalStateException("Регион магазина недоступен"));
        }
    }

    private void takePhysicalAndComplete(Player player, TradeSession session, Shop shop, int slot,
                                         ItemStack product, UUID transactionId, TradePaymentSvc.Payment pay,
                                         List<PurchaseAdjustment> adjustments) {
        ItemStack physical = session.physical.getItem(slot);
        if (physical == null || !physical.isSimilar(product) || physical.getAmount() != product.getAmount()) {
            restoreFailedPurchase(player, session, shop, product, transactionId, pay.buyer(), slot, true,
                    new IllegalStateException("Товар в магазине изменился до подтверждения операции"));
            return;
        }
        session.physical.setItem(slot, null);
        completePurchase(player, session, shop, product, transactionId, pay, adjustments, slot);
    }

    private void completePurchase(Player player, TradeSession session, Shop shop, ItemStack product,
                                  UUID transactionId, TradePaymentSvc.Payment pay,
                                  List<PurchaseAdjustment> adjustments, int itemSlot) {
        int items = product.getAmount();
        long amount = shop.price();
        SaleLog sale = new SaleLog(shop.ownerId(), shop.id(), "#" + shop.id(), product.getType(), items, amount);
        shopStore.completeSale(transactionId.toString(), shop, items, pay.seller())
                .thenAccept(completed -> {
                    if (!completed) {
                        restoreFailedPurchase(player, session, shop, product, transactionId, pay.buyer(), itemSlot,
                                true, new IllegalStateException("Транзакция больше не подготовлена"));
                        return;
                    }
                    CompletableFuture<Void> publication = new CompletableFuture<>();
                    if (sched.global(() -> {
                        try {
                            shops.recordSale(shop.id(), amount, items);
                            integrations.sale(sale);
                            notifications.sale(sale, transactionId);
                            Bukkit.getPluginManager().callEvent(new ShopPurchaseCompletedEvent(transactionId,
                                    player.getUniqueId(), snapshot(shop), amount, payment.amounts(pay.buyer()),
                                    payment.amounts(pay.seller()), adjustments));
                            publication.complete(null);
                        } catch (RuntimeException exception) {
                            publication.completeExceptionally(exception);
                        }
                    }) != Sched.ScheduleResult.SCHEDULED) {
                        publication.complete(null);
                        logger.warning("Не удалось опубликовать завершение покупки #" + shop.id()
                                + ": сервер завершает работу");
                    }
                    publication.handle((ignored, error) -> {
                        if (error != null) {
                            logger.log(Level.WARNING, "Не удалось опубликовать завершение покупки #" + shop.id(), error);
                        }
                        sched.entity(player, () -> {
                            finishPending(player.getUniqueId(), session);
                            if (delivery.hasMarker(player.getInventory(), "transaction:" + transactionId)) {
                                delivery.confirmDelivery(player, transactionId.toString(), () -> {
                                    sounds.buyerPurchase(player);
                                    msg.send(player, "purchase-success", Map.of("items", items, "amount", amount),
                                            Map.of("item", itemNames.component(product.getType())));
                                });
                            }
                        }, () -> finishPending(player.getUniqueId(), session));
                        return null;
                    });
                })
                .exceptionally(error -> {
                    restoreFailedPurchase(player, session, shop, product, transactionId,
                            pay.buyer(), itemSlot, true, error);
                    return null;
                });
    }

    void restoreFailedPurchase(Player player, TradeSession session, Shop shop, ItemStack product,
                                      UUID transactionId, Map<Currency, Long> pay, int itemSlot,
                                      boolean productRemoved, Throwable error) {
        logger.log(Level.SEVERE, "Не удалось завершить продажу #" + shop.id(), error);
        sched.entity(player, () -> {
            delivery.removeMarker(player.getInventory(), "transaction:" + transactionId);
            if (sessions.get(player.getUniqueId()) == session && session.inventory.getItem(itemSlot) == null) {
                session.inventory.setItem(itemSlot, product.clone());
            }
            finishPending(player.getUniqueId(), session);
        }, () -> finishPending(player.getUniqueId(), session));
        if (!productRemoved) {
            rollbackFailedPurchase(player, shop, product, transactionId, pay, false);
            return;
        }
        Runnable restore = () -> {
            ItemStack current = session.physical.getItem(itemSlot);
            boolean recoverItem = current != null && (!current.isSimilar(product)
                    || current.getAmount() != product.getAmount());
            if (current == null) {
                session.physical.setItem(itemSlot, product.clone());
            }
            rollbackFailedPurchase(player, shop, product, transactionId, pay, recoverItem);
        };
        if (Bukkit.isOwnedByCurrentRegion(session.location)) {
            restore.run();
        } else if (sched.region(session.location, restore) != Sched.ScheduleResult.SCHEDULED) {
            rollbackFailedPurchase(player, shop, product, transactionId, pay, true);
        }
    }

    void rollbackFailedPurchase(Player player, Shop shop, ItemStack product, UUID transactionId,
                                        Map<Currency, Long> pay, boolean recoverItem) {
        shopStore.rollback(transactionId.toString(), player.getUniqueId(), pay, shop.ownerId(),
                        product.serializeAsBytes(), recoverItem)
                .thenAccept(applied -> {
                    if (applied) {
                        sched.global(() -> Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                                transactionId, shop.id(), player.getUniqueId())));
                    }
                });
    }

    void cancelPrepared(Player player, TradeSession session, UUID transactionId,
                               Map<Currency, Long> refund) {
        boolean active = transactionId != null && sessions.get(player.getUniqueId()) == session
                && session.purchasePending && transactionId.equals(session.transactionId);
        if (active) {
            removePendingPlayerItem(player, session, transactionId);
            if (session.pendingProduct != null && TradeItem.isEmpty(session.inventory.getItem(session.pendingSlot))) {
                session.inventory.setItem(session.pendingSlot, session.pendingProduct.clone());
            }
            finishPending(player.getUniqueId(), session);
        }
        if (transactionId == null) {
            return;
        }
        shopStore.rollback(transactionId.toString(), player.getUniqueId(), refund)
                .thenAccept(applied -> {
                    if (applied) {
                        sched.global(() -> Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                                transactionId, session.shop.id(), player.getUniqueId())));
                    }
                });
    }

    private void cancelPreparedRetired(UUID playerId, TradeSession session, UUID transactionId,
                                       Map<Currency, Long> refund) {
        if (sessions.get(playerId) == session && session.purchasePending
                && transactionId != null && transactionId.equals(session.transactionId)) {
            finishPending(playerId, session);
        }
        if (transactionId == null) {
            return;
        }
        shopStore.rollback(transactionId.toString(), playerId, refund)
                .thenAccept(applied -> {
                    if (applied) {
                        sched.global(() -> Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                                transactionId, session.shop.id(), playerId)));
                    }
                });
    }

    void returnPendingProduct(Player player, TradeSession session) {
        if (!session.purchasePending || session.pendingProduct == null) {
            return;
        }
        session.inventory.setItem(session.pendingSlot, session.pendingProduct.clone());
        player.getOpenInventory().setCursor(null);
        cancelPrepared(player, session, session.transactionId, Map.of());
    }

    void removePendingPlayerItem(Player player, TradeSession session, UUID transactionId) {
        String txMarker = "transaction:" + transactionId;
        if (txMarker.equals(delivery.marker(player.getItemOnCursor()))
                || delivery.isPendingSource(player.getItemOnCursor(), session.pendingProduct)) {
            player.getOpenInventory().setCursor(null);
        }
        if (transactionId != null) {
            delivery.removeMarker(player.getInventory(), txMarker);
        }
        if (session.destinationSlot < 0) {
            return;
        }
        ItemStack item = player.getInventory().getItem(session.destinationSlot);
        if (delivery.isPendingSource(item, session.pendingProduct)) {
            player.getInventory().setItem(session.destinationSlot, null);
        }
    }

    void finishPending(UUID playerId, TradeSession session) {
        session.purchasePending = false;
        session.pendingProduct = null;
        session.pendingSlot = -1;
        session.prepared = false;
        session.productPlaced = false;
        session.destinationSlot = -1;
        session.payment = null;
        session.adjustments = List.of();
        session.transactionId = null;
        session.escrowed = false;
        if (sessions.get(playerId) != session) {
            sessions.release(session.shop.id());
        }
    }

    private boolean isPendingReturn(InventoryClickEvent event, TradeSession session) {
        return event.getClickedInventory() == session.inventory && event.getRawSlot() == session.pendingSlot
                && event.getClick() == org.bukkit.event.inventory.ClickType.LEFT
                && event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ALL
                && TradeItem.isEmpty(event.getCurrentItem())
                && delivery.isPendingSource(event.getCursor(), session.pendingProduct);
    }

    private boolean isPendingReturn(InventoryDragEvent event, TradeSession session) {
        return event.getType() == org.bukkit.event.inventory.DragType.EVEN
                && event.getRawSlots().size() == 1 && event.getRawSlots().contains(session.pendingSlot)
                && delivery.isPendingSource(event.getOldCursor(), session.pendingProduct)
                && TradeItem.isEmpty(session.inventory.getItem(session.pendingSlot));
    }

    private boolean isPendingPlacement(InventoryClickEvent event, TradeSession session) {
        return event.getClickedInventory() == event.getView().getBottomInventory()
                && event.getClick() == org.bukkit.event.inventory.ClickType.LEFT
                && event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ALL
                && TradeItem.isEmpty(event.getCurrentItem())
                && delivery.isPendingSource(event.getCursor(), session.pendingProduct);
    }

    private boolean isPendingPlacement(InventoryDragEvent event, TradeSession session) {
        if (event.getType() != org.bukkit.event.inventory.DragType.EVEN || event.getRawSlots().size() != 1) {
            return false;
        }
        int rawSlot = event.getRawSlots().iterator().next();
        int topSize = session.inventory.getSize();
        return rawSlot >= topSize && delivery.isPendingSource(event.getOldCursor(), session.pendingProduct)
                && TradeItem.isEmpty(event.getView().getBottomInventory().getItem(rawSlot - topSize));
    }

    private ShopSnapshot snapshot(Shop shop) {
        ShopPosition first = new ShopPosition(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
        ShopPosition second = shop.x2() == null ? null
                : new ShopPosition(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
        return new ShopSnapshot(shop.id(), shop.ownerId(), shop.ownerName(), first, second, shop.price(),
                shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt());
    }
}
