package ru.javaroot.list;

import org.bukkit.Bukkit;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.javaroot.config.Msg;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.data.ShopStore;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.integration.SaleLog;
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

public class ShopTrade implements Listener {
    private final ShopMgr shops;
    private final ShopStore shopStore;
    private final BalanceStore balances;
    private final IntegrationHub integrations;
    private final NotifySvc notifications;
    private final SoundSvc sounds;
    private final Msg msg;
    private final Sched sched;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();

    public ShopTrade(ShopMgr shops, ShopStore shopStore, BalanceStore balances, IntegrationHub integrations,
                     NotifySvc notifications, SoundSvc sounds, Msg msg, Sched sched) {
        this.shops = shops;
        this.shopStore = shopStore;
        this.balances = balances;
        this.integrations = integrations;
        this.notifications = notifications;
        this.sounds = sounds;
        this.msg = msg;
        this.sched = sched;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
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
        Session session = new Session(shop, event.getInventory());
        if (lockedShops.add(shop.id())) {
            sessions.put(player.getUniqueId(), session);
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
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= session.inventory.getSize()) {
            return;
        }
        if (isPaymentClick(event, session)) {
            event.setCancelled(false);
            return;
        }
        if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT) {
            buy(player, session, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null && event.getView().getTopInventory() == session.inventory) {
                event.setCancelled(true);
            }
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

    public void closeAll() {
        for (UUID playerId : Set.copyOf(sessions.keySet())) {
            close(playerId, Bukkit.getPlayer(playerId));
        }
    }

    private void buy(Player player, Session session, int slot) {
        Shop shop = shops.find(session.shop.id());
        ItemStack product = session.inventory.getItem(slot);
        ItemStack expected = session.snapshot.get(slot);
        if (shop == null || product == null || expected == null || !product.isSimilar(expected)
                || product.getAmount() != expected.getAmount()) {
            return;
        }
        if (!canFit(player.getInventory(), product)) {
            msg.send(player, "purchase-full-inventory");
            return;
        }
        long paid = paymentTotal(session);
        if (paid < shop.price()) {
            msg.send(player, "purchase-insufficient");
            return;
        }
        EnumMap<Currency, Long> payment = takePayment(session, shop.price());
        session.inventory.setItem(slot, null);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(product.clone());
        if (!overflow.isEmpty()) {
            session.inventory.setItem(slot, product);
            restorePayment(session, payment);
            return;
        }
        int items = product.getAmount();
        long amount = shop.price();
        shops.recordSale(shop.id(), amount, items);
        shopStore.recordSale(shop.id(), amount, items);
        for (Map.Entry<Currency, Long> entry : payment.entrySet()) {
            balances.deposit(shop.ownerId(), entry.getKey(), entry.getValue());
        }
        SaleLog sale = new SaleLog(shop.ownerId(), shop.id(), "#" + shop.id(), product.getType().name(), items, amount);
        integrations.sale(sale);
        sounds.buyerPurchase(player);
        msg.send(player, "purchase-success", Map.of("item", product.getType().name(), "items", items, "amount", amount));
        Player seller = Bukkit.getPlayer(shop.ownerId());
        notifications.sale(sale, seller);
    }

    private boolean isPaymentClick(InventoryClickEvent event, Session session) {
        int slot = event.getRawSlot();
        if (!session.paymentSlots.contains(slot) || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)
                || event.getClick() == ClickType.SHIFT_LEFT
                || event.getClick() == ClickType.SHIFT_RIGHT || event.getClick().isKeyboardClick()
                || event.getClick() == ClickType.DOUBLE_CLICK || event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP || event.getCursor() == null
                || event.getCursor().getType().isAir()) {
            return false;
        }
        Currency currency = Currency.from(event.getCursor().getType());
        if (currency == null) {
            return false;
        }
        ItemStack current = event.getCurrentItem();
        return current == null || current.getType().isAir() || current.getType() == currency.material();
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

    private EnumMap<Currency, Long> takePayment(Session session, long price) {
        EnumMap<Currency, Long> result = new EnumMap<>(Currency.class);
        long remaining = price;
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            if (item == null) {
                continue;
            }
            Currency currency = Currency.from(item.getType());
            if (currency == null) {
                continue;
            }
            int used = (int) Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - used);
            if (item.getAmount() == 0) {
                session.inventory.setItem(slot, null);
            }
            result.merge(currency, (long) used, Long::sum);
            remaining -= used;
            if (remaining == 0) {
                break;
            }
        }
        return result;
    }

    private void restorePayment(Session session, Map<Currency, Long> payment) {
        for (Map.Entry<Currency, Long> entry : payment.entrySet()) {
            int amount = Math.toIntExact(entry.getValue());
            for (int slot : session.paymentSlots) {
                ItemStack item = session.inventory.getItem(slot);
                if (item == null) {
                    session.inventory.setItem(slot, new ItemStack(entry.getKey().material(), Math.min(64, amount)));
                    amount -= Math.min(64, amount);
                } else if (item.getType() == entry.getKey().material() && item.getAmount() < 64) {
                    int add = Math.min(64 - item.getAmount(), amount);
                    item.setAmount(item.getAmount() + add);
                    amount -= add;
                }
                if (amount == 0) {
                    break;
                }
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

    private void close(UUID playerId, Player player) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        lockedShops.remove(session.shop.id());
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
        if (player == null || !player.isOnline()) {
            returned.forEach((currency, amount) -> balances.deposit(playerId, currency, amount));
            return;
        }
        sched.entity(player, () -> {
            returned.forEach((currency, amount) -> {
                long left = amount;
                while (left > 0) {
                    int stack = (int) Math.min(64, left);
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(currency.material(), stack));
                    long extra = overflow.values().stream().mapToLong(ItemStack::getAmount).sum();
                    if (extra > 0) {
                        player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(currency.material(), (int) extra));
                    }
                    left -= stack;
                }
            });
        });
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
        private final Inventory inventory;
        private final Map<Integer, ItemStack> snapshot = new HashMap<>();
        private final Set<Integer> paymentSlots = new HashSet<>();
        private Session(Shop shop, Inventory inventory) {
            this.shop = shop;
            this.inventory = inventory;
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().isAir()) {
                    paymentSlots.add(slot);
                } else {
                    snapshot.put(slot, item.clone());
                }
            }
        }
    }
}
