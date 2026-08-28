package ru.javaroot.list;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.config.Msg;
import ru.javaroot.api.CurrencyAmounts;
import ru.javaroot.api.ShopPosition;
import ru.javaroot.api.ShopSnapshot;
import ru.javaroot.api.event.AdjustmentType;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.api.event.ShopPurchaseCompletedEvent;
import ru.javaroot.api.event.ShopPurchasePrepareEvent;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.data.ShopStore;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.integration.SaleLog;
import ru.javaroot.item.ItemName;
import ru.javaroot.notification.NotifySvc;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.List;
import com.google.gson.Gson;

public class ShopTrade implements Listener {
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final BalanceStore balances;
    private final IntegrationHub integrations;
    private final NotifySvc notifications;
    private final SoundSvc sounds;
    private final Msg msg;
    private final ItemName itemNames;
    private final Sched sched;
    private final Logger logger;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, EnumMap<Currency, Long>> pendingReturns = new ConcurrentHashMap<>();
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();
    private final NamespacedKey deliveryKey;
    private volatile boolean accepting;

    public ShopTrade(JavaPlugin plugin, ShopMgr shops, ShopStore shopStore, BalanceStore balances,
                     IntegrationHub integrations,
                     NotifySvc notifications, SoundSvc sounds, Msg msg, ItemName itemNames, Sched sched,
                     Logger logger) {
        this.shops = shops;
        this.shopStore = shopStore;
        this.balances = balances;
        this.integrations = integrations;
        this.notifications = notifications;
        this.sounds = sounds;
        this.msg = msg;
        this.itemNames = itemNames;
        this.sched = sched;
        this.logger = logger;
        this.deliveryKey = new NamespacedKey(plugin, "delivery-transaction");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!accepting && (event.getInventory().getHolder() instanceof Chest
                || event.getInventory().getHolder() instanceof DoubleChest)) {
            event.setCancelled(true);
            return;
        }
        Shop shop = findShop(event.getInventory());
        if (shop == null) {
            return;
        }
        if (shop.ownerId().equals(player.getUniqueId())) {
            if (lockedShops.contains(shop.id())) {
                event.setCancelled(true);
                msg.send(player, "shop-busy");
            }
            return;
        }
        if (lockedShops.contains(shop.id()) || sessions.containsKey(player.getUniqueId())) {
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
        Session session = new Session(shop, event.getInventory(), event.getView().title(),
                new org.bukkit.Location(world, shop.x1(), shop.y1(), shop.z1()));
        if (lockedShops.add(shop.id())) {
            sessions.put(player.getUniqueId(), session);
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (deliveryItem(event.getCurrentItem()) || deliveryItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory) {
            return;
        }
        event.setCancelled(true);
        if (session.purchasePending) {
            return;
        }
        if (event.getRawSlot() < 0) {
            return;
        }
        if (event.getRawSlot() >= session.inventory.getSize()) {
            if (isCurrencyPickup(event) || isCurrencyPlacement(event)) {
                event.setCancelled(false);
            } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                movePaymentItem(event, session);
            }
            return;
        }
        if (isPaymentPickup(event, session)) {
            event.setCancelled(false);
            return;
        }
        if (isPaymentShiftPickup(event, session)) {
            movePaymentToPlayer(event, player);
            return;
        }
        if (isPaymentClick(event, session)) {
            event.setCancelled(false);
            return;
        }
        if (isProductPickup(event, session)) {
            startPendingProduct(event, player, session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (deliveryItem(event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }
            Session session = sessions.get(player.getUniqueId());
            if (session != null && event.getView().getTopInventory() == session.inventory) {
                event.setCancelled(true);
                if (session.purchasePending) {
                    return;
                }
                if (isPaymentDrag(event, session)) {
                    event.setCancelled(false);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (deliveryItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (deliveryItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (deliveryItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            close(player.getUniqueId(), player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer().getUniqueId(), null);
    }

    public boolean isBusy(long shopId) {
        return lockedShops.contains(shopId);
    }

    public boolean reserveRemoval(long shopId) {
        return lockedShops.add(shopId);
    }

    public void releaseRemoval(long shopId) {
        lockedShops.remove(shopId);
    }

    public void start() {
        accepting = true;
    }

    public void stopInput() {
        accepting = false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliverPending(event.getPlayer());
        deliverRecovery(event.getPlayer());
    }

    public CompletableFuture<Void> recover() {
        return shopStore.preparedTransactions().thenCompose(transactions -> {
            List<CompletableFuture<Void>> recoveries = transactions.stream()
                    .map(this::recover)
                    .toList();
            return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
        });
    }

    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> closings = new java.util.ArrayList<>();
        for (UUID playerId : Set.copyOf(sessions.keySet())) {
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
        for (UUID playerId : Set.copyOf(pendingReturns.keySet())) {
            closings.add(depositPendingReturn(playerId));
        }
        return CompletableFuture.allOf(closings.toArray(CompletableFuture[]::new));
    }

    private void startPendingProduct(InventoryClickEvent event, Player player, Session session) {
        int slot = event.getRawSlot();
        Shop shop = shops.find(session.shop.id());
        ItemStack product = session.inventory.getItem(slot);
        ItemStack expected = session.snapshot.get(slot);
        if (shop == null || product == null || expected == null || !product.isSimilar(expected)
                || product.getAmount() != expected.getAmount()) {
            return;
        }
        long paid = paymentTotal(session);
        if (paid < shop.price()) {
            msg.send(player, "purchase-insufficient");
            return;
        }
        if (!canFit(player.getInventory(), product)) {
            return;
        }
        preparePurchase(player, session, shop, slot, product.clone());
    }

    private void preparePurchase(Player player, Session session, Shop shop, int slot, ItemStack product) {
        UUID transactionId = UUID.randomUUID();
        EnumMap<Currency, Long> basePayment = paymentPlan(session, shop.price());
        ShopPurchasePrepareEvent event = new ShopPurchasePrepareEvent(transactionId, player.getUniqueId(),
                snapshot(shop), amounts(basePayment));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        Payment payment = adjustedPayment(session, basePayment, event.adjustments());
        if (payment == null) {
            msg.send(player, "purchase-insufficient");
            return;
        }
        session.purchasePending = true;
        session.transactionId = transactionId;
        session.escrowed = false;
        shopStore.prepareSale(transactionId.toString(), shop, player.getUniqueId(), slot,
                        product.getAmount(), shop.price(), payment.buyer, payment.seller,
                        product.serializeAsBytes(), gson.toJson(event.adjustments()))
                .thenRun(() -> {
                    sched.entity(player, () -> escrowAndComplete(player, session, shop,
                            slot, product, transactionId, payment, event.adjustments()),
                            () -> cancelPrepared(player, session, transactionId, Map.of()));
                })
                .exceptionally(error -> {
                    finishPending(player.getUniqueId(), session);
                    logger.log(Level.SEVERE, "Не удалось подготовить продажу #" + shop.id(), error);
                    return null;
                });
    }

    private void escrowAndComplete(Player player, Session session, Shop shop, int slot,
                                   ItemStack product, UUID transactionId, Payment payment,
                                   List<PurchaseAdjustment> adjustments) {
        if (sessions.get(player.getUniqueId()) != session || !session.purchasePending) {
            cancelPrepared(player, session, transactionId, Map.of());
            return;
        }
        ItemStack current = session.inventory.getItem(slot);
        if (current == null || !current.isSimilar(product) || current.getAmount() != product.getAmount()
                || !hasPayment(session, payment.buyer)) {
            cancelPrepared(player, session, transactionId, Map.of());
            return;
        }
        session.inventory.setItem(slot, null);
        takePayment(session, payment.buyer);
        session.escrowed = true;
        if (sched.region(session.location, () -> takePhysicalAndComplete(player, session, shop,
                slot, product, transactionId, payment, adjustments)) != Sched.ScheduleResult.SCHEDULED) {
            restoreFailedPurchase(player, session, shop, product, transactionId, payment.buyer,
                    slot, false, new IllegalStateException("Shop region is unavailable"));
        }
    }

    private void takePhysicalAndComplete(Player player, Session session, Shop shop, int slot,
                                         ItemStack product, UUID transactionId, Payment payment,
                                         List<PurchaseAdjustment> adjustments) {
        ItemStack physical = session.physical.getItem(slot);
        if (physical == null || !physical.isSimilar(product) || physical.getAmount() != product.getAmount()) {
            restoreFailedPurchase(player, session, shop, product, transactionId, payment.buyer,
                    slot, true, new IllegalStateException("Shop item changed before commit"));
            return;
        }
        session.physical.setItem(slot, null);
        completePurchase(player, session, shop, product, transactionId, payment, adjustments, slot);
    }

    private void completePurchase(Player player, Session session, Shop shop, ItemStack product,
                                  UUID transactionId, Payment payment,
                                  List<PurchaseAdjustment> adjustments, int itemSlot) {
        int items = product.getAmount();
        long amount = shop.price();
        SaleLog sale = new SaleLog(shop.ownerId(), shop.id(), "#" + shop.id(), product.getType(), items, amount);
        shopStore.completeSale(transactionId.toString(), shop, items, payment.seller)
                .thenAccept(completed -> {
                    if (!completed) {
                        restoreFailedPurchase(player, session, shop, product, transactionId, payment.buyer, itemSlot,
                                true, new IllegalStateException("Transaction is no longer prepared"));
                        return;
                    }
                    shops.recordSale(shop.id(), amount, items);
                    integrations.sale(sale);
                    notifications.sale(sale, transactionId);
                    Bukkit.getPluginManager().callEvent(new ShopPurchaseCompletedEvent(transactionId,
                            player.getUniqueId(), snapshot(shop), amount, amounts(payment.buyer),
                            amounts(payment.seller), adjustments));
                    finishPending(player.getUniqueId(), session);
                    deliver(player, transactionId.toString(), product, items, amount);
                })
                .exceptionally(error -> {
                    restoreFailedPurchase(player, session, shop, product, transactionId,
                            payment.buyer, itemSlot, true, error);
                    return null;
                });
    }

    private void deliver(Player player, String transactionId, ItemStack product, int items, long amount) {
        sched.entity(player, () -> {
            ItemStack delivery = deliveryItem(product, "transaction:" + transactionId);
            if (!player.isOnline() || !canFit(player.getInventory(), delivery)) {
                return;
            }
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(delivery);
            if (!overflow.isEmpty()) {
                return;
            }
            confirmDelivery(player, transactionId, () -> {
                sounds.buyerPurchase(player);
                msg.send(player, "purchase-success", Map.of("items", items, "amount", amount),
                        Map.of("item", itemNames.component(product.getType())));
            });
        });
    }

    private void deliverPending(Player player) {
        shopStore.pendingDeliveries(player.getUniqueId()).thenAccept(pending -> sched.entity(player, () -> {
            Set<String> active = pending.stream().map(ShopStore.PendingDelivery::transactionId)
                    .map(id -> "transaction:" + id).collect(java.util.stream.Collectors.toSet());
            clearStaleMarkers(player.getInventory(), "transaction:", active);
            for (ShopStore.PendingDelivery delivery : pending) {
                String marker = "transaction:" + delivery.transactionId();
                if (hasMarker(player.getInventory(), marker)) {
                    confirmDelivery(player, delivery.transactionId());
                    continue;
                }
                ItemStack product = deliveryItem(ItemStack.deserializeBytes(delivery.itemSnapshot()), marker);
                if (!canFit(player.getInventory(), product)) {
                    return;
                }
                if (player.getInventory().addItem(product).isEmpty()) {
                    confirmDelivery(player, delivery.transactionId());
                }
            }
        }));
    }

    private void deliverRecovery(Player player) {
        shopStore.recoveryItems(player.getUniqueId()).thenAccept(items -> sched.entity(player, () -> {
            Set<String> active = items.stream().map(ShopStore.RecoveryItem::id)
                    .map(id -> "recovery:" + id).collect(java.util.stream.Collectors.toSet());
            clearStaleMarkers(player.getInventory(), "recovery:", active);
            for (ShopStore.RecoveryItem recovery : items) {
                String marker = "recovery:" + recovery.id();
                if (hasMarker(player.getInventory(), marker)) {
                    confirmRecovery(player, recovery.id());
                    continue;
                }
                ItemStack item = deliveryItem(ItemStack.deserializeBytes(recovery.itemSnapshot()), marker);
                if (!canFit(player.getInventory(), item)) {
                    return;
                }
                if (player.getInventory().addItem(item).isEmpty()) {
                    confirmRecovery(player, recovery.id());
                }
            }
        }));
    }

    private void confirmDelivery(Player player, String transactionId, Runnable completed) {
        String marker = "transaction:" + transactionId;
        shopStore.delivered(transactionId).thenRun(() -> sched.entity(player, () -> {
            clearMarker(player.getInventory(), marker);
            if (completed != null) {
                completed.run();
            }
        }));
    }

    private void confirmDelivery(Player player, String transactionId) {
        confirmDelivery(player, transactionId, null);
    }

    private void confirmRecovery(Player player, long recoveryId) {
        String marker = "recovery:" + recoveryId;
        shopStore.recoveryDelivered(recoveryId)
                .thenRun(() -> sched.entity(player, () -> clearMarker(player.getInventory(), marker)));
    }

    private ItemStack deliveryItem(ItemStack source, String marker) {
        ItemStack item = source.clone();
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(deliveryKey, PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
        return item;
    }

    private boolean deliveryItem(ItemStack item) {
        return !isEmpty(item) && item.getItemMeta().getPersistentDataContainer()
                .has(deliveryKey, PersistentDataType.STRING);
    }

    private boolean hasMarker(Inventory inventory, String marker) {
        for (ItemStack item : inventory.getContents()) {
            if (marker.equals(marker(item))) {
                return true;
            }
        }
        return false;
    }

    private void clearStaleMarkers(Inventory inventory, String prefix, Set<String> active) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            String marker = marker(item);
            if (marker != null && marker.startsWith(prefix) && !active.contains(marker)) {
                clearMarker(item);
                inventory.setItem(slot, item);
            }
        }
    }

    private void clearMarker(Inventory inventory, String marker) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (marker.equals(marker(item))) {
                clearMarker(item);
                inventory.setItem(slot, item);
            }
        }
    }

    private void clearMarker(ItemStack item) {
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(deliveryKey);
        item.setItemMeta(meta);
    }

    private String marker(ItemStack item) {
        return isEmpty(item) ? null : item.getItemMeta().getPersistentDataContainer()
                .get(deliveryKey, PersistentDataType.STRING);
    }

    private CompletableFuture<Void> recover(ShopStore.PreparedTransaction transaction) {
        CompletableFuture<Void> recovered = new CompletableFuture<>();
        Shop shop = shops.find(transaction.shopId());
        if (shop == null) {
            finishRecovery(transaction, true, recovered);
            return recovered;
        }
        if (sched.global(() -> {
            org.bukkit.World world = Bukkit.getWorld(shop.worldId());
            if (world == null) {
                finishRecovery(transaction, true, recovered);
                return;
            }
            org.bukkit.Location location = new org.bukkit.Location(world, shop.x1(), shop.y1(), shop.z1());
            if (sched.region(location, () -> recoverOnRegion(transaction, shop, location, recovered))
                    != Sched.ScheduleResult.SCHEDULED) {
                finishRecovery(transaction, true, recovered);
            }
        }) != Sched.ScheduleResult.SCHEDULED) {
            finishRecovery(transaction, true, recovered);
        }
        return recovered;
    }

    private void recoverOnRegion(ShopStore.PreparedTransaction transaction, Shop shop,
                                 org.bukkit.Location location, CompletableFuture<Void> recovered) {
        if (location.getBlock().getType() != org.bukkit.Material.CHEST) {
            finishRecovery(transaction, true, recovered);
            return;
        }
        Inventory inventory = ((Chest) location.getBlock().getState()).getInventory();
        ItemStack product = ItemStack.deserializeBytes(transaction.itemSnapshot());
        ItemStack current = inventory.getItem(transaction.itemSlot());
        boolean recoverItem = current != null && (!current.isSimilar(product)
                || current.getAmount() != product.getAmount());
        if (current == null) {
            inventory.setItem(transaction.itemSlot(), product);
        }
        finishRecovery(transaction, recoverItem, recovered);
    }

    private void finishRecovery(ShopStore.PreparedTransaction transaction, boolean recoverItem,
                                CompletableFuture<Void> recovered) {
        shopStore.recoverRollback(transaction, recoverItem).whenComplete((applied, error) -> {
            if (error != null) {
                recovered.completeExceptionally(error);
                return;
            }
            if (applied) {
                Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                        UUID.fromString(transaction.transactionId()), transaction.shopId(), transaction.buyerId()));
            }
            recovered.complete(null);
        });
    }

    private void restoreFailedPurchase(Player player, Session session, Shop shop, ItemStack product,
                                       UUID transactionId, Map<Currency, Long> payment,
                                       int itemSlot, boolean productRemoved, Throwable error) {
        logger.log(Level.SEVERE, "Не удалось завершить продажу #" + shop.id(), error);
        sched.entity(player, () -> {
            if (sessions.get(player.getUniqueId()) == session && session.inventory.getItem(itemSlot) == null) {
                session.inventory.setItem(itemSlot, product.clone());
            }
            finishPending(player.getUniqueId(), session);
        }, () -> finishPending(player.getUniqueId(), session));
        if (!productRemoved) {
            rollbackFailedPurchase(player, shop, product, transactionId, payment, false);
            return;
        }
        Runnable restore = () -> {
            ItemStack current = session.physical.getItem(itemSlot);
            boolean recoverItem = current != null && (!current.isSimilar(product)
                    || current.getAmount() != product.getAmount());
            if (current == null) {
                session.physical.setItem(itemSlot, product.clone());
            }
            rollbackFailedPurchase(player, shop, product, transactionId, payment, recoverItem);
        };
        if (Bukkit.isOwnedByCurrentRegion(session.location)) {
            restore.run();
        } else if (sched.region(session.location, restore) != Sched.ScheduleResult.SCHEDULED) {
            rollbackFailedPurchase(player, shop, product, transactionId, payment, true);
        }
    }

    private void rollbackFailedPurchase(Player player, Shop shop, ItemStack product,
                                        UUID transactionId, Map<Currency, Long> payment,
                                        boolean recoverItem) {
        shopStore.rollback(transactionId.toString(), player.getUniqueId(), payment,
                        shop.ownerId(), product.serializeAsBytes(), recoverItem)
                .thenAccept(applied -> {
                    if (applied) {
                        Bukkit.getPluginManager().callEvent(
                                new ShopTransactionRolledBackEvent(transactionId, shop.id(), player.getUniqueId()));
                    }
                });
    }

    private void cancelPrepared(Player player, Session session, UUID transactionId,
                                Map<Currency, Long> refund) {
        finishPending(player.getUniqueId(), session);
        shopStore.rollback(transactionId.toString(), player.getUniqueId(), refund)
                .thenAccept(applied -> {
                    if (applied) {
                        Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                                transactionId, session.shop.id(), player.getUniqueId()));
                    }
                });
    }

    private boolean isProductPickup(InventoryClickEvent event, Session session) {
        return event.getClick() == ClickType.LEFT
                && event.getAction() == InventoryAction.PICKUP_ALL
                && isEmpty(event.getCursor())
                && session.snapshot.containsKey(event.getRawSlot());
    }

    private boolean isPaymentPickup(InventoryClickEvent event, Session session) {
        return session.paymentSlots.contains(event.getRawSlot()) && isCurrencyPickup(event);
    }

    private boolean isPaymentShiftPickup(InventoryClickEvent event, Session session) {
        return session.paymentSlots.contains(event.getRawSlot())
                && (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && !isEmpty(event.getCurrentItem())
                && Currency.from(event.getCurrentItem().getType()) != null;
    }

    private boolean isCurrencyPickup(InventoryClickEvent event) {
        if ((event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || (event.getAction() != InventoryAction.PICKUP_ALL && event.getAction() != InventoryAction.PICKUP_HALF)
                || !isEmpty(event.getCursor())) {
            return false;
        }
        ItemStack current = event.getCurrentItem();
        return !isEmpty(current) && Currency.from(current.getType()) != null;
    }

    private boolean isCurrencyPlacement(InventoryClickEvent event) {
        if ((event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || isEmpty(event.getCursor())) {
            return false;
        }
        Currency currency = Currency.from(event.getCursor().getType());
        return currency != null && canAcceptPayment(event.getCurrentItem(), currency);
    }

    private void movePaymentToPlayer(InventoryClickEvent event, Player player) {
        ItemStack current = event.getCurrentItem();
        if (!canFit(player.getInventory(), current)) {
            return;
        }
        player.getInventory().addItem(current.clone());
        event.getView().getTopInventory().setItem(event.getRawSlot(), null);
    }

    private boolean isPaymentClick(InventoryClickEvent event, Session session) {
        int slot = event.getRawSlot();
        if (!session.paymentSlots.contains(slot)
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || isEmpty(event.getCursor())) {
            return false;
        }
        Currency currency = Currency.from(event.getCursor().getType());
        return currency != null && canAcceptPayment(event.getCurrentItem(), currency);
    }

    private void movePaymentItem(InventoryClickEvent event, Session session) {
        ItemStack current = event.getCurrentItem();
        Currency currency = isEmpty(current) ? null : Currency.from(current.getType());
        if (currency == null || event.getClickedInventory() == null) {
            return;
        }
        int amount = current.getAmount();
        for (int slot : session.paymentSlots) {
            ItemStack target = session.inventory.getItem(slot);
            if (!canAcceptPayment(target, currency)) {
                continue;
            }
            boolean empty = isEmpty(target);
            int room = empty ? current.getMaxStackSize() : target.getMaxStackSize() - target.getAmount();
            int moved = Math.min(amount, room);
            if (moved < 1) {
                continue;
            }
            if (empty) {
                ItemStack placed = current.clone();
                placed.setAmount(moved);
                session.inventory.setItem(slot, placed);
            } else {
                target.setAmount(target.getAmount() + moved);
            }
            amount -= moved;
            if (amount == 0) {
                break;
            }
        }
        if (amount == current.getAmount()) {
            return;
        }
        Inventory source = event.getClickedInventory();
        if (amount == 0) {
            source.setItem(event.getSlot(), null);
        } else {
            current.setAmount(amount);
            source.setItem(event.getSlot(), current);
        }
    }

    private boolean isPaymentDrag(InventoryDragEvent event, Session session) {
        if (event.getRawSlots().isEmpty() || isEmpty(event.getOldCursor())) {
            return false;
        }
        Currency currency = Currency.from(event.getOldCursor().getType());
        if (currency == null) {
            return false;
        }
        int topSize = session.inventory.getSize();
        boolean topPayment = event.getRawSlots().stream()
                .allMatch(slot -> slot < topSize && session.paymentSlots.contains(slot)
                        && canAcceptPayment(session.inventory.getItem(slot), currency));
        boolean bottomInventory = event.getRawSlots().stream()
                .allMatch(slot -> slot >= topSize
                        && canAcceptPayment(event.getView().getBottomInventory().getItem(slot - topSize), currency));
        return topPayment || bottomInventory;
    }

    private boolean canAcceptPayment(ItemStack current, Currency currency) {
        return isEmpty(current)
                || (current.getType() == currency.material() && current.getAmount() < current.getMaxStackSize());
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private long paymentTotal(Session session) {
        long total = 0;
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            if (item != null && Currency.from(item.getType()) != null) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private EnumMap<Currency, Long> paymentPlan(Session session, long price) {
        EnumMap<Currency, Long> result = new EnumMap<>(Currency.class);
        long remaining = price;
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            Currency currency = item == null ? null : Currency.from(item.getType());
            if (currency == null) {
                continue;
            }
            long used = Math.min(item.getAmount(), remaining);
            result.merge(currency, used, Long::sum);
            remaining -= used;
            if (remaining == 0) {
                break;
            }
        }
        return result;
    }

    private Payment adjustedPayment(Session session, EnumMap<Currency, Long> base,
                                    List<PurchaseAdjustment> adjustments) {
        EnumMap<Currency, Long> buyer = new EnumMap<>(base);
        EnumMap<Currency, Long> seller = new EnumMap<>(base);
        try {
            for (PurchaseAdjustment adjustment : adjustments) {
                for (Currency currency : Currency.values()) {
                    long value = adjustment.amounts().amount(currency == Currency.DIAMOND_ORE
                            ? ru.javaroot.api.CurrencyType.DIAMOND_ORE
                            : ru.javaroot.api.CurrencyType.DEEPSLATE_DIAMOND_ORE);
                    if (value == 0) {
                        continue;
                    }
                    if (adjustment.type() == AdjustmentType.BUYER_SURCHARGE) {
                        buyer.put(currency, Math.addExact(buyer.getOrDefault(currency, 0L), value));
                    } else {
                        long payout = seller.getOrDefault(currency, 0L);
                        if (payout < value) {
                            return null;
                        }
                        seller.put(currency, payout - value);
                    }
                }
            }
        } catch (ArithmeticException exception) {
            return null;
        }
        return hasPayment(session, buyer) ? new Payment(buyer, seller) : null;
    }

    private boolean hasPayment(Session session, Map<Currency, Long> required) {
        EnumMap<Currency, Long> available = new EnumMap<>(Currency.class);
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            Currency currency = item == null ? null : Currency.from(item.getType());
            if (currency != null) {
                available.merge(currency, (long) item.getAmount(), Long::sum);
            }
        }
        return required.entrySet().stream()
                .allMatch(entry -> available.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    private void takePayment(Session session, Map<Currency, Long> required) {
        EnumMap<Currency, Long> remaining = new EnumMap<>(Currency.class);
        remaining.putAll(required);
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            Currency currency = item == null ? null : Currency.from(item.getType());
            if (currency == null) {
                continue;
            }
            long needed = remaining.getOrDefault(currency, 0L);
            int used = (int) Math.min(item.getAmount(), needed);
            if (used == 0) {
                continue;
            }
            item.setAmount(item.getAmount() - used);
            if (item.getAmount() == 0) {
                session.inventory.setItem(slot, null);
            }
            remaining.put(currency, needed - used);
        }
    }

    private CurrencyAmounts amounts(Map<Currency, Long> values) {
        return new CurrencyAmounts(values.getOrDefault(Currency.DIAMOND_ORE, 0L),
                values.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L));
    }

    private ShopSnapshot snapshot(Shop shop) {
        ShopPosition first = new ShopPosition(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
        ShopPosition second = shop.x2() == null ? null
                : new ShopPosition(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
        return new ShopSnapshot(shop.id(), shop.ownerId(), shop.ownerName(), first, second,
                shop.price(), shop.earnedTotal(), shop.soldSlots(), shop.soldItems(), shop.createdAt());
    }

    private void givePayment(Player player, Map<Currency, Long> payment) {
        for (Map.Entry<Currency, Long> entry : payment.entrySet()) {
            long left = entry.getValue();
            while (left > 0) {
                int stack = (int) Math.min(64, left);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(
                        new ItemStack(entry.getKey().material(), stack));
                long extra = overflow.values().stream().mapToLong(ItemStack::getAmount).sum();
                if (extra > 0) {
                    player.getWorld().dropItemNaturally(player.getLocation(),
                            new ItemStack(entry.getKey().material(), (int) extra));
                }
                left -= stack;
            }
        }
    }

    private boolean canFit(Inventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack current : inventory.getContents()) {
            if (current == null || current.getType().isAir()) {
                remaining -= item.getMaxStackSize();
            } else if (current.isSimilar(item)) {
                remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private CompletableFuture<Void> close(UUID playerId, Player player) {
        return close(playerId, player, false);
    }

    private CompletableFuture<Void> close(UUID playerId, Player player, boolean persistPayment) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!session.purchasePending) {
            lockedShops.remove(session.shop.id());
        }
        CompletableFuture<Void> rollback = CompletableFuture.completedFuture(null);
        if (session.purchasePending && !session.escrowed && session.transactionId != null) {
            UUID transactionId = session.transactionId;
            rollback = shopStore.rollback(transactionId.toString(), playerId, Map.of()).thenAccept(applied -> {
                finishPending(playerId, session);
                if (applied) {
                    Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                            transactionId, session.shop.id(), playerId));
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
        pendingReturns.merge(playerId, returned, (current, added) -> {
            added.forEach((currency, amount) -> current.merge(currency, amount, Long::sum));
            return current;
        });
        sched.entity(player, () -> {
            EnumMap<Currency, Long> payment = pendingReturns.remove(playerId);
            if (payment != null) {
                if (player.isOnline()) {
                    givePayment(player, payment);
                } else {
                    payment.forEach((currency, amount) -> balances.deposit(playerId, currency, amount));
                }
            }
        }, () -> depositPendingReturn(playerId));
        return rollback;
    }

    private CompletableFuture<Void> depositPendingReturn(UUID playerId) {
        EnumMap<Currency, Long> payment = pendingReturns.remove(playerId);
        return payment == null ? CompletableFuture.completedFuture(null) : deposit(playerId, payment);
    }

    private CompletableFuture<Void> deposit(UUID playerId, Map<Currency, Long> payment) {
        List<CompletableFuture<Void>> deposits = payment.entrySet().stream()
                .map(entry -> balances.deposit(playerId, entry.getKey(), entry.getValue()))
                .toList();
        return CompletableFuture.allOf(deposits.toArray(CompletableFuture[]::new));
    }

    private void complete(CompletableFuture<Void> future, Throwable error) {
        if (error == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(error);
        }
    }

    private void finishPending(UUID playerId, Session session) {
        session.purchasePending = false;
        session.transactionId = null;
        session.escrowed = false;
        if (sessions.get(playerId) != session) {
            lockedShops.remove(session.shop.id());
        }
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

    private static class Session {
        private final Shop shop;
        private final Inventory physical;
        private final Inventory inventory;
        private final Map<Integer, ItemStack> snapshot = new HashMap<>();
        private final Set<Integer> paymentSlots = new HashSet<>();
        private final org.bukkit.Location location;
        private volatile boolean purchasePending;
        private volatile boolean escrowed;
        private UUID transactionId;
        private Session(Shop shop, Inventory physical, net.kyori.adventure.text.Component title,
                        org.bukkit.Location location) {
            this.shop = shop;
            this.physical = physical;
            this.inventory = Bukkit.createInventory(null, physical.getSize(), title);
            this.location = location;
            for (int slot = 0; slot < physical.getSize(); slot++) {
                ItemStack item = physical.getItem(slot);
                if (item == null || item.getType().isAir()) {
                    paymentSlots.add(slot);
                } else {
                    snapshot.put(slot, item.clone());
                    inventory.setItem(slot, item.clone());
                }
            }
        }
    }

    private record Payment(EnumMap<Currency, Long> buyer, EnumMap<Currency, Long> seller) {
    }
}
