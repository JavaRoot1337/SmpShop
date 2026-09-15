package ru.javaroot.list;

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
import org.bukkit.inventory.InventoryHolder;
import ru.javaroot.SmpShop;
import ru.javaroot.command.PermissionKeys;
import ru.javaroot.command.ShopCmd;
import ru.javaroot.config.Msg;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.DbLoc;
import ru.javaroot.data.ShopStore;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;
import ru.javaroot.logersxd.Loggers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ShopInteract implements Listener {
    private final SmpShop plugin;
    private final ShopCmd command;
    private final Msg msg;
    private final Cfg cfg;
    private final ShopStore store;
    private final ShopMgr shops;
    private final Sched sched;
    private final ShopTrade trade;
    private final DbLoc dbMessages;
    private final Loggers.Channel logger;

    public ShopInteract(SmpShop plugin, ShopCmd command, Msg msg, Cfg cfg, DbLoc dbMessages,
                        ShopStore store, ShopMgr shops, Sched sched, ShopTrade trade) {
        this(plugin, command, msg, cfg, dbMessages, store, shops, sched, trade,
                new Loggers(plugin.getLogger()).shop());
    }

    public ShopInteract(SmpShop plugin, ShopCmd command, Msg msg, Cfg cfg, DbLoc dbMessages,
                        ShopStore store, ShopMgr shops, Sched sched, ShopTrade trade,
                        Loggers.Channel logger) {
        this.plugin = plugin;
        this.command = command;
        this.msg = msg;
        this.cfg = cfg;
        this.dbMessages = dbMessages;
        this.store = store;
        this.shops = shops;
        this.sched = sched;
        this.trade = trade;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST) {
            return;
        }
        Player player = event.getPlayer();
        ShopCmd.Action action = command.action(player.getUniqueId());
        if (action != null && action.type() == ShopCmd.ActionType.PLACE) {
            event.setCancelled(true);
            place(player, event.getClickedBlock().getLocation(), action);
            return;
        }
        Shop shop = shops.find(ShopKey.of(event.getClickedBlock().getLocation()));
        if (shop == null || action == null || (action.type() != ShopCmd.ActionType.DELETE && action.type() != ShopCmd.ActionType.REMOVE)) {
            return;
        }
        event.setCancelled(true);
        if (action.type() == ShopCmd.ActionType.REMOVE && !player.hasPermission(PermissionKeys.ADMIN_REMOVE)) {
            msg.send(player, "no-permission");
            command.clearAction(player.getUniqueId());
            return;
        }
        if (!player.hasPermission(PermissionKeys.USE)) {
            msg.send(player, "no-permission");
            command.clearAction(player.getUniqueId());
            return;
        }
        if (action.type() == ShopCmd.ActionType.DELETE && !shop.ownerId().equals(player.getUniqueId())) {
            msg.send(player, "shop-not-owner");
            command.clearAction(player.getUniqueId());
            return;
        }
        delete(player, shop);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        command.clearAction(event.getPlayer().getUniqueId());
    }

    private void place(Player player, Location location, ShopCmd.Action action) {
        List<Location> locations = chestLocations(location);
        List<ShopKey> keys = locations.stream().map(ShopKey::of).toList();
        int limit = player.hasPermission(PermissionKeys.PLUS) ? cfg.plusShopLimit() : cfg.regularShopLimit();
        if (!shops.tryReserve(player.getUniqueId(), limit, keys)) {
            msg.send(player, "shop-conflict");
            command.clearAction(player.getUniqueId());
            return;
        }
        command.clearAction(player.getUniqueId());
        Location first = locations.getFirst();
        Shop draft = new Shop(0, player.getUniqueId(), player.getName(), first.getWorld().getUID(), first.getBlockX(), first.getBlockY(), first.getBlockZ(),
                locations.size() == 2 ? locations.get(1).getBlockX() : null,
                locations.size() == 2 ? locations.get(1).getBlockY() : null,
                locations.size() == 2 ? locations.get(1).getBlockZ() : null,
                action.price(), 0, 0, 0, System.currentTimeMillis());
        store.insert(draft).thenAccept(id -> {
            Runnable cleanup = () -> {
                shops.release(player.getUniqueId(), keys);
                store.delete(id).exceptionally(error -> {
                    logger.warning(dbMessages.raw("shop.cleanup-failed",
                            Map.of("error", String.valueOf(error.getMessage()))));
                    return null;
                });
            };
            if (sched.region(first, () -> {
                boolean invalid = locations.stream().anyMatch(loc -> loc.getBlock().getType() != Material.CHEST);
                boolean conflict = keys.stream().anyMatch(shops::isRegistered);
                if (invalid || conflict) {
                    cleanup.run();
                    sched.entity(player, () -> msg.send(player, "shop-conflict"));
                    return;
                }
                Shop shop = new Shop(id, draft.ownerId(), draft.ownerName(), draft.worldId(), draft.x1(), draft.y1(), draft.z1(), draft.x2(), draft.y2(), draft.z2(), draft.price(), 0, 0, 0, draft.createdAt());
                if (sched.global(() -> {
                    try {
                        shops.add(shop);
                        shops.release(player.getUniqueId(), keys);
                        sched.entity(player, () -> msg.send(player, "shop-created", Map.of("price", shop.price())));
                    } catch (RuntimeException exception) {
                        cleanup.run();
                        logger.warning(dbMessages.raw("shop.registration-failed",
                                Map.of("error", String.valueOf(exception.getMessage()))));
                    }
                }) != Sched.ScheduleResult.SCHEDULED) {
                    cleanup.run();
                    sched.entity(player, () -> msg.send(player, "shop-conflict"));
                }
            }) != Sched.ScheduleResult.SCHEDULED) {
                cleanup.run();
                sched.entity(player, () -> msg.send(player, "shop-conflict"));
            }
        }).exceptionally(error -> {
            shops.release(player.getUniqueId(), keys);
            logger.warning(dbMessages.raw("shop.save-failed",
                    Map.of("error", String.valueOf(error.getMessage()))));
            return null;
        });
    }

    private void delete(Player player, Shop shop) {
        command.clearAction(player.getUniqueId());
        if (!trade.reserveRemoval(shop.id())) {
            msg.send(player, "shop-busy");
            return;
        }
        store.delete(shop.id())
                .thenRun(() -> sched.global(() -> {
                    shops.remove(shop);
                    sched.entity(player, () -> msg.send(player, "shop-deleted"));
                }))
                .whenComplete((ignored, error) -> {
                    trade.releaseRemoval(shop.id());
                    if (error != null) {
                        logger.warning(dbMessages.raw("shop.delete-failed",
                                Map.of("error", String.valueOf(error.getMessage()))));
                    }
                });
    }

    private List<Location> chestLocations(Location location) {
        Chest chest = (Chest) location.getBlock().getState();
        if (!(chest.getInventory() instanceof DoubleChestInventory inventory)) {
            return List.of(location);
        }
        List<Location> locations = new ArrayList<>();
        addChestLocation(locations, inventory.getLeftSide().getHolder());
        addChestLocation(locations, inventory.getRightSide().getHolder());
        return locations.isEmpty() ? List.of(location) : locations;
    }

    private void addChestLocation(List<Location> locations, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            locations.add(chest.getLocation());
        }
    }
}
