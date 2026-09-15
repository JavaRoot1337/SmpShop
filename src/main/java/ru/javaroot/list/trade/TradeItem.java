package ru.javaroot.list.trade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

class TradeItem {
    private TradeItem() {
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    static boolean canFit(Inventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack current : inventory.getStorageContents()) {
            if (isEmpty(current)) {
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
}
