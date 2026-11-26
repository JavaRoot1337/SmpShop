package ru.javaroot.smpshop.list;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopKey;
import ru.javaroot.smpshop.shop.ShopMgr;

import java.util.Collection;
import java.util.logging.Logger;

public final class ProtectList implements Listener {
    private static final BlockFace[] ALL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };
    private static final BlockFace[] SIDE_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Logger log;
    private final ShopMgr shops;
    private final Exec exec;

    public ProtectList(Logger log, ShopMgr shops, Exec exec) {
        this.log = log;
        this.shops = shops;
        this.exec = exec;
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
            exec.global(() -> {
                World w = Bukkit.getWorld(shop.worldId());
                if (w == null) {
                    log.warning("Магазин #" + shop.id() + " ссылается на отсутствующий мир");
                    return;
                }
                Location loc = new Location(w, shop.x1(), shop.y1(), shop.z1());
                exec.region(loc, () -> {
                    if (loc.getBlock().getType() != Material.CHEST) {
                        log.warning("Магазин #" + shop.id() + " ссылается на отсутствующий сундук");
                    }
                });
            });
        }
    }

    private boolean isShopInventory(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof Chest chest) {
            return isShopBlock(chest.getBlock());
        }
        if (holder instanceof DoubleChest dbl) {
            return isShopHolder(dbl.getLeftSide()) || isShopHolder(dbl.getRightSide());
        }
        return false;
    }

    private boolean isReservedInventory(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof Chest chest) {
            return isReservedBlock(chest.getBlock());
        }
        if (holder instanceof DoubleChest dbl) {
            return isReservedHolder(dbl.getLeftSide()) || isReservedHolder(dbl.getRightSide());
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
        for (BlockFace face : ALL_FACES) {
            if (isShopBlock(block.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    private boolean touchesSingleShop(Block block) {
        for (BlockFace face : SIDE_FACES) {
            Shop shop = shops.find(ShopKey.of(block.getRelative(face).getLocation()));
            if (shop != null && shop.secondKey() == null) {
                return true;
            }
        }
        return false;
    }
}
