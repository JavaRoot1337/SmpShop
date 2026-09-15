package ru.javaroot.list.trade;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.DragType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.data.ShopStore;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopMgr;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class TradeDeliverySvc {
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final Sched sched;
    private final TradeSessionStore sessions;
    private final NamespacedKey deliveryKey;

    TradeDeliverySvc(JavaPlugin plugin, ShopMgr shops, ShopStore shopStore, Sched sched,
                                TradeSessionStore sessions) {
        this.shops = shops;
        this.shopStore = shopStore;
        this.sched = sched;
        this.sessions = sessions;
        this.deliveryKey = new NamespacedKey(plugin, "delivery-transaction");
    }

    void checkPlacement(Player player, String deliveryMarker) {
        sched.entity(player, () -> {
            if (deliveryMarker.equals(marker(player.getItemOnCursor()))
                    || !hasMarker(player.getInventory(), deliveryMarker)) {
                return;
            }
            if (deliveryMarker.startsWith(TradeMarkers.TRANSACTION_PREFIX)) {
                confirmDelivery(player, deliveryMarker.substring(TradeMarkers.TRANSACTION_PREFIX.length()));
            } else if (deliveryMarker.startsWith(TradeMarkers.RECOVERY_PREFIX)) {
                confirmRecovery(player, Long.parseLong(deliveryMarker.substring(TradeMarkers.RECOVERY_PREFIX.length())));
            }
        });
    }

    boolean isDeliveryPlacement(org.bukkit.event.inventory.InventoryClickEvent event) {
        return event.getClickedInventory() == event.getView().getBottomInventory()
                && event.getClick() == org.bukkit.event.inventory.ClickType.LEFT
                && event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ALL
                && TradeItem.isEmpty(event.getCurrentItem()) && !TradeItem.isEmpty(event.getCursor())
                && event.getCursor().getAmount() >= 1
                && event.getCursor().getAmount() <= event.getCursor().getMaxStackSize();
    }

    boolean isDeliveryPlacement(org.bukkit.event.inventory.InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getType() != DragType.EVEN || event.getRawSlots().size() != 1) {
            return false;
        }
        int rawSlot = event.getRawSlots().iterator().next();
        return rawSlot >= topSize
                && TradeItem.isEmpty(event.getView().getBottomInventory().getItem(rawSlot - topSize))
                && event.getOldCursor().getAmount() >= 1
                && event.getOldCursor().getAmount() <= event.getOldCursor().getMaxStackSize();
    }

    void deliverPending(Player player) {
        shopStore.pendingDeliveries(player.getUniqueId()).thenAccept(pending -> sched.entity(player, () -> {
            Set<String> active = pending.stream().map(ShopStore.PendingDelivery::transactionId)
                    .map(TradeMarkers::transaction).collect(java.util.stream.Collectors.toSet());
            clearStaleMarkers(player.getInventory(), TradeMarkers.TRANSACTION_PREFIX, active);
            for (ShopStore.PendingDelivery delivery : pending) {
                String marker = TradeMarkers.transaction(delivery.transactionId());
                if (hasMarker(player.getInventory(), marker)) {
                    confirmDelivery(player, delivery.transactionId());
                    continue;
                }
                ItemStack product = deliveryItem(ItemStack.deserializeBytes(delivery.itemSnapshot()), marker);
                if (!TradeItem.canFit(player.getInventory(), product)) {
                    return;
                }
                if (player.getInventory().addItem(product).isEmpty()) {
                    confirmDelivery(player, delivery.transactionId());
                } else {
                    removeMarker(player.getInventory(), marker);
                }
            }
        }));
    }

    void deliverRecovery(Player player) {
        shopStore.recoveryItems(player.getUniqueId()).thenAccept(items -> sched.entity(player, () -> {
            Set<String> active = items.stream().map(ShopStore.RecoveryItem::id)
                    .map(TradeMarkers::recovery).collect(java.util.stream.Collectors.toSet());
            clearStaleMarkers(player.getInventory(), TradeMarkers.RECOVERY_PREFIX, active);
            for (ShopStore.RecoveryItem recovery : items) {
                String marker = TradeMarkers.recovery(recovery.id());
                if (hasMarker(player.getInventory(), marker)) {
                    confirmRecovery(player, recovery.id());
                    continue;
                }
                ItemStack item = deliveryItem(ItemStack.deserializeBytes(recovery.itemSnapshot()), marker);
                if (!TradeItem.canFit(player.getInventory(), item)) {
                    return;
                }
                if (player.getInventory().addItem(item).isEmpty()) {
                    confirmRecovery(player, recovery.id());
                } else {
                    removeMarker(player.getInventory(), marker);
                }
            }
        }));
    }

    CompletableFuture<Void> recover(ShopStore.PreparedTransaction transaction) {
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

    boolean isDeliveryItem(ItemStack item) {
        return !TradeItem.isEmpty(item) && item.getItemMeta().getPersistentDataContainer()
                .has(deliveryKey, PersistentDataType.STRING);
    }

    ItemStack deliveryItem(ItemStack source, String marker) {
        ItemStack item = source.clone();
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(deliveryKey, PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
        return item;
    }

    String marker(ItemStack item) {
        return TradeItem.isEmpty(item) ? null : item.getItemMeta().getPersistentDataContainer()
                .get(deliveryKey, PersistentDataType.STRING);
    }

    boolean isPendingItem(Player player, ItemStack item) {
        TradeSession session = sessions.get(player.getUniqueId());
        return session != null && session.purchasePending
                && sessionsFor(item).stream().anyMatch(value -> value == session);
    }

    boolean isPendingItem(ItemStack item) {
        return sessionsFor(item).stream().anyMatch(value -> value.purchasePending);
    }

    void removeTemporaryMarkers(Player player) {
        removeMarkers(player.getInventory(), TradeMarkers.PENDING_PREFIX);
        String cursorMarker = marker(player.getItemOnCursor());
        if (cursorMarker != null && cursorMarker.startsWith(TradeMarkers.PENDING_PREFIX)) {
            player.getOpenInventory().setCursor(null);
        }
    }

    void removeMarker(Inventory inventory, String marker) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (marker.equals(marker(inventory.getItem(slot)))) {
                inventory.setItem(slot, null);
            }
        }
    }

    void clearMarker(Inventory inventory, String marker) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (marker.equals(marker(item))) {
                clearMarker(item);
                inventory.setItem(slot, item);
            }
        }
    }

    void clearMarker(ItemStack item) {
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(deliveryKey);
        item.setItemMeta(meta);
    }

    boolean hasMarker(Inventory inventory, String marker) {
        for (ItemStack item : inventory.getContents()) {
            if (marker.equals(marker(item))) {
                return true;
            }
        }
        return false;
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
                cleanupTransactionMarker(transaction.buyerId(), TradeMarkers.transaction(transaction.transactionId()));
                sched.global(() -> Bukkit.getPluginManager().callEvent(new ShopTransactionRolledBackEvent(
                        UUID.fromString(transaction.transactionId()), transaction.shopId(), transaction.buyerId())));
            }
            recovered.complete(null);
        });
    }

    private void cleanupTransactionMarker(UUID buyerId, String marker) {
        sched.global(() -> {
            Player buyer = Bukkit.getPlayer(buyerId);
            if (buyer != null) {
                sched.entity(buyer, () -> removeMarker(buyer.getInventory(), marker));
            }
        });
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

    private void removeMarkers(Inventory inventory, String prefix) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (marker(inventory.getItem(slot)) instanceof String marker && marker.startsWith(prefix)) {
                inventory.setItem(slot, null);
            }
        }
    }

    private List<TradeSession> sessionsFor(ItemStack item) {
        return sessions.playerIds().stream().map(sessions::get)
                .filter(session -> session != null && session.purchasePending
                        && isPendingSource(item, session.pendingProduct))
                .toList();
    }

    boolean isPendingSource(ItemStack item, ItemStack expected) {
        String value = marker(item);
        return expected != null && (!isDeliveryItem(item) || (value != null && value.startsWith(TradeMarkers.PENDING_PREFIX)))
                && !TradeItem.isEmpty(item) && pendingSimilar(item, expected, value)
                && item.getAmount() == expected.getAmount();
    }

    private boolean pendingSimilar(ItemStack item, ItemStack expected, String value) {
        if (value == null) {
            return item.isSimilar(expected);
        }
        ItemStack plain = item.clone();
        clearMarker(plain);
        return plain.isSimilar(expected);
    }

    void confirmDelivery(Player player, String transactionId) {
        String marker = TradeMarkers.transaction(transactionId);
        shopStore.delivered(transactionId, player.getUniqueId())
                .thenAccept(delivered -> {
                    if (delivered) {
                        sched.entity(player, () -> clearMarker(player.getInventory(), marker),
                                () -> { });
                    }
                });
    }

    void confirmDelivery(Player player, String transactionId, Runnable completed) {
        String marker = TradeMarkers.transaction(transactionId);
        shopStore.delivered(transactionId, player.getUniqueId()).thenAccept(delivered -> {
            if (!delivered) {
                return;
            }
            sched.entity(player, () -> {
                clearMarker(player.getInventory(), marker);
                if (completed != null) {
                    completed.run();
                }
            }, () -> { });
        });
    }

    private void confirmRecovery(Player player, long recoveryId) {
        String marker = TradeMarkers.recovery(recoveryId);
        shopStore.recoveryDelivered(recoveryId, player.getUniqueId()).thenAccept(delivered -> {
            if (delivered) {
                sched.entity(player, () -> clearMarker(player.getInventory(), marker), () -> { });
            }
        });
    }

}
