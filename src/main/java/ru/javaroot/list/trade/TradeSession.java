package ru.javaroot.list.trade;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.javaroot.api.event.PurchaseAdjustment;
import ru.javaroot.shop.Shop;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class TradeSession {
    final Shop shop;
    final Inventory physical;
    final Inventory inventory;
    final Map<Integer, ItemStack> snapshot = new HashMap<>();
    final Set<Integer> paymentSlots = new HashSet<>();
    final Location location;
    volatile boolean purchasePending;
    ItemStack pendingProduct;
    int pendingSlot = -1;
    TradePaymentSvc.Payment payment;
    List<PurchaseAdjustment> adjustments = List.of();
    boolean prepared;
    boolean productPlaced;
    int destinationSlot = -1;
    volatile boolean escrowed;
    UUID transactionId;

    TradeSession(Shop shop, Inventory physical, Component title, Location location) {
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
