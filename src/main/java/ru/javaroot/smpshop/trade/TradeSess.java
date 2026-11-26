package ru.javaroot.smpshop.trade;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.smpshop.gui.MenuHolder;
import ru.javaroot.smpshop.shop.Shop;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TradeSess extends MenuHolder {
    final Shop shop;
    final Inventory physical;
    final Map<Integer, ItemStack> snapshot = new HashMap<>();
    final Set<Integer> paymentSlots = new HashSet<>();
    final Location loc;
    volatile boolean purchasePending;
    ItemStack pendingProduct;
    int pendingSlot = -1;
    TradePaySvc.Payment payment;
    List<PurchaseAdjustment> adjustments = List.of();
    boolean prepared;
    boolean productPlaced;
    int destinationSlot = -1;
    volatile boolean escrowed;
    UUID txId;

    TradeSess(Shop shop, Inventory physical, Component title, Location loc) {
        this.shop = shop;
        this.physical = physical;
        this.loc = loc;
        Inventory inv = Bukkit.createInventory(this, physical.getSize(), title);
        bind(inv);
        for (int slot = 0; slot < physical.getSize(); slot++) {
            ItemStack item = physical.getItem(slot);
            if (item == null || item.getType().isAir()) {
                paymentSlots.add(slot);
            } else {
                snapshot.put(slot, item.clone());
                inv.setItem(slot, item.clone());
            }
        }
    }
}
