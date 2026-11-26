package ru.javaroot.smpshop.gui;

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
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.BalRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.plat.Hex;
import ru.javaroot.smpshop.shop.Bal;
import ru.javaroot.smpshop.shop.CurType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class BalGui implements Listener {
    private static final int SIZE = 54;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;

    private final Logger log;
    private final BalRepo balances;
    private final Exec exec;
    private final CfgLoad cfgLoad;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> closing = ConcurrentHashMap.newKeySet();

    public BalGui(Logger log, BalRepo balances, Exec exec, CfgLoad cfgLoad) {
        this.log = log;
        this.balances = balances;
        this.exec = exec;
        this.cfgLoad = cfgLoad;
    }

    public void open(Player p) {
        UUID playerId = p.getUniqueId();
        balances.load(playerId).thenAccept(bal -> exec.entity(p, () -> {
            if (!p.isOnline() || sessions.containsKey(playerId)) {
                return;
            }
            Session sess = new Session(playerId, bal);
            Inventory inv = Bukkit.createInventory(sess, SIZE, cfgLoad.msg().comp("balance-title"));
            sess.bind(inv);
            sessions.put(playerId, sess);
            showPage(sess);
            p.openInventory(inv);
        })).exceptionally(err -> {
            logFailure("Не удалось загрузить баланс", err);
            return null;
        });
    }

    public boolean isOpen(UUID playerId) {
        return sessions.containsKey(playerId) || closing.contains(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)
                || !(event.getView().getTopInventory().getHolder() instanceof Session sess)
                || sessions.get(p.getUniqueId()) != sess) {
            return;
        }
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw == PREV_SLOT) {
            if (isNavClick(event)) {
                prevPage(sess);
            }
            return;
        }
        if (raw == NEXT_SLOT) {
            if (isNavClick(event)) {
                nextPage(sess);
            }
            return;
        }
        if (raw >= 0 && raw < SIZE && raw >= BalPages.SLOTS) {
            return;
        }
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            moveShift(p, sess, event);
            return;
        }
        if (allowedClick(event)) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)
                || !(event.getView().getTopInventory().getHolder() instanceof Session sess)
                || sessions.get(p.getUniqueId()) != sess) {
            return;
        }
        event.setCancelled(true);
        if (!isCurrency(event.getOldCursor())) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < SIZE && raw >= BalPages.SLOTS) {
                return;
            }
        }
        event.setCancelled(false);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p
                && event.getView().getTopInventory().getHolder() instanceof Session sess
                && sessions.remove(p.getUniqueId(), sess)) {
            closing.add(sess.playerId);
            capture(sess);
            settleAndUnlock(sess, p).whenComplete((ignored, err) -> {
                if (err != null) {
                    logFailure("Не удалось сохранить баланс после закрытия меню", err);
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Session sess = sessions.remove(p.getUniqueId());
        if (sess != null) {
            closing.add(sess.playerId);
            capture(sess);
            settleAndUnlock(sess, p).whenComplete((ignored, err) -> {
                if (err != null) {
                    logFailure("Не удалось сохранить баланс при выходе игрока", err);
                }
            });
        }
    }

    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            UUID playerId = entry.getKey();
            Session sess = entry.getValue();
            if (!sessions.remove(playerId, sess)) {
                continue;
            }
            closing.add(playerId);
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                futures.add(settleAndUnlock(sess, null));
                continue;
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            futures.add(done);
            Runnable task = () -> {
                capture(sess);
                if (p.getOpenInventory().getTopInventory() == sess.getInventory()) {
                    p.closeInventory();
                }
                settleAndUnlock(sess, p).whenComplete((ignored, err) -> complete(done, err));
            };
            if (exec.ownsEntity(p)) {
                task.run();
            } else {
                exec.entity(p, task, () -> settleAndUnlock(sess, null).whenComplete((ignored, err) -> complete(done, err)));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private void prevPage(Session sess) {
        capture(sess);
        if (sess.page > 0) {
            sess.page--;
            showPage(sess);
        }
    }

    private void nextPage(Session sess) {
        capture(sess);
        if (sess.page < sess.lastOriginalPage || BalPages.full(sess.pages.get(sess.page))) {
            sess.page++;
            showPage(sess);
        }
    }

    private void showPage(Session sess) {
        ItemStack[] contents = new ItemStack[SIZE];
        ItemStack[] page = sess.pages.computeIfAbsent(sess.page, n -> BalPages.itemPage(sess.original, n));
        for (int slot = 0; slot < BalPages.SLOTS; slot++) {
            contents[slot] = copy(page[slot]);
        }
        contents[PREV_SLOT] = navItem("balance-previous");
        contents[NEXT_SLOT] = navItem("balance-next");
        sess.getInventory().setContents(contents);
    }

    private void capture(Session sess) {
        ItemStack[] page = sess.pages.computeIfAbsent(sess.page, n -> BalPages.itemPage(sess.original, n));
        for (int slot = 0; slot < BalPages.SLOTS; slot++) {
            page[slot] = copy(sess.getInventory().getItem(slot));
        }
    }

    private CompletableFuture<Void> settleAndUnlock(Session sess, Player p) {
        CompletableFuture<Void> fut;
        try {
            fut = settle(sess, p);
        } catch (RuntimeException ex) {
            fut = CompletableFuture.failedFuture(ex);
        }
        return fut.whenComplete((ignored, err) -> closing.remove(sess.playerId));
    }

    private CompletableFuture<Void> settle(Session sess, Player p) {
        EnumMap<CurType, Long> current = BalPages.changedBalance(sess.original, sess.pages);
        List<ItemStack> invalid = BalPages.invalidItems(sess.pages.values());
        long diamondDelta = Math.subtractExact(current.getOrDefault(CurType.DIAMOND_ORE, 0L),
                sess.original.diamondOre());
        long deepslateDelta = Math.subtractExact(current.getOrDefault(CurType.DEEPSLATE_DIAMOND_ORE, 0L),
                sess.original.deepslateDiamondOre());
        CompletableFuture<BalRepo.MutRes> settled;
        try {
            settled = balances.settle(sess.opId, sess.playerId, diamondDelta, deepslateDelta);
        } catch (RuntimeException ex) {
            return recoverFailed(sess, p, invalid, diamondDelta, deepslateDelta, ex);
        }
        return settled.thenCompose(res -> {
            if (res.status() == BalRepo.Status.SUCCESS) {
                return returnItems(p, invalid);
            }
            return recoverFailed(sess, p, invalid, diamondDelta, deepslateDelta,
                    new IllegalStateException("Баланс изменился во время работы меню: " + res.status()));
        });
    }

    private CompletableFuture<Void> recoverFailed(Session sess, Player p, List<ItemStack> invalid,
                                                    long diamondDelta, long deepslateDelta, Throwable failure) {
        List<ItemStack> returned = new ArrayList<>(invalid);
        if (diamondDelta > 0 && p != null && p.isOnline()) {
            addStacks(returned, CurType.DIAMOND_ORE, diamondDelta);
        }
        if (deepslateDelta > 0 && p != null && p.isOnline()) {
            addStacks(returned, CurType.DEEPSLATE_DIAMOND_ORE, deepslateDelta);
        }
        CompletableFuture<?> f1 = compensateDelta(sess, CurType.DIAMOND_ORE, diamondDelta, p);
        CompletableFuture<?> f2 = compensateDelta(sess, CurType.DEEPSLATE_DIAMOND_ORE, deepslateDelta, p);
        CompletableFuture<Void> f3 = returnItems(p, returned);
        return CompletableFuture.allOf(f1, f2, f3).thenCompose(ignored -> CompletableFuture.failedFuture(failure));
    }

    private CompletableFuture<?> compensateDelta(Session sess, CurType cur, long delta, Player p) {
        if (delta == 0 || (delta > 0 && p != null && p.isOnline())) {
            return CompletableFuture.completedFuture(null);
        }
        long amount = delta > 0 ? delta : -delta;
        UUID opId = UUID.nameUUIDFromBytes((sess.opId + ":" + cur).getBytes(StandardCharsets.UTF_8));
        return balances.change(opId, "smpshop:balance_gui_compensation", sess.playerId, cur, amount)
                .thenAccept(res -> {
                    if (res.status() != BalRepo.Status.SUCCESS) {
                        throw new IllegalStateException("Не удалось компенсировать баланс: " + res.status());
                    }
                });
    }

    private void addStacks(List<ItemStack> out, CurType cur, long amount) {
        while (amount > 0) {
            int stack = (int) Math.min(64, amount);
            out.add(new ItemStack(cur.mat(), stack));
            amount -= stack;
        }
    }

    private CompletableFuture<Void> returnItems(Player p, List<ItemStack> items) {
        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (p == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Игрок недоступен для возврата"));
        }
        CompletableFuture<Void> ret = new CompletableFuture<>();
        if (exec.entity(p, () -> {
            try {
                if (!p.isOnline()) {
                    ret.completeExceptionally(new IllegalStateException("Игрок вышел до возврата предметов"));
                    return;
                }
                for (ItemStack item : items) {
                    Map<Integer, ItemStack> overflow = p.getInventory().addItem(item);
                    overflow.values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                }
                ret.complete(null);
            } catch (RuntimeException ex) {
                ret.completeExceptionally(ex);
            }
        }, () -> ret.completeExceptionally(new IllegalStateException("Планировщик игрока недоступен")))
                != Exec.Result.SCHEDULED) {
            ret.completeExceptionally(new IllegalStateException("Планировщик игрока недоступен"));
        }
        return ret;
    }

    private ItemStack navItem(String key) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Hex.noItalic(cfgLoad.msg().comp(key)));
        item.setItemMeta(meta);
        return item;
    }

    private boolean allowedClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return false;
        }
        return allowedItem(event.getCurrentItem()) && allowedItem(event.getCursor());
    }

    private boolean isNavClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        return (click == ClickType.LEFT || click == ClickType.RIGHT) && allowedItem(event.getCursor());
    }

    private void moveShift(Player p, Session sess, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (!isCurrency(item) || event.getClickedInventory() == null) {
            return;
        }
        Inventory inv = sess.getInventory();
        if (event.getRawSlot() < SIZE) {
            Map<Integer, ItemStack> overflow = p.getInventory().addItem(item.clone());
            inv.setItem(event.getRawSlot(), overflow.values().stream().findFirst().orElse(null));
            return;
        }
        int rem = item.getAmount();
        for (int slot = 0; slot < BalPages.SLOTS && rem > 0; slot++) {
            ItemStack target = inv.getItem(slot);
            if (target != null && target.isSimilar(item)) {
                int moved = Math.min(rem, target.getMaxStackSize() - target.getAmount());
                target.setAmount(target.getAmount() + moved);
                rem -= moved;
            }
        }
        for (int slot = 0; slot < BalPages.SLOTS && rem > 0; slot++) {
            if (inv.getItem(slot) != null) {
                continue;
            }
            int moved = Math.min(rem, item.getMaxStackSize());
            ItemStack placed = item.clone();
            placed.setAmount(moved);
            inv.setItem(slot, placed);
            rem -= moved;
        }
        if (rem == 0) {
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            item.setAmount(rem);
            event.getClickedInventory().setItem(event.getSlot(), item);
        }
    }

    private static boolean allowedItem(ItemStack item) {
        return item == null || item.getType().isAir() || isCurrency(item);
    }

    private static boolean isCurrency(ItemStack item) {
        return item != null && CurType.from(item.getType()) != null;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static void complete(CompletableFuture<Void> fut, Throwable err) {
        if (err == null) {
            fut.complete(null);
        } else {
            fut.completeExceptionally(err);
        }
    }

    private void logFailure(String prefix, Throwable err) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        log.warning(prefix + ": " + cause.getMessage());
    }

    private static final class Session extends MenuHolder {
        private final UUID playerId;
        private final UUID opId = UUID.randomUUID();
        private final Bal original;
        private final Map<Long, ItemStack[]> pages = new ConcurrentHashMap<>();
        private final long lastOriginalPage;
        private long page;

        private Session(UUID playerId, Bal original) {
            this.playerId = playerId;
            this.original = original;
            this.lastOriginalPage = BalPages.pageCount(original) - 1;
        }
    }
}
