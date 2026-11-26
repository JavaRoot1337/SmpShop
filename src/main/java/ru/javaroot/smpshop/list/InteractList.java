package ru.javaroot.smpshop.list;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.cmd.ShopCmd;
import ru.javaroot.smpshop.db.ShopRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.PendingAct;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopKey;
import ru.javaroot.smpshop.shop.ShopMgr;
import ru.javaroot.smpshop.trade.TradeSvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class InteractList implements Listener {
    private final Logger log;
    private final PendingAct pending;
    private final CfgLoad cfgLoad;
    private final ShopRepo shopRepo;
    private final ShopMgr shops;
    private final Exec exec;
    private final TradeSvc trade;

    public InteractList(Logger log, PendingAct pending, CfgLoad cfgLoad, ShopRepo shopRepo,
                        ShopMgr shops, Exec exec, TradeSvc trade) {
        this.log = log;
        this.pending = pending;
        this.cfgLoad = cfgLoad;
        this.shopRepo = shopRepo;
        this.shops = shops;
        this.exec = exec;
        this.trade = trade;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST) {
            return;
        }
        Player p = event.getPlayer();
        PendingAct.Entry act = pending.get(p.getUniqueId());
        if (act == null) {
            return;
        }
        if (act.type() == PendingAct.Type.PLACE) {
            event.setCancelled(true);
            place(p, event.getClickedBlock().getLocation(), act);
            return;
        }
        Shop shop = shops.find(ShopKey.of(event.getClickedBlock().getLocation()));
        if (shop == null) {
            return;
        }
        event.setCancelled(true);
        if (act.type() == PendingAct.Type.REMOVE && !p.hasPermission(ShopCmd.PERM_ADMIN_REMOVE)) {
            cfgLoad.msg().send(p, "no-permission");
            pending.clear(p.getUniqueId());
            return;
        }
        if (!p.hasPermission(ShopCmd.PERM_USE)) {
            cfgLoad.msg().send(p, "no-permission");
            pending.clear(p.getUniqueId());
            return;
        }
        if (act.type() == PendingAct.Type.DELETE && !shop.ownerId().equals(p.getUniqueId())) {
            cfgLoad.msg().send(p, "shop-not-owner");
            pending.clear(p.getUniqueId());
            return;
        }
        delete(p, shop);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.clear(event.getPlayer().getUniqueId());
    }

    private void place(Player p, Location loc, PendingAct.Entry act) {
        List<Location> locs = chestLocations(loc);
        List<ShopKey> keys = locs.stream().map(ShopKey::of).toList();
        var cfg = cfgLoad.cfg();
        int limit = p.hasPermission(ShopCmd.PERM_PLUS) ? cfg.plusMaxPerPlayer() : cfg.maxPerPlayer();
        pending.clear(p.getUniqueId());
        if (!shops.tryReserve(p.getUniqueId(), limit, keys)) {
            cfgLoad.msg().send(p, "shop-conflict");
            return;
        }
        Location first = locs.getFirst();
        Shop draft = new Shop(
                0, p.getUniqueId(), p.getName(), first.getWorld().getUID(),
                first.getBlockX(), first.getBlockY(), first.getBlockZ(),
                locs.size() == 2 ? locs.get(1).getBlockX() : null,
                locs.size() == 2 ? locs.get(1).getBlockY() : null,
                locs.size() == 2 ? locs.get(1).getBlockZ() : null,
                act.price(), 0, 0, 0, System.currentTimeMillis()
        );
        shopRepo.insert(draft).thenAccept(id -> {
            Runnable cleanup = () -> {
                shops.release(p.getUniqueId(), keys);
                shopRepo.delete(id).exceptionally(err -> {
                    log.warning("Не удалось очистить отменённый магазин #" + id + ": " + err.getMessage());
                    return null;
                });
            };
            if (exec.region(first, () -> {
                boolean invalid = locs.stream().anyMatch(l -> l.getBlock().getType() != Material.CHEST);
                boolean conflict = keys.stream().anyMatch(shops::isRegistered);
                if (invalid || conflict) {
                    cleanup.run();
                    exec.entity(p, () -> cfgLoad.msg().send(p, "shop-conflict"));
                    return;
                }
                Shop shop = new Shop(id, draft.ownerId(), draft.ownerName(), draft.worldId(),
                        draft.x1(), draft.y1(), draft.z1(), draft.x2(), draft.y2(), draft.z2(),
                        draft.price(), 0, 0, 0, draft.createdAt());
                if (exec.global(() -> {
                    try {
                        shops.add(shop);
                        shops.release(p.getUniqueId(), keys);
                        exec.entity(p, () -> cfgLoad.msg().send(p, "shop-created", Map.of("price", shop.price())));
                    } catch (RuntimeException ex) {
                        cleanup.run();
                        log.warning("Не удалось зарегистрировать магазин #" + id + ": " + ex.getMessage());
                    }
                }) != Exec.Result.SCHEDULED) {
                    cleanup.run();
                    exec.entity(p, () -> cfgLoad.msg().send(p, "shop-conflict"));
                }
            }) != Exec.Result.SCHEDULED) {
                cleanup.run();
                exec.entity(p, () -> cfgLoad.msg().send(p, "shop-conflict"));
            }
        }).exceptionally(err -> {
            shops.release(p.getUniqueId(), keys);
            log.warning("Не удалось сохранить магазин: " + err.getMessage());
            return null;
        });
    }

    private void delete(Player p, Shop shop) {
        pending.clear(p.getUniqueId());
        if (!trade.reserveRemoval(shop.id())) {
            cfgLoad.msg().send(p, "shop-busy");
            return;
        }
        shopRepo.delete(shop.id())
                .thenRun(() -> exec.global(() -> {
                    shops.remove(shop);
                    exec.entity(p, () -> cfgLoad.msg().send(p, "shop-deleted"));
                }))
                .whenComplete((ignored, err) -> {
                    trade.releaseRemoval(shop.id());
                    if (err != null) {
                        log.warning("Не удалось удалить магазин #" + shop.id() + ": " + err.getMessage());
                    }
                });
    }

    private static List<Location> chestLocations(Location loc) {
        Chest chest = (Chest) loc.getBlock().getState();
        if (!(chest.getInventory() instanceof DoubleChestInventory inv)) {
            return List.of(loc);
        }
        List<Location> locs = new ArrayList<>();
        addHolder(locs, inv.getLeftSide().getHolder());
        addHolder(locs, inv.getRightSide().getHolder());
        return locs.isEmpty() ? List.of(loc) : locs;
    }

    private static void addHolder(List<Location> locs, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            locs.add(chest.getLocation());
        }
    }
}
