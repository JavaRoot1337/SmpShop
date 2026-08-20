package ru.javaroot.list;

import org.bukkit.ChatColor;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;

import java.util.Collection;

public class ShopProtect implements Listener {
    private final ShopMgr shops;
    private final Sched sched;

    public ShopProtect(ShopMgr shops, Sched sched) {
        this.shops = shops;
        this.sched = sched;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (shops.find(ShopKey.of(event.getBlock().getLocation())) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> shops.find(ShopKey.of(block.getLocation())) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> shops.find(ShopKey.of(block.getLocation())) != null);
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
    public void onHopperPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.HOPPER && isNearShop(event.getBlockPlaced())) {
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

    public void clearShopNames(Collection<Shop> loadedShops) {
        for (Shop shop : loadedShops) {
            clearName(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
            if (shop.x2() != null) {
                clearName(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
            }
        }
    }

    private void clearName(java.util.UUID worldId, int x, int y, int z) {
        Location location = new Location(org.bukkit.Bukkit.getWorld(worldId), x, y, z);
        if (location.getWorld() == null) {
            return;
        }
        sched.region(location, () -> {
            Block block = location.getBlock();
            if (block.getType() != Material.CHEST) {
                return;
            }
            Chest chest = (Chest) block.getState();
            String name = chest.getCustomName();
            if (name != null && ChatColor.stripColor(name).startsWith("Магазин игрока: ")) {
                chest.setCustomName(null);
                chest.update(true, false);
            }
        });
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

    private boolean isShopHolder(InventoryHolder holder) {
        return holder instanceof Chest chest && isShopBlock(chest.getBlock());
    }

    private boolean isShopBlock(Block block) {
        return shops.find(ShopKey.of(block.getLocation())) != null;
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
}
