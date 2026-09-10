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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class BalanceInv implements Listener {
    private static final int GUI_SIZE = 54;
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
        if (rawSlot >= 0 && rawSlot < GUI_SIZE && rawSlot >= BalancePages.BALANCE_SLOTS) {
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
            if (rawSlot >= 0 && rawSlot < GUI_SIZE && rawSlot >= BalancePages.BALANCE_SLOTS) {
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
            Runnable close = () -> {
                capture(session);
                if (player.getOpenInventory().getTopInventory() == session.inventory) {
                    player.closeInventory();
                }
                settleAndUnlock(session, player).whenComplete((ignored, error) -> complete(closing, error));
            };
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                close.run();
            } else {
                sched.entity(player, close,
                        () -> settleAndUnlock(session, null).whenComplete((ignored, error) -> complete(closing, error)));
            }
        }
        return CompletableFuture.allOf(closings.toArray(CompletableFuture[]::new));
    }

    public boolean isOpen(UUID playerId) {
        return sessions.containsKey(playerId) || closing.contains(playerId);
    }

    static List<OreStack[]> pack(Balance balance) {
        return BalancePages.pack(balance);
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
        if (!BalancePages.full(session.pages.get(session.page))) {
            return;
        }
        session.page++;
        showPage(session);
    }

    private void showPage(Session session) {
        ItemStack[] contents = new ItemStack[GUI_SIZE];
        ItemStack[] page = session.pages.computeIfAbsent(session.page,
                pageNumber -> BalancePages.itemPage(session.original, pageNumber));
        for (int slot = 0; slot < BalancePages.BALANCE_SLOTS; slot++) {
            contents[slot] = copy(page[slot]);
        }
        contents[PREVIOUS_SLOT] = navigation("balance-previous");
        contents[NEXT_SLOT] = navigation("balance-next");
        session.inventory.setContents(contents);
    }

    private void capture(Session session) {
        ItemStack[] page = session.pages.computeIfAbsent(session.page,
                pageNumber -> BalancePages.itemPage(session.original, pageNumber));
        for (int slot = 0; slot < BalancePages.BALANCE_SLOTS; slot++) {
            page[slot] = copy(session.inventory.getItem(slot));
        }
    }

    private void close(Player player, Session session) {
        settleAndUnlock(session, player)
                .whenComplete((ignored, error) -> logIfFailed("Не удалось сохранить баланс", error));
    }

    private CompletableFuture<Void> settleAndUnlock(Session session, Player player) {
        CompletableFuture<Void> settled;
        try {
            settled = settle(session, player);
        } catch (RuntimeException exception) {
            settled = CompletableFuture.failedFuture(exception);
        }
        return settled.whenComplete((ignored, error) -> closing.remove(session.playerId));
    }

    private CompletableFuture<Void> settle(Session session, Player player) {
        EnumMap<Currency, Long> current = BalancePages.changedBalance(session.original, session.pages);
        List<ItemStack> invalid = BalancePages.invalidItems(session.pages.values());
        long diamondDelta = Math.subtractExact(current.getOrDefault(Currency.DIAMOND_ORE, 0L),
                session.original.diamondOre());
        long deepslateDelta = Math.subtractExact(current.getOrDefault(Currency.DEEPSLATE_DIAMOND_ORE, 0L),
                session.original.deepslateDiamondOre());
        CompletableFuture<BalanceStore.MutationResult> settled;
        try {
            settled = balances.settle(session.operationId, session.playerId, diamondDelta, deepslateDelta);
        } catch (RuntimeException exception) {
            return recoverFailedSettle(session, player, invalid, diamondDelta, deepslateDelta, exception);
        }
        return settled.thenCompose(result -> {
            if (result.status() == BalanceStore.MutationStatus.SUCCESS) {
                returnItems(player, invalid);
                return CompletableFuture.completedFuture(null);
            }
            return recoverFailedSettle(session, player, invalid, diamondDelta, deepslateDelta,
                    new IllegalStateException("Баланс изменился во время работы GUI: " + result.status()));
        });
    }

    private CompletableFuture<Void> recoverFailedSettle(Session session, Player player, List<ItemStack> invalid,
                                                        long diamondDelta, long deepslateDelta, Throwable failure) {
        List<ItemStack> returned = new ArrayList<>(invalid);
        if (diamondDelta > 0 && player != null && player.isOnline()) {
            addReturnedStacks(returned, Currency.DIAMOND_ORE, diamondDelta);
        }
        if (deepslateDelta > 0 && player != null && player.isOnline()) {
            addReturnedStacks(returned, Currency.DEEPSLATE_DIAMOND_ORE, deepslateDelta);
        }
        CompletableFuture<?> diamond = compensateFailedDelta(session, Currency.DIAMOND_ORE, diamondDelta, player);
        CompletableFuture<?> deepslate = compensateFailedDelta(session, Currency.DEEPSLATE_DIAMOND_ORE,
                deepslateDelta, player);
        returnItems(player, returned);
        return CompletableFuture.allOf(diamond, deepslate)
                .thenCompose(ignored -> CompletableFuture.failedFuture(failure));
    }

    private CompletableFuture<?> compensateFailedDelta(Session session, Currency currency, long delta, Player player) {
        if (delta == 0 || (delta > 0 && player != null && player.isOnline())) {
            return CompletableFuture.completedFuture(null);
        }
        long amount = delta > 0 ? delta : -delta;
        UUID operationId = UUID.nameUUIDFromBytes((session.operationId + ":" + currency)
                .getBytes(StandardCharsets.UTF_8));
        return balances.change(operationId, "smpshop:balance_gui_compensation", session.playerId, currency, amount)
                .thenAccept(result -> {
                    if (result.status() != BalanceStore.MutationStatus.SUCCESS) {
                        throw new IllegalStateException("Не удалось компенсировать изменение баланса: " + result.status());
                    }
                });
    }

    private void addReturnedStacks(List<ItemStack> returned, Currency currency, long amount) {
        while (amount > 0) {
            int stack = (int) Math.min(64, amount);
            returned.add(new ItemStack(currency.material(), stack));
            amount -= stack;
        }
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
        for (int slot = 0; slot < BalancePages.BALANCE_SLOTS && remaining > 0; slot++) {
            ItemStack target = session.inventory.getItem(slot);
            if (target != null && target.isSimilar(item)) {
                int moved = Math.min(remaining, target.getMaxStackSize() - target.getAmount());
                target.setAmount(target.getAmount() + moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < BalancePages.BALANCE_SLOTS && remaining > 0; slot++) {
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
            this.lastOriginalPage = BalancePages.pageCount(original) - 1;
        }
    }

    record OreStack(Currency currency, int amount) {
    }
}
