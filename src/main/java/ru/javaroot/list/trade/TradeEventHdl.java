package ru.javaroot.list.trade;

import org.bukkit.Bukkit;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.config.Msg;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.data.ShopStore;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.item.ItemName;
import ru.javaroot.notification.NotifySvc;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;
import ru.javaroot.sound.SoundSvc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TradeEventHdl {
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final BalanceStore balances;
    private final Msg msg;
    private final Sched sched;
    private final Logger logger;
    private final TradeSessionStore sessions;
    private final TradePaymentSvc payment;
    private final TradePurchaseSvc purchase;
    private final TradeDeliverySvc delivery;

    public TradeEventHdl(JavaPlugin plugin, ShopMgr shops, ShopStore shopStore, BalanceStore balances,
                         IntegrationHub integrations, NotifySvc notifications, SoundSvc sounds, Msg msg,
                         ItemName itemNames, Sched sched, Logger logger) {
        TradeSessionStore sessions = new TradeSessionStore();
        TradePaymentSvc payment = new TradePaymentSvc();
        TradeDeliverySvc delivery = new TradeDeliverySvc(plugin, shops, shopStore, sched, sessions);
        TradePurchaseSvc purchase = new TradePurchaseSvc(shops, shopStore, integrations, notifications,
                sounds, msg, itemNames, sched, logger, sessions, payment, delivery);
        this.shops = shops;
        this.shopStore = shopStore;
        this.balances = balances;
        this.msg = msg;
        this.sched = sched;
        this.logger = logger;
        this.sessions = sessions;
        this.payment = payment;
        this.purchase = purchase;
        this.delivery = delivery;
    }

    public boolean isBusy(long shopId) {
        return sessions.isBusy(shopId);
    }

    public boolean reserveRemoval(long shopId) {
        return sessions.reserveRemoval(shopId);
    }

    public void releaseRemoval(long shopId) {
        sessions.releaseRemoval(shopId);
    }

    public void start() {
        sessions.start();
    }

    public void stop() {
        sessions.stop();
    }

    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!sessions.accepting() && (event.getInventory().getHolder() instanceof Chest
                || event.getInventory().getHolder() instanceof DoubleChest)) {
            event.setCancelled(true);
            return;
        }
        Shop shop = findShop(event.getInventory());
        if (shop == null) {
            return;
        }
        if (shop.ownerId().equals(player.getUniqueId())) {
            if (sessions.isBusy(shop.id())) {
                event.setCancelled(true);
                msg.send(player, "shop-busy");
            }
            return;
        }
        if (sessions.isBusy(shop.id()) || sessions.get(player.getUniqueId()) != null) {
            event.setCancelled(true);
            msg.send(player, "shop-busy");
            return;
        }
        if (event.getInventory().getViewers().stream()
                .anyMatch(viewer -> viewer.getUniqueId().equals(shop.ownerId()))) {
            event.setCancelled(true);
            msg.send(player, "shop-busy");
            return;
        }
        org.bukkit.World world = Bukkit.getWorld(shop.worldId());
        if (world == null) {
            event.setCancelled(true);
            return;
        }
        TradeSession session = new TradeSession(shop, event.getInventory(), event.getView().title(),
                new org.bukkit.Location(world, shop.x1(), shop.y1(), shop.z1()));
        if (sessions.reserve(shop.id())) {
            if (!sessions.putIfAbsent(player.getUniqueId(), session)) {
                sessions.release(shop.id());
                event.setCancelled(true);
                msg.send(player, "shop-busy");
                return;
            }
            event.setCancelled(true);
            sched.entity(player, () -> {
                if (sessions.get(player.getUniqueId()) == session) {
                    player.openInventory(session.inventory);
                }
            }, () -> close(player.getUniqueId(), null));
        } else {
            event.setCancelled(true);
            msg.send(player, "shop-busy");
        }
    }

    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        TradeSession session = sessions.get(player.getUniqueId());
        String deliveryMarker = delivery.marker(event.getCursor());
        boolean pendingCursor = session != null && session.purchasePending
                && deliveryMarker != null && deliveryMarker.equals("pending:" + session.transactionId);
        if ((delivery.isDeliveryItem(event.getCurrentItem()) || deliveryMarker != null) && !pendingCursor) {
            if (deliveryMarker != null && deliveryMarker.startsWith("pending:")) {
                event.setCancelled(true);
                return;
            }
            if (deliveryMarker != null && delivery.isDeliveryPlacement(event)) {
                event.setCancelled(false);
                delivery.checkPlacement(player, deliveryMarker);
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (session == null || event.getView().getTopInventory() != session.inventory) {
            if (pendingCursor) {
                event.setCancelled(true);
            }
            return;
        }
        if (session.purchasePending) {
            event.setCancelled(true);
            purchase.handlePendingClick(event, player, session);
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() < 0) {
            return;
        }
        if (event.getRawSlot() >= session.inventory.getSize()) {
            if (payment.isCurrencyPickup(event) || payment.isCurrencyPlacement(event)) {
                event.setCancelled(false);
            } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                payment.movePaymentItem(event, session);
            }
            return;
        }
        if (payment.isPaymentPickup(event, session)) {
            event.setCancelled(false);
            return;
        }
        if (payment.isPaymentShiftPickup(event, session)) {
            payment.movePaymentToPlayer(event, player);
            return;
        }
        if (payment.isPaymentClick(event, session)) {
            event.setCancelled(false);
            return;
        }
        if (isProductPickup(event, session)) {
            purchase.startPendingProduct(event, player, session);
        }
    }

    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        TradeSession session = sessions.get(player.getUniqueId());
        String deliveryMarker = delivery.marker(event.getOldCursor());
        boolean pendingCursor = session != null && session.purchasePending
                && deliveryMarker != null && deliveryMarker.equals("pending:" + session.transactionId);
        if (deliveryMarker != null && !pendingCursor) {
            event.setCancelled(true);
            if (deliveryMarker.startsWith("pending:")) {
                return;
            }
            if (delivery.isDeliveryPlacement(event)) {
                event.setCancelled(false);
                delivery.checkPlacement(player, deliveryMarker);
            }
            return;
        }
        if (pendingCursor && (session == null || event.getView().getTopInventory() != session.inventory)) {
            event.setCancelled(true);
            return;
        }
        if (session != null && event.getView().getTopInventory() == session.inventory) {
            event.setCancelled(true);
            if (session.purchasePending) {
                purchase.handlePendingDrag(event, player, session);
                return;
            }
            if (payment.isPaymentDrag(event, session)) {
                event.setCancelled(false);
            }
        }
    }

    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (delivery.isDeliveryItem(item) || delivery.isPendingItem(event.getPlayer(), item)) {
            event.setCancelled(true);
        }
    }

    public void onUse(PlayerInteractEvent event) {
        if (delivery.isDeliveryItem(event.getItem()) || delivery.isPendingItem(event.getPlayer(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    public void onMove(InventoryMoveItemEvent event) {
        if (delivery.isDeliveryItem(event.getItem()) || delivery.isPendingItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (delivery.isDeliveryItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    public void onEntityPickup(EntityPickupItemEvent event) {
        if (delivery.isDeliveryItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(delivery::isDeliveryItem);
    }

    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (delivery.isDeliveryItem(event.getMainHandItem()) || delivery.isDeliveryItem(event.getOffHandItem())
                || delivery.isPendingItem(event.getPlayer(), event.getMainHandItem())
                || delivery.isPendingItem(event.getPlayer(), event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            String deliveryMarker = delivery.marker(player.getItemOnCursor());
            close(player.getUniqueId(), player);
            if (deliveryMarker != null) {
                delivery.checkPlacement(player, deliveryMarker);
            }
        }
    }

    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        shopStore.rolledBackTransactions(player.getUniqueId()).whenComplete((transactionIds, error) ->
                sched.entity(player, () -> {
                    if (sessions.get(player.getUniqueId()) != null) {
                        return;
                    }
                    delivery.removeTemporaryMarkers(player);
                    if (error != null) {
                        logger.log(Level.WARNING, "Не удалось очистить временные marker-ы игрока "
                                + player.getUniqueId(), error);
                        return;
                    }
                    for (String transactionId : transactionIds) {
                        delivery.removeMarker(player.getInventory(), "transaction:" + transactionId);
                    }
                    delivery.deliverPending(player);
                    delivery.deliverRecovery(player);
                }));
    }

    public CompletableFuture<Void> recover() {
        return shopStore.preparedTransactions().thenCompose(transactions -> {
            List<CompletableFuture<Void>> recoveries = transactions.stream().map(delivery::recover).toList();
            return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
        });
    }

    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> closings = new java.util.ArrayList<>();
        for (UUID playerId : sessions.playerIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || Bukkit.isOwnedByCurrentRegion(player)) {
                closings.add(close(playerId, player, true));
                continue;
            }
            CompletableFuture<Void> closing = new CompletableFuture<>();
            closings.add(closing);
            sched.entity(player,
                    () -> close(playerId, player, true)
                            .whenComplete((ignored, error) -> complete(closing, error)),
                    () -> close(playerId, null, true)
                            .whenComplete((ignored, error) -> complete(closing, error)));
        }
        for (UUID playerId : sessions.pendingReturnPlayerIds()) {
            closings.add(depositPendingReturn(playerId));
        }
        return CompletableFuture.allOf(closings.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> close(UUID playerId, Player player) {
        return close(playerId, player, false);
    }

    private CompletableFuture<Void> close(UUID playerId, Player player, boolean persistPayment) {
        TradeSession session = sessions.remove(playerId);
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        boolean cancel = session.purchasePending && !session.escrowed;
        UUID transactionId = session.transactionId;
        if (cancel && player != null) {
            purchase.removePendingPlayerItem(player, session, transactionId);
        }
        if (cancel) {
            purchase.finishPending(playerId, session);
        } else if (!session.purchasePending) {
            sessions.release(session.shop.id());
        }
        CompletableFuture<Void> rollback = CompletableFuture.completedFuture(null);
        if (cancel && transactionId != null) {
            rollback = shopStore.rollback(transactionId.toString(), playerId, Map.of()).thenAccept(applied -> {
                if (applied) {
                    sched.global(() -> Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                            transactionId, session.shop.id(), playerId)));
                }
            });
        }
        EnumMap<Currency, Long> returned = new EnumMap<>(Currency.class);
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            if (item == null) {
                continue;
            }
            Currency currency = Currency.from(item.getType());
            if (currency != null) {
                returned.merge(currency, (long) item.getAmount(), Long::sum);
                session.inventory.setItem(slot, null);
            }
        }
        if (persistPayment || player == null || !player.isOnline()) {
            return CompletableFuture.allOf(rollback, deposit(playerId, returned));
        }
        sessions.mergePendingReturn(playerId, returned);
        sched.entity(player, () -> {
            EnumMap<Currency, Long> payment = sessions.removePendingReturn(playerId);
            if (payment != null) {
                if (player.isOnline()) {
                    this.payment.givePayment(player, payment);
                } else {
                    payment.forEach((currency, amount) -> balances.deposit(playerId, currency, amount));
                }
            }
        }, () -> depositPendingReturn(playerId));
        return rollback;
    }

    private CompletableFuture<Void> depositPendingReturn(UUID playerId) {
        EnumMap<Currency, Long> payment = sessions.removePendingReturn(playerId);
        return payment == null ? CompletableFuture.completedFuture(null) : deposit(playerId, payment);
    }

    private CompletableFuture<Void> deposit(UUID playerId, Map<Currency, Long> payment) {
        List<CompletableFuture<Void>> deposits = payment.entrySet().stream()
                .map(entry -> balances.deposit(playerId, entry.getKey(), entry.getValue())).toList();
        return CompletableFuture.allOf(deposits.toArray(CompletableFuture[]::new));
    }

    private void complete(CompletableFuture<Void> future, Throwable error) {
        if (error == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(error);
        }
    }

    private boolean isProductPickup(InventoryClickEvent event, TradeSession session) {
        return event.getClick() == ClickType.LEFT && event.getAction() == InventoryAction.PICKUP_ALL
                && TradeItem.isEmpty(event.getCursor()) && session.snapshot.containsKey(event.getRawSlot());
    }

    private Shop findShop(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            return shops.find(ShopKey.of(chest.getLocation()));
        }
        if (holder instanceof DoubleChest doubleChest) {
            Shop left = findHolder(doubleChest.getLeftSide());
            return left != null ? left : findHolder(doubleChest.getRightSide());
        }
        return null;
    }

    private Shop findHolder(InventoryHolder holder) {
        return holder instanceof Chest chest ? shops.find(ShopKey.of(chest.getLocation())) : null;
    }
}
