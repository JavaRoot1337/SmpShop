package ru.javaroot.smpshop.trade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

final class TradeItem {
    private TradeItem() {
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    static boolean canFit(Inventory inv, ItemStack item) {
        int rem = item.getAmount();
        for (ItemStack cur : inv.getStorageContents()) {
            if (isEmpty(cur)) {
                rem -= item.getMaxStackSize();
            } else if (cur.isSimilar(item)) {
                rem -= Math.max(0, cur.getMaxStackSize() - cur.getAmount());
            }
            if (rem <= 0) {
                return true;
            }
        }
        return false;
    }
}
