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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopKey;
import ru.javaroot.shop.ShopMgr;

import java.util.Collection;
import java.util.logging.Logger;

public class ShopProtect implements Listener {
    private final ShopMgr shops;
    private final Sched sched;
    private final Logger logger;

    public ShopProtect(ShopMgr shops, Sched sched, Logger logger) {
        this.shops = shops;
        this.sched = sched;
        this.logger = logger;
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

    public void clearShopNames(Collection<Shop> loadedShops) {
        for (Shop shop : loadedShops) {
            clearName(shop.worldId(), shop.x1(), shop.y1(), shop.z1());
            if (shop.x2() != null) {
                clearName(shop.worldId(), shop.x2(), shop.y2(), shop.z2());
            }
        }
    }

    public void audit(Collection<Shop> loadedShops) {
        for (Shop shop : loadedShops) {
            sched.global(() -> {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(shop.worldId());
                if (world == null) {
                    logger.warning("Магазин #" + shop.id() + " ссылается на отсутствующий мир");
                    return;
                }
                Location location = new Location(world, shop.x1(), shop.y1(), shop.z1());
                sched.region(location, () -> {
                    if (location.getBlock().getType() != Material.CHEST) {
                        logger.warning("Магазин #" + shop.id() + " ссылается на отсутствующий сундук");
                    }
                });
            });
        }
    }

    private void clearName(java.util.UUID worldId, int x, int y, int z) {
        sched.global(() -> {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldId);
            if (world == null) {
                return;
            }
            Location location = new Location(world, x, y, z);
            sched.region(location, () -> {
                Block block = location.getBlock();
                if (block.getType() != Material.CHEST) {
                    return;
                }
                Chest chest = (Chest) block.getState();
                Component name = chest.customName();
                if (name != null && PlainTextComponentSerializer.plainText().serialize(name).startsWith("Магазин игрока: ")) {
                    chest.customName(null);
                    chest.update(true, false);
                }
            });
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
