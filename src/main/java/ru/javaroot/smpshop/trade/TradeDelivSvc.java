package ru.javaroot.smpshop.trade;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import ru.javaroot.api.event.ShopTransactionRolledBackEvent;
import ru.javaroot.smpshop.db.TxRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopMgr;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

final class TradeDelivSvc {
    private final ShopMgr shops;
    private final TxRepo txRepo;
    private final Exec exec;
    private final TradeStore store;
    private final NamespacedKey delivKey;

    TradeDelivSvc(Plugin plug, ShopMgr shops, TxRepo txRepo, Exec exec, TradeStore store) {
        this.shops = shops;
        this.txRepo = txRepo;
        this.exec = exec;
        this.store = store;
        this.delivKey = new NamespacedKey(plug, "delivery-transaction");
    }

    void checkPlacement(Player p, String marker) {
        exec.entity(p, () -> {
            if (marker.equals(marker(p.getItemOnCursor())) || !hasMarker(p.getInventory(), marker)) {
                return;
            }
            if (marker.startsWith(TradeMarkers.TRANSACTION_PREFIX)) {
                confirmDelivery(p, marker.substring(TradeMarkers.TRANSACTION_PREFIX.length()), null);
            } else if (marker.startsWith(TradeMarkers.RECOVERY_PREFIX)) {
                confirmRecovery(p, Long.parseLong(marker.substring(TradeMarkers.RECOVERY_PREFIX.length())));
            }
        });
    }

    boolean isDeliveryPlacement(InventoryClickEvent event) {
        return event.getClickedInventory() == event.getView().getBottomInventory()
                && event.getClick() == ClickType.LEFT
                && event.getAction() == InventoryAction.PLACE_ALL
                && TradeItem.isEmpty(event.getCurrentItem())
                && !TradeItem.isEmpty(event.getCursor())
                && event.getCursor().getAmount() >= 1
                && event.getCursor().getAmount() <= event.getCursor().getMaxStackSize();
    }

    boolean isDeliveryPlacement(InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getType() != DragType.EVEN || event.getRawSlots().size() != 1) {
            return false;
        }
        int raw = event.getRawSlots().iterator().next();
        return raw >= topSize
                && TradeItem.isEmpty(event.getView().getBottomInventory().getItem(raw - topSize))
                && event.getOldCursor().getAmount() >= 1
                && event.getOldCursor().getAmount() <= event.getOldCursor().getMaxStackSize();
    }

    void deliverPending(Player p) {
        txRepo.pendingDeliveries(p.getUniqueId()).thenAccept(pending -> exec.entity(p, () -> {
            Set<String> active = pending.stream()
                    .map(TxRepo.PendingDelivery::txId)
                    .map(TradeMarkers::transaction)
                    .collect(Collectors.toSet());
            clearStaleMarkers(p.getInventory(), TradeMarkers.TRANSACTION_PREFIX, active);
            for (TxRepo.PendingDelivery d : pending) {
                String marker = TradeMarkers.transaction(d.txId());
                if (hasMarker(p.getInventory(), marker)) {
                    confirmDelivery(p, d.txId(), null);
                    continue;
                }
                ItemStack product = deliveryItem(ItemStack.deserializeBytes(d.itemSnapshot()), marker);
                if (!TradeItem.canFit(p.getInventory(), product)) {
                    return;
                }
                if (p.getInventory().addItem(product).isEmpty()) {
                    confirmDelivery(p, d.txId(), null);
                } else {
                    removeMarker(p.getInventory(), marker);
                }
            }
        }));
    }

    void deliverRecovery(Player p) {
        txRepo.recoveryItems(p.getUniqueId()).thenAccept(items -> exec.entity(p, () -> {
            Set<String> active = items.stream()
                    .map(TxRepo.RecoveryItem::id)
                    .map(TradeMarkers::recovery)
                    .collect(Collectors.toSet());
            clearStaleMarkers(p.getInventory(), TradeMarkers.RECOVERY_PREFIX, active);
            for (TxRepo.RecoveryItem rec : items) {
                String marker = TradeMarkers.recovery(rec.id());
                if (hasMarker(p.getInventory(), marker)) {
                    confirmRecovery(p, rec.id());
                    continue;
                }
                ItemStack item = deliveryItem(ItemStack.deserializeBytes(rec.itemSnapshot()), marker);
                if (!TradeItem.canFit(p.getInventory(), item)) {
                    return;
                }
                if (p.getInventory().addItem(item).isEmpty()) {
                    confirmRecovery(p, rec.id());
                } else {
                    removeMarker(p.getInventory(), marker);
                }
            }
        }));
    }

    CompletableFuture<Void> recover(TxRepo.PreparedTx tx) {
        CompletableFuture<Void> recovered = new CompletableFuture<>();
        Shop shop = shops.find(tx.shopId());
        if (shop == null) {
            finishRecovery(tx, true, recovered);
            return recovered;
        }
        if (exec.global(() -> {
            World w = Bukkit.getWorld(shop.worldId());
            if (w == null) {
                finishRecovery(tx, true, recovered);
                return;
            }
            Location loc = new Location(w, shop.x1(), shop.y1(), shop.z1());
            if (exec.region(loc, () -> recoverOnRegion(tx, loc, recovered)) != Exec.Result.SCHEDULED) {
                finishRecovery(tx, true, recovered);
            }
        }) != Exec.Result.SCHEDULED) {
            finishRecovery(tx, true, recovered);
        }
        return recovered;
    }

    boolean isDeliveryItem(ItemStack item) {
        return !TradeItem.isEmpty(item) && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(delivKey, PersistentDataType.STRING);
    }

    ItemStack deliveryItem(ItemStack src, String marker) {
        ItemStack item = src.clone();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(delivKey, PersistentDataType.STRING, marker);
        item.setItemMeta(meta);
        return item;
    }

    String marker(ItemStack item) {
        if (TradeItem.isEmpty(item) || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(delivKey, PersistentDataType.STRING);
    }

    boolean isPendingItem(Player p, ItemStack item) {
        TradeSess sess = store.get(p.getUniqueId());
        return sess != null && sess.purchasePending && sessionsFor(item).contains(sess);
    }

    boolean isPendingItem(ItemStack item) {
        return sessionsFor(item).stream().anyMatch(s -> s.purchasePending);
    }

    void removeTemporaryMarkers(Player p) {
        removeMarkers(p.getInventory(), TradeMarkers.PENDING_PREFIX);
        String cursorMarker = marker(p.getItemOnCursor());
        if (cursorMarker != null && cursorMarker.startsWith(TradeMarkers.PENDING_PREFIX)) {
            p.getOpenInventory().setCursor(null);
        }
    }

    void removeMarker(Inventory inv, String marker) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (marker.equals(marker(inv.getItem(slot)))) {
                inv.setItem(slot, null);
            }
        }
    }

    void clearMarker(Inventory inv, String marker) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (marker.equals(marker(item))) {
                clearMarker(item);
                inv.setItem(slot, item);
            }
        }
    }

    void clearMarker(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(delivKey);
        item.setItemMeta(meta);
    }

    boolean hasMarker(Inventory inv, String marker) {
        for (ItemStack item : inv.getContents()) {
            if (marker.equals(marker(item))) {
                return true;
            }
        }
        return false;
    }

    boolean isPendingSource(ItemStack item, ItemStack expected) {
        String val = marker(item);
        return expected != null
                && (!isDeliveryItem(item) || (val != null && val.startsWith(TradeMarkers.PENDING_PREFIX)))
                && !TradeItem.isEmpty(item)
                && pendingSimilar(item, expected, val)
                && item.getAmount() == expected.getAmount();
    }

    void confirmDelivery(Player p, String txId, Runnable completed) {
        String marker = TradeMarkers.transaction(txId);
        txRepo.delivered(txId, p.getUniqueId()).thenAccept(ok -> {
            if (!ok) {
                return;
            }
            exec.entity(p, () -> {
                clearMarker(p.getInventory(), marker);
                if (completed != null) {
                    completed.run();
                }
            }, () -> { });
        });
    }

    private void confirmRecovery(Player p, long recId) {
        String marker = TradeMarkers.recovery(recId);
        txRepo.recoveryDelivered(recId, p.getUniqueId()).thenAccept(ok -> {
            if (ok) {
                exec.entity(p, () -> clearMarker(p.getInventory(), marker), () -> { });
            }
        });
    }

    private void recoverOnRegion(TxRepo.PreparedTx tx, Location loc, CompletableFuture<Void> recovered) {
        if (loc.getBlock().getType() != Material.CHEST) {
            finishRecovery(tx, true, recovered);
            return;
        }
        Inventory inv = ((Chest) loc.getBlock().getState()).getInventory();
        ItemStack product = ItemStack.deserializeBytes(tx.itemSnapshot());
        ItemStack cur = inv.getItem(tx.itemSlot());
        boolean recoverItem = cur != null && (!cur.isSimilar(product) || cur.getAmount() != product.getAmount());
        if (cur == null) {
            inv.setItem(tx.itemSlot(), product);
        }
        finishRecovery(tx, recoverItem, recovered);
    }

    private void finishRecovery(TxRepo.PreparedTx tx, boolean recoverItem, CompletableFuture<Void> recovered) {
        txRepo.recoverRollback(tx, recoverItem).whenComplete((applied, err) -> {
            if (err != null) {
                recovered.completeExceptionally(err);
                return;
            }
            if (applied) {
                cleanupTxMarker(tx.buyerId(), TradeMarkers.transaction(tx.txId()));
                exec.global(() -> Bukkit.getPluginManager().callEvent(
                        new ShopTransactionRolledBackEvent(UUID.fromString(tx.txId()), tx.shopId(), tx.buyerId())));
            }
            recovered.complete(null);
        });
    }

    private void cleanupTxMarker(UUID buyerId, String marker) {
        exec.global(() -> {
            Player buyer = Bukkit.getPlayer(buyerId);
            if (buyer != null) {
                exec.entity(buyer, () -> removeMarker(buyer.getInventory(), marker));
            }
        });
    }

    private void clearStaleMarkers(Inventory inv, String prefix, Set<String> active) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            String marker = marker(item);
            if (marker != null && marker.startsWith(prefix) && !active.contains(marker)) {
                clearMarker(item);
                inv.setItem(slot, item);
            }
        }
    }

    private void removeMarkers(Inventory inv, String prefix) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            String marker = marker(inv.getItem(slot));
            if (marker != null && marker.startsWith(prefix)) {
                inv.setItem(slot, null);
            }
        }
    }

    private List<TradeSess> sessionsFor(ItemStack item) {
        return store.playerIds().stream()
                .map(store::get)
                .filter(s -> s != null && s.purchasePending && isPendingSource(item, s.pendingProduct))
                .toList();
    }

    private boolean pendingSimilar(ItemStack item, ItemStack expected, String val) {
        if (val == null) {
            return item.isSimilar(expected);
        }
        ItemStack plain = item.clone();
        clearMarker(plain);
        return plain.isSimilar(expected);
    }
}
