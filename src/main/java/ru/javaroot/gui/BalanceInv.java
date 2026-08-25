package ru.javaroot.gui;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.config.Msg;
import ru.javaroot.currency.Balance;
import ru.javaroot.currency.Currency;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.scheduler.Sched;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public class BalanceInv implements Listener {
    private static final int GUI_SIZE = 54;
    private static final int BALANCE_SLOTS = 45;
    private static final int MAX_STACK_SIZE = 64;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;

    private final JavaPlugin plugin;
    private final BalanceStore balances;
    private final Sched sched;
    private final Msg msg;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public BalanceInv(JavaPlugin plugin, BalanceStore balances, Sched sched, Msg msg) {
        this.plugin = plugin;
        this.balances = balances;
        this.sched = sched;
        this.msg = msg;
    }

    public void open(Player player) {
        UUID playerId = player.getUniqueId();
        balances.load(playerId).thenAccept(balance -> sched.entity(player, () -> {
            if (!player.isOnline() || sessions.containsKey(playerId)) {
                return;
            }
            Session session = new Session(playerId, balance, itemPages(balance));
            session.inventory = Bukkit.createInventory(null, GUI_SIZE, msg.component("balance-title"));
            sessions.put(playerId, session);
            showPage(session);
            player.openInventory(session.inventory);
        })).exceptionally(error -> {
            logFailure("Не удалось загрузить баланс", error);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = session(player, event.getView().getTopInventory());
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot == PREVIOUS_SLOT) {
            if (navigationClick(event)) {
                previous(session);
            }
            return;
        }
        if (rawSlot == NEXT_SLOT) {
            if (navigationClick(event)) {
                next(session);
            }
            return;
        }
        if (rawSlot >= 0 && rawSlot < GUI_SIZE && rawSlot >= BALANCE_SLOTS) {
            return;
        }
        if (allowedClick(event)) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || session(player, event.getView().getTopInventory()) == null) {
            return;
        }

        event.setCancelled(true);
        if (!isCurrency(event.getOldCursor())) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < GUI_SIZE && rawSlot >= BALANCE_SLOTS) {
                return;
            }
        }
        event.setCancelled(false);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = session(player, event.getView().getTopInventory());
        if (session != null && sessions.remove(player.getUniqueId(), session)) {
            capture(session);
            close(player, session);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Session session = sessions.remove(playerId);
        if (session != null) {
            capture(session);
            close(event.getPlayer(), session);
        }
    }

    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> closings = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            UUID playerId = entry.getKey();
            Session session = entry.getValue();
            if (!sessions.remove(playerId, session)) {
                continue;
            }

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                closings.add(settle(session, null));
                continue;
            }

            CompletableFuture<Void> closing = new CompletableFuture<>();
            closings.add(closing);
            sched.entity(player, () -> {
                capture(session);
                if (player.getOpenInventory().getTopInventory() == session.inventory) {
                    player.closeInventory();
                }
                settle(session, player).whenComplete((ignored, error) -> complete(closing, error));
            }, () -> settle(session, null).whenComplete((ignored, error) -> complete(closing, error)));
        }
        return CompletableFuture.allOf(closings.toArray(CompletableFuture[]::new));
    }

    static List<OreStack[]> pack(Balance balance) {
        List<OreStack[]> pages = new ArrayList<>();
        OreStack[] page = new OreStack[BALANCE_SLOTS];
        int slot = 0;
        for (Currency currency : Currency.values()) {
            long remaining = amount(balance, currency);
            while (remaining > 0) {
                if (slot == BALANCE_SLOTS) {
                    pages.add(page);
                    page = new OreStack[BALANCE_SLOTS];
                    slot = 0;
                }
                int stackAmount = (int) Math.min(MAX_STACK_SIZE, remaining);
                page[slot++] = new OreStack(currency, stackAmount);
                remaining -= stackAmount;
            }
        }
        pages.add(page);
        return pages;
    }

    private static List<ItemStack[]> itemPages(Balance balance) {
        List<ItemStack[]> pages = new ArrayList<>();
        for (OreStack[] page : pack(balance)) {
            ItemStack[] items = new ItemStack[BALANCE_SLOTS];
            for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
                OreStack stack = page[slot];
                if (stack != null) {
                    items[slot] = new ItemStack(stack.currency().material(), stack.amount());
                }
            }
            pages.add(items);
        }
        return pages;
    }

    private void previous(Session session) {
        capture(session);
        if (session.page == 0) {
            return;
        }
        session.page--;
        showPage(session);
    }

    private void next(Session session) {
        capture(session);
        if (session.page + 1 < session.pages.size()) {
            session.page++;
            showPage(session);
            return;
        }
        if (!full(session.pages.get(session.page))) {
            return;
        }
        session.pages.add(new ItemStack[BALANCE_SLOTS]);
        session.page++;
        showPage(session);
    }

    private void showPage(Session session) {
        ItemStack[] contents = new ItemStack[GUI_SIZE];
        ItemStack[] page = session.pages.get(session.page);
        for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
            contents[slot] = copy(page[slot]);
        }
        contents[PREVIOUS_SLOT] = navigation("balance-previous");
        contents[NEXT_SLOT] = navigation("balance-next");
        session.inventory.setContents(contents);
    }

    private void capture(Session session) {
        ItemStack[] page = session.pages.get(session.page);
        for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
            page[slot] = copy(session.inventory.getItem(slot));
        }
    }

    private void close(Player player, Session session) {
        sched.entity(player, () -> {
            settle(session, player).whenComplete((ignored, error) -> logIfFailed("Не удалось сохранить баланс", error));
        }, () -> settle(session, null).whenComplete((ignored, error) -> logIfFailed("Не удалось сохранить баланс", error)));
    }

    private CompletableFuture<Void> settle(Session session, Player player) {
        EnumMap<Currency, Long> current = count(session.pages);
        List<ItemStack> invalid = invalidItems(session.pages);
        returnItems(player, invalid);

        List<CompletableFuture<Void>> operations = new ArrayList<>();
        for (Currency currency : Currency.values()) {
            long difference = current.getOrDefault(currency, 0L) - amount(session.original, currency);
            if (difference > 0) {
                operations.add(balances.deposit(session.playerId, currency, difference));
            } else if (difference < 0) {
                operations.add(takeOrRestore(session.playerId, currency, -difference));
            }
        }
        return CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> takeOrRestore(UUID playerId, Currency currency, long amount) {
        return balances.take(playerId, currency, amount)
                .thenCompose(taken -> taken ? CompletableFuture.completedFuture(null) : balances.deposit(playerId, currency, amount));
    }

    private void returnItems(Player player, List<ItemStack> items) {
        if (player == null || !player.isOnline()) {
            return;
        }
        for (ItemStack item : items) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            overflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private ItemStack navigation(String key) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(msg.component(key).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private Session session(Player player, Inventory inventory) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.inventory == inventory ? session : null;
    }

    private boolean allowedClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return false;
        }
        return allowedItem(event.getCurrentItem()) && allowedItem(event.getCursor());
    }

    private boolean navigationClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        return (click == ClickType.LEFT || click == ClickType.RIGHT) && allowedItem(event.getCursor());
    }

    private boolean allowedItem(ItemStack item) {
        return item == null || item.getType().isAir() || isCurrency(item);
    }

    private boolean isCurrency(ItemStack item) {
        return item != null && Currency.from(item.getType()) != null;
    }

    private EnumMap<Currency, Long> count(List<ItemStack[]> pages) {
        EnumMap<Currency, Long> amounts = new EnumMap<>(Currency.class);
        for (ItemStack[] page : pages) {
            for (ItemStack item : page) {
                Currency currency = item == null ? null : Currency.from(item.getType());
                if (currency != null) {
                    amounts.merge(currency, (long) item.getAmount(), Long::sum);
                }
            }
        }
        return amounts;
    }

    private List<ItemStack> invalidItems(List<ItemStack[]> pages) {
        List<ItemStack> invalid = new ArrayList<>();
        for (ItemStack[] page : pages) {
            for (ItemStack item : page) {
                if (item != null && !item.getType().isAir() && !isCurrency(item)) {
                    invalid.add(item.clone());
                }
            }
        }
        return invalid;
    }

    private static boolean full(ItemStack[] page) {
        for (ItemStack item : page) {
            if (item == null || item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static long amount(Balance balance, Currency currency) {
        return currency == Currency.DIAMOND_ORE ? balance.diamondOre() : balance.deepslateDiamondOre();
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private void complete(CompletableFuture<Void> future, Throwable error) {
        if (error == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(error);
        }
    }

    private void logIfFailed(String message, Throwable error) {
        if (error != null) {
            logFailure(message, error);
        }
    }

    private void logFailure(String message, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        plugin.getLogger().warning(message + ": " + cause.getMessage());
    }

    private static class Session {
        private final UUID playerId;
        private final Balance original;
        private final List<ItemStack[]> pages;
        private int page;
        private Inventory inventory;

        private Session(UUID playerId, Balance original, List<ItemStack[]> pages) {
            this.playerId = playerId;
            this.original = original;
            this.pages = pages;
        }
    }

    record OreStack(Currency currency, int amount) {
    }
}
