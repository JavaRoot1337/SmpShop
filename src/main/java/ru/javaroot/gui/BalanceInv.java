package ru.javaroot.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.config.Msg;
import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.scheduler.Sched;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BalanceInv implements Listener {
    private final BalanceStore balances;
    private final Sched sched;
    private final Msg msg;
    private final NamespacedKey itemKey;
    private final NamespacedKey typeKey;
    private final Set<UUID> opened = ConcurrentHashMap.newKeySet();
    private final Set<UUID> withdrawing = ConcurrentHashMap.newKeySet();

    public BalanceInv(JavaPlugin plugin, BalanceStore balances, Sched sched, Msg msg) {
        this.balances = balances;
        this.sched = sched;
        this.msg = msg;
        this.itemKey = new NamespacedKey(plugin, "balance-gui");
        this.typeKey = new NamespacedKey(plugin, "balance-type");
    }

    public void open(Player player) {
        balances.load(player.getUniqueId()).thenAccept(balance -> sched.entity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            opened.add(player.getUniqueId());
            player.openInventory(create(balance));
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !opened.contains(player.getUniqueId())) {
            return;
        }
        Inventory menu = event.getView().getTopInventory();
        if (event.getRawSlot() >= menu.getSize()) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                    || event.getClick().isKeyboardClick()
                    || event.getClick() == ClickType.SWAP_OFFHAND
                    || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!"balance".equals(meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING))) {
            if ("close".equals(meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING))) {
                player.closeInventory();
            }
            return;
        }
        String type = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (type == null) {
            return;
        }
        Currency currency = Currency.valueOf(type);
        int amount = withdrawAmount(event.getClick(), item.getAmount());
        if (amount < 1 || !withdrawing.add(player.getUniqueId())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        balances.take(playerId, currency, amount)
                .thenAccept(taken -> sched.entity(player,
                        () -> finishWithdraw(player, playerId, menu, currency, amount, taken),
                        () -> refund(playerId, currency, amount, taken)))
                .exceptionally(error -> {
                    withdrawing.remove(playerId);
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && opened.contains(player.getUniqueId())
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            opened.remove(player.getUniqueId());
        }
    }

    private Inventory create(Balance balance) {
        Inventory inventory = Bukkit.createInventory(null, 27, msg.component("balance-title"));
        setCurrency(inventory, 11, Currency.DIAMOND_ORE, balance.diamondOre());
        setCurrency(inventory, 15, Currency.DEEPSLATE_DIAMOND_ORE, balance.deepslateDiamondOre());
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(msg.component("balance-close"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "close");
        close.setItemMeta(meta);
        inventory.setItem(22, close);
        return inventory;
    }

    private void setCurrency(Inventory inventory, int slot, Currency currency, long amount) {
        if (amount < 1) {
            inventory.setItem(slot, null);
            return;
        }
        ItemStack item = new ItemStack(currency.material(), (int) Math.min(64, amount));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "balance");
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, currency.name());
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private int withdrawAmount(ClickType click, int visibleAmount) {
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return 0;
        }
        return click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT
                ? Math.max(1, (visibleAmount + 1) / 2)
                : visibleAmount;
    }

    private void finishWithdraw(Player player, UUID playerId, Inventory menu, Currency currency,
                                int amount, boolean taken) {
        withdrawing.remove(playerId);
        if (!taken) {
            return;
        }
        if (!player.isOnline() || !isOpen(player, menu)) {
            refund(playerId, currency, amount, true);
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(currency.material(), amount));
        long returned = overflow.values().stream().mapToLong(ItemStack::getAmount).sum();
        if (returned > 0) {
            balances.deposit(playerId, currency, returned);
        }
        refresh(player, menu);
    }

    private void refund(UUID playerId, Currency currency, int amount, boolean taken) {
        withdrawing.remove(playerId);
        if (taken) {
            balances.deposit(playerId, currency, amount);
        }
    }

    private void refresh(Player player, Inventory menu) {
        balances.load(player.getUniqueId()).thenAccept(balance -> sched.entity(player, () -> {
            if (player.isOnline() && isOpen(player, menu)) {
                Inventory updated = create(balance);
                menu.setContents(updated.getContents());
            }
        }));
    }

    private boolean isOpen(Player player, Inventory menu) {
        return opened.contains(player.getUniqueId()) && player.getOpenInventory().getTopInventory() == menu;
    }
}
