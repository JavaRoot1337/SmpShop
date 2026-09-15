package ru.javaroot.list;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.Material;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.logersxd.Loggers;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;

import java.util.Collection;
import java.util.logging.Logger;

public class ShopProtect implements Listener {
    private final ShopMgr shops;
    private final Sched sched;
    private final Loggers.Channel logger;
    private final OtherMsg other;

    public ShopProtect(ShopMgr shops, Sched sched, Logger logger, OtherMsg other) {
        this(shops, sched, new Loggers(logger).shop(), other);
    }

    public ShopProtect(ShopMgr shops, Sched sched, Loggers.Channel logger, OtherMsg other) {
        this.shops = shops;
        this.sched = sched;
        this.logger = logger;
        this.other = other;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isShopBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isShopBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isShopBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() == Material.HOPPER && isNearShop(block)) {
            event.setCancelled(true);
            return;
        }
        if (block.getType() == Material.CHEST && touchesSingleShop(block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperMinecartPlace(EntityPlaceEvent event) {
        if (event.getEntity() instanceof HopperMinecart && isNearShop(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isShopInventory(event.getSource()) || isShopInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (isShopInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isReservedInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    public void audit(Collection<Shop> loadedShops) {
        for (Shop shop : loadedShops) {
            sched.global(() -> {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(shop.worldId());
                if (world == null) {
                    logger.warning(other.raw("shop-missing-world", java.util.Map.of("shop", shop.id())));
                    return;
                }
                Location location = new Location(world, shop.x1(), shop.y1(), shop.z1());
                sched.region(location, () -> {
                    if (location.getBlock().getType() != Material.CHEST) {
                        logger.warning(other.raw("shop-missing-chest", java.util.Map.of("shop", shop.id())));
                    }
                });
            });
        }
    }

    private boolean isShopInventory(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            return isShopBlock(chest.getBlock());
        }
        if (holder instanceof DoubleChest doubleChest) {
            return isShopHolder(doubleChest.getLeftSide()) || isShopHolder(doubleChest.getRightSide());
        }
        return false;
    }

    private boolean isReservedInventory(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            return isReservedBlock(chest.getBlock());
        }
        if (holder instanceof DoubleChest doubleChest) {
            return isReservedHolder(doubleChest.getLeftSide()) || isReservedHolder(doubleChest.getRightSide());
        }
        return false;
    }

    private boolean isShopHolder(InventoryHolder holder) {
        return holder instanceof Chest chest && isShopBlock(chest.getBlock());
    }

    private boolean isReservedHolder(InventoryHolder holder) {
        return holder instanceof Chest chest && isReservedBlock(chest.getBlock());
    }

    private boolean isShopBlock(Block block) {
        return shops.isProtected(ShopKey.of(block.getLocation()));
    }

    private boolean isReservedBlock(Block block) {
        return shops.isReserved(ShopKey.of(block.getLocation()));
    }

    private boolean isNearShop(Block block) {
        if (isShopBlock(block)) {
            return true;
        }
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            if (isShopBlock(block.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    private boolean touchesSingleShop(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Shop shop = shops.find(ShopKey.of(block.getRelative(face).getLocation()));
            if (shop != null && shop.secondKey() == null) {
                return true;
            }
        }
        return false;
    }
}
