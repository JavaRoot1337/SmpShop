package ru.javaroot.gui;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.Msg;
import ru.javaroot.currency.Currency;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.shop.Shop;
import ru.javaroot.shop.ShopMgr;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ShopListInv implements Listener {
    private final ShopMgr shops;
    private final Cfg cfg;
    private final Msg msg;
    private final Sched sched;
    private final NamespacedKey guiKey;
    private final NamespacedKey shopKey;
    private final NamespacedKey pageKey;
    private final NamespacedKey navKey;
    private final Map<UUID, Inventory> opened = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> owners = new ConcurrentHashMap<>();

    public ShopListInv(JavaPlugin plugin, ShopMgr shops, Cfg cfg, Msg msg, Sched sched) {
        this.shops = shops;
        this.cfg = cfg;
        this.msg = msg;
        this.sched = sched;
        this.guiKey = new NamespacedKey(plugin, "shop-list");
        this.shopKey = new NamespacedKey(plugin, "shop-id");
        this.pageKey = new NamespacedKey(plugin, "shop-page");
        this.navKey = new NamespacedKey(plugin, "shop-nav");
    }

    public void open(Player player, UUID ownerId) {
        open(player, ownerId, 0);
    }

    private void open(Player player, UUID ownerId, int page) {
        List<Shop> filtered = shops.all().stream()
                .filter(shop -> shop.ownerId().equals(ownerId))
                .sorted(Comparator.comparingLong(Shop::id))
                .toList();
        int perPage = cfg.menuSize() - 9;
        int pages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * perPage;
        int to = Math.min(filtered.size(), from + perPage);
        List<Shop> pageShops = filtered.subList(from, to);
        List<CompletableFuture<ItemStack>> icons = pageShops.stream().map(this::icon).toList();
        CompletableFuture.allOf(icons.toArray(CompletableFuture[]::new)).thenAccept(ignored -> sched.entity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            List<ItemStack> items = icons.stream().map(CompletableFuture::join).toList();
            Inventory menu = create(current, pages, pageShops, items);
            opened.put(player.getUniqueId(), menu);
            player.openInventory(menu);
            owners.put(player.getUniqueId(), ownerId);
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || opened.get(player.getUniqueId()) != event.getView().getTopInventory()) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!"list".equals(meta.getPersistentDataContainer().get(guiKey, PersistentDataType.STRING))) {
            return;
        }
        int page = meta.getPersistentDataContainer().getOrDefault(pageKey, PersistentDataType.INTEGER, 0);
        String nav = meta.getPersistentDataContainer().get(navKey, PersistentDataType.STRING);
        if ("next".equals(nav)) {
            UUID owner = owners.get(player.getUniqueId());
            if (owner != null) {
                open(player, owner, page + 1);
            }
            return;
        }
        if ("previous".equals(nav)) {
            UUID owner = owners.get(player.getUniqueId());
            if (owner != null) {
                open(player, owner, page - 1);
            }
            return;
        }
        Long id = meta.getPersistentDataContainer().get(shopKey, PersistentDataType.LONG);
        if (id != null) {
            openShop(player, shops.find(id));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            if (opened.remove(player.getUniqueId(), event.getView().getTopInventory())) {
                owners.remove(player.getUniqueId());
            }
        }
    }

    private CompletableFuture<ItemStack> icon(Shop shop) {
        CompletableFuture<ItemStack> result = new CompletableFuture<>();
        World world = Bukkit.getWorld(shop.worldId());
        if (world == null) {
            result.complete(new ItemStack(Material.BARRIER));
            return result;
        }
        Location location = new Location(world, shop.x1(), shop.y1(), shop.z1());
        sched.region(location, () -> result.complete(createIcon(shop, location)));
        return result;
    }

    private ItemStack createIcon(Shop shop, Location location) {
        Material material = mostCommon(location);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(msg.component("list-shop-name", Map.of("id", shop.id())));
        meta.lore(List.of(
                msg.component("list-owner", Map.of("owner", shop.ownerName())),
                msg.component("list-coordinates", Map.of("x", shop.x1(), "y", shop.y1(), "z", shop.z1())),
                msg.component("list-earned", Map.of("earned", shop.earnedTotal())),
                msg.component("list-sold", Map.of("sold", shop.soldItems())),
                msg.component("list-price", Map.of("price", shop.price()))
        ));
        meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, "list");
        meta.getPersistentDataContainer().set(shopKey, PersistentDataType.LONG, shop.id());
        item.setItemMeta(meta);
        return item;
    }

    private Material mostCommon(Location location) {
        if (location.getBlock().getType() != Material.CHEST) {
            return Material.BARRIER;
        }
        Chest chest = (Chest) location.getBlock().getState();
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack item : chest.getInventory().getContents()) {
            if (item == null || item.getType().isAir() || Currency.from(item.getType()) != null) {
                continue;
            }
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Material.BARRIER);
    }

    private Inventory create(int page, int pages, List<Shop> pageShops, List<ItemStack> icons) {
        Inventory inventory = Bukkit.createInventory(null, cfg.menuSize(), msg.component("list-title", Map.of("page", page + 1)));
        for (int index = 0; index < icons.size(); index++) {
            ItemStack item = icons.get(index);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
            item.setItemMeta(meta);
            inventory.setItem(index, item);
        }
        if (page > 0) {
            inventory.setItem(cfg.menuSize() - 9, navigation("previous", page, msg.component("list-previous")));
        }
        if (page + 1 < pages) {
            inventory.setItem(cfg.menuSize() - 1, navigation("next", page, msg.component("list-next")));
        }
        return inventory;
    }

    private ItemStack navigation(String direction, int page, Component name) {
        ItemStack item = new ItemStack("next".equals(direction) ? Material.ARROW : Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, "list");
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, direction);
        meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
        item.setItemMeta(meta);
        return item;
    }

    private void openShop(Player player, Shop shop) {
        if (shop == null) {
            return;
        }
        World world = Bukkit.getWorld(shop.worldId());
        if (world == null) {
            return;
        }
        Location location = new Location(world, shop.x1(), shop.y1(), shop.z1());
        sched.region(location, () -> {
            if (location.getBlock().getType() != Material.CHEST) {
                return;
            }
            Inventory inventory = ((Chest) location.getBlock().getState()).getInventory();
            sched.entity(player, () -> player.openInventory(inventory));
        });
    }

}
