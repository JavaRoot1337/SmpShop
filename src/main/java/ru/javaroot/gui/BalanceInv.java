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
import java.util.Set;

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
    private final Set<UUID> closing = ConcurrentHashMap.newKeySet();

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
            Session session = new Session(playerId, balance);
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
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            moveShift(player, session, event);
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
            closing.add(session.playerId);
            capture(session);
            close(player, session);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Session session = sessions.remove(playerId);
        if (session != null) {
            closing.add(session.playerId);
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
            closing.add(playerId);

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                closings.add(settleAndUnlock(session, null));
                continue;
            }

            CompletableFuture<Void> closing = new CompletableFuture<>();
            closings.add(closing);
            sched.entity(player, () -> {
                capture(session);
                if (player.getOpenInventory().getTopInventory() == session.inventory) {
                    player.closeInventory();
                }
                settleAndUnlock(session, player).whenComplete((ignored, error) -> complete(closing, error));
            }, () -> settleAndUnlock(session, null).whenComplete((ignored, error) -> complete(closing, error)));
        }
        return CompletableFuture.allOf(closings.toArray(CompletableFuture[]::new));
    }

    public boolean isOpen(UUID playerId) {
        return sessions.containsKey(playerId) || closing.contains(playerId);
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
        if (session.page < session.lastOriginalPage) {
            session.page++;
            showPage(session);
            return;
        }
        if (!full(session.pages.get(session.page))) {
            return;
        }
        session.page++;
        showPage(session);
    }

    private void showPage(Session session) {
        ItemStack[] contents = new ItemStack[GUI_SIZE];
        ItemStack[] page = session.pages.computeIfAbsent(session.page,
                pageNumber -> itemPage(session.original, pageNumber));
        for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
            contents[slot] = copy(page[slot]);
        }
        contents[PREVIOUS_SLOT] = navigation("balance-previous");
        contents[NEXT_SLOT] = navigation("balance-next");
        session.inventory.setContents(contents);
    }

    private void capture(Session session) {
        ItemStack[] page = session.pages.computeIfAbsent(session.page,
                pageNumber -> itemPage(session.original, pageNumber));
        for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
            page[slot] = copy(session.inventory.getItem(slot));
        }
    }

    private void close(Player player, Session session) {
        sched.entity(player, () -> {
            settleAndUnlock(session, player).whenComplete((ignored, error) -> logIfFailed("Не удалось сохранить баланс", error));
        }, () -> settleAndUnlock(session, null).whenComplete((ignored, error) -> logIfFailed("Не удалось сохранить баланс", error)));
    }

    private CompletableFuture<Void> settleAndUnlock(Session session, Player player) {
        return settle(session, player).whenComplete((ignored, error) -> closing.remove(session.playerId));
    }

    private CompletableFuture<Void> settle(Session session, Player player) {
        EnumMap<Currency, Long> current = changedBalance(session);
        List<ItemStack> invalid = invalidItems(session.pages.values());
        returnItems(player, invalid);

        long diamondDelta = current.getOrDefault(Currency.DIAMOND_ORE, 0L) - session.original.diamondOre();
        long deepslateDelta = current.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L)
                - session.original.deepslateDiamondOre();
        return balances.settle(session.operationId, session.playerId, diamondDelta, deepslateDelta)
                .thenCompose(result -> result.status() == BalanceStore.MutationStatus.SUCCESS
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException("Balance changed during GUI session")));
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
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return false;
        }
        return allowedItem(event.getCurrentItem()) && allowedItem(event.getCursor());
    }

    private boolean navigationClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        return (click == ClickType.LEFT || click == ClickType.RIGHT) && allowedItem(event.getCursor());
    }

    private void moveShift(Player player, Session session, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (!isCurrency(item)) {
            return;
        }
        if (event.getRawSlot() < GUI_SIZE) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
            session.inventory.setItem(event.getRawSlot(), overflow.values().stream().findFirst().orElse(null));
            return;
        }
        int remaining = item.getAmount();
        for (int slot = 0; slot < BALANCE_SLOTS && remaining > 0; slot++) {
            ItemStack target = session.inventory.getItem(slot);
            if (target != null && target.isSimilar(item)) {
                int moved = Math.min(remaining, target.getMaxStackSize() - target.getAmount());
                target.setAmount(target.getAmount() + moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < BALANCE_SLOTS && remaining > 0; slot++) {
            if (session.inventory.getItem(slot) != null) {
                continue;
            }
            int moved = Math.min(remaining, item.getMaxStackSize());
            ItemStack placed = item.clone();
            placed.setAmount(moved);
            session.inventory.setItem(slot, placed);
            remaining -= moved;
        }
        if (remaining == 0) {
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            item.setAmount(remaining);
            event.getClickedInventory().setItem(event.getSlot(), item);
        }
    }

    private boolean allowedItem(ItemStack item) {
        return item == null || item.getType().isAir() || isCurrency(item);
    }

    private boolean isCurrency(ItemStack item) {
        return item != null && Currency.from(item.getType()) != null;
    }

    private EnumMap<Currency, Long> count(Iterable<ItemStack[]> pages) {
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

    private List<ItemStack> invalidItems(Iterable<ItemStack[]> pages) {
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

    private EnumMap<Currency, Long> changedBalance(Session session) {
        EnumMap<Currency, Long> current = new EnumMap<>(Currency.class);
        for (Currency currency : Currency.values()) {
            current.put(currency, amount(session.original, currency));
        }
        for (Map.Entry<Long, ItemStack[]> entry : session.pages.entrySet()) {
            ItemStack[] originalPage = itemPage(session.original, entry.getKey());
            EnumMap<Currency, Long> before = count(java.util.Collections.singletonList(originalPage));
            EnumMap<Currency, Long> after = count(java.util.Collections.singletonList(entry.getValue()));
            for (Currency currency : Currency.values()) {
                long value = Math.subtractExact(current.get(currency), before.getOrDefault(currency, 0L));
                current.put(currency, Math.addExact(value, after.getOrDefault(currency, 0L)));
            }
        }
        return current;
    }

    private static ItemStack[] itemPage(Balance balance, long page) {
        ItemStack[] items = new ItemStack[BALANCE_SLOTS];
        long firstStack = Math.multiplyExact(page, BALANCE_SLOTS);
        long diamondStacks = stackCount(balance.diamondOre());
        long deepslateStacks = stackCount(balance.deepslateDiamondOre());
        for (int slot = 0; slot < BALANCE_SLOTS; slot++) {
            long stack = firstStack + slot;
            if (stack < diamondStacks) {
                items[slot] = stack(balance.diamondOre(), stack, Currency.DIAMOND_ORE);
            } else if (stack - diamondStacks < deepslateStacks) {
                items[slot] = stack(balance.deepslateDiamondOre(), stack - diamondStacks,
                        Currency.DEEPSLATE_DIAMOND_ORE);
            }
        }
        return items;
    }

    private static ItemStack stack(long amount, long index, Currency currency) {
        long remaining = amount - index * MAX_STACK_SIZE;
        return new ItemStack(currency.material(), (int) Math.min(MAX_STACK_SIZE, remaining));
    }

    private static long pageCount(Balance balance) {
        long stacks = Math.addExact(stackCount(balance.diamondOre()), stackCount(balance.deepslateDiamondOre()));
        return stacks == 0 ? 1 : (stacks - 1) / BALANCE_SLOTS + 1;
    }

    private static long stackCount(long amount) {
        return amount == 0 ? 0 : (amount - 1) / MAX_STACK_SIZE + 1;
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
        private final UUID operationId = UUID.randomUUID();
        private final Balance original;
        private final Map<Long, ItemStack[]> pages = new ConcurrentHashMap<>();
        private final long lastOriginalPage;
        private long page;
        private Inventory inventory;

        private Session(UUID playerId, Balance original) {
            this.playerId = playerId;
            this.original = original;
            this.lastOriginalPage = pageCount(original) - 1;
        }
    }

    record OreStack(Currency currency, int amount) {
    }
}
