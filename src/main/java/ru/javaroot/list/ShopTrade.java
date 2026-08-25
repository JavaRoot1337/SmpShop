package ru.javaroot.list;

import org.bukkit.Bukkit;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
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
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Long> lockedShops = ConcurrentHashMap.newKeySet();

    public ShopTrade(ShopMgr shops, ShopStore shopStore, BalanceStore balances, IntegrationHub integrations,
                     NotifySvc notifications, SoundSvc sounds, Msg msg, ItemName itemNames, Sched sched) {
        this.shops = shops;
        this.shopStore = shopStore;
        this.balances = balances;
        this.integrations = integrations;
        this.notifications = notifications;
        this.sounds = sounds;
        this.msg = msg;
        this.itemNames = itemNames;
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
        if (event.getRawSlot() < 0) {
            return;
        }
        if (session.pendingProductActive) {
            if (event.getRawSlot() >= session.inventory.getSize()) {
                placePendingProduct(event, player, session);
            } else {
                returnPendingProduct(event, session);
            }
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
            Session session = sessions.get(player.getUniqueId());
            if (session != null && event.getView().getTopInventory() == session.inventory) {
                event.setCancelled(true);
                if (session.pendingProductActive) {
                    if (isPendingProductDrag(event, session)) {
                        placePendingProduct(event, player, session);
                    } else {
                        returnPendingProduct(event, session);
                    }
                    return;
                }
                if (isPaymentDrag(event, session)) {
                    event.setCancelled(false);
                }
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
        event.getPlayer().getOpenInventory().setCursor(null);
        close(event.getPlayer().getUniqueId(), null);
    }

    public void closeAll() {
        for (UUID playerId : Set.copyOf(sessions.keySet())) {
            close(playerId, Bukkit.getPlayer(playerId));
        }
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
        session.pendingSlot = slot;
        session.pendingProduct = product.clone();
        session.pendingProductActive = true;
        session.inventory.setItem(slot, null);
        event.getView().setCursor(session.pendingProduct.clone());
    }

    private void completePurchase(Player player, Session session, Shop shop, ItemStack product) {
        EnumMap<Currency, Long> payment = takePayment(session, shop.price());
        EnumMap<Currency, Long> excess = takeRemainingPayment(session);
        givePayment(player, excess);
        session.clearPendingProduct();
        int items = product.getAmount();
        long amount = shop.price();
        shops.recordSale(shop.id(), amount, items);
        shopStore.recordSale(shop.id(), amount, items);
        for (Map.Entry<Currency, Long> entry : payment.entrySet()) {
            balances.deposit(shop.ownerId(), entry.getKey(), entry.getValue());
        }
        SaleLog sale = new SaleLog(shop.ownerId(), shop.id(), "#" + shop.id(), product.getType(), items, amount);
        integrations.sale(sale);
        sounds.buyerPurchase(player);
        msg.send(player, "purchase-success", Map.of("items", items, "amount", amount),
                Map.of("item", itemNames.component(product.getType())));
        Player seller = Bukkit.getPlayer(shop.ownerId());
        notifications.sale(sale, seller);
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

    private void placePendingProduct(InventoryClickEvent event, Player player, Session session) {
        if (!isPendingProductClick(event, session)) {
            return;
        }
        Shop shop = shops.find(session.shop.id());
        if (shop == null || paymentTotal(session) < shop.price()) {
            return;
        }
        int slot = event.getSlot();
        ItemStack product = session.pendingProduct.clone();
        ItemStack target = event.getCurrentItem();
        Inventory bottom = event.getView().getBottomInventory();
        if (isEmpty(target)) {
            bottom.setItem(slot, product);
        } else {
            target.setAmount(target.getAmount() + product.getAmount());
        }
        event.getView().setCursor(null);
        completePurchase(player, session, shop, product);
    }

    private void placePendingProduct(InventoryDragEvent event, Player player, Session session) {
        if (!isPendingProductDrag(event, session)) {
            return;
        }
        Shop shop = shops.find(session.shop.id());
        if (shop == null || paymentTotal(session) < shop.price()) {
            return;
        }
        int rawSlot = event.getRawSlots().iterator().next();
        int slot = rawSlot - session.inventory.getSize();
        ItemStack product = session.pendingProduct.clone();
        ItemStack target = event.getView().getBottomInventory().getItem(slot);
        if (isEmpty(target)) {
            event.getView().getBottomInventory().setItem(slot, product);
        } else {
            target.setAmount(target.getAmount() + product.getAmount());
        }
        event.setCursor(null);
        completePurchase(player, session, shop, product);
    }

    private boolean isPendingProductClick(InventoryClickEvent event, Session session) {
        return event.getClick() == ClickType.LEFT
                && event.getClickedInventory() == event.getView().getBottomInventory()
                && isPendingCursor(event.getCursor(), session)
                && canPlaceProduct(event.getCurrentItem(), session.pendingProduct);
    }

    private boolean isPendingProductDrag(InventoryDragEvent event, Session session) {
        if (event.getType() != DragType.EVEN
                || event.getRawSlots().size() != 1
                || !isPendingCursor(event.getOldCursor(), session)) {
            return false;
        }
        int rawSlot = event.getRawSlots().iterator().next();
        if (rawSlot < session.inventory.getSize()) {
            return false;
        }
        ItemStack target = event.getView().getBottomInventory().getItem(rawSlot - session.inventory.getSize());
        return canPlaceProduct(target, session.pendingProduct);
    }

    private boolean isPendingCursor(ItemStack cursor, Session session) {
        return session.pendingProductActive && !isEmpty(cursor)
                && cursor.isSimilar(session.pendingProduct)
                && cursor.getAmount() == session.pendingProduct.getAmount();
    }

    private boolean canPlaceProduct(ItemStack target, ItemStack product) {
        return isEmpty(target)
                || (target.isSimilar(product)
                && target.getAmount() + product.getAmount() <= target.getMaxStackSize());
    }

    private void returnPendingProduct(InventoryClickEvent event, Session session) {
        if (!isPendingProductReturn(event, session)) {
            return;
        }
        session.inventory.setItem(session.pendingSlot, session.pendingProduct.clone());
        event.getView().setCursor(null);
        session.clearPendingProduct();
    }

    private boolean isPendingProductReturn(InventoryClickEvent event, Session session) {
        return event.getRawSlot() == session.pendingSlot
                && event.getClick() == ClickType.LEFT
                && isEmpty(event.getCurrentItem())
                && isPendingCursor(event.getCursor(), session);
    }

    private void returnPendingProduct(InventoryDragEvent event, Session session) {
        if (event.getType() != DragType.EVEN
                || event.getRawSlots().size() != 1
                || !isPendingCursor(event.getOldCursor(), session)
                || !event.getRawSlots().contains(session.pendingSlot)) {
            return;
        }
        int slot = session.pendingSlot;
        if (!isEmpty(session.inventory.getItem(slot))) {
            return;
        }
        session.inventory.setItem(slot, session.pendingProduct.clone());
        event.setCursor(null);
        session.clearPendingProduct();
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

    private EnumMap<Currency, Long> takeRemainingPayment(Session session) {
        EnumMap<Currency, Long> result = new EnumMap<>(Currency.class);
        for (int slot : session.paymentSlots) {
            ItemStack item = session.inventory.getItem(slot);
            if (isEmpty(item)) {
                continue;
            }
            Currency currency = Currency.from(item.getType());
            if (currency != null) {
                result.merge(currency, (long) item.getAmount(), Long::sum);
                session.inventory.setItem(slot, null);
            }
        }
        return result;
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

    private void close(UUID playerId, Player player) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        lockedShops.remove(session.shop.id());
        if (session.pendingProductActive) {
            session.inventory.setItem(session.pendingSlot, session.pendingProduct.clone());
            if (player != null) {
                player.getOpenInventory().setCursor(null);
            }
            session.clearPendingProduct();
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
        if (player == null || !player.isOnline()) {
            returned.forEach((currency, amount) -> balances.deposit(playerId, currency, amount));
            return;
        }
        sched.entity(player, () -> {
            givePayment(player, returned);
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
        private int pendingSlot = -1;
        private ItemStack pendingProduct;
        private boolean pendingProductActive;
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

        private void clearPendingProduct() {
            pendingSlot = -1;
            pendingProduct = null;
            pendingProductActive = false;
        }
    }
}
