package ru.javaroot.smpshop.gui;

import net.kyori.adventure.text.Component;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.cfg.Msg;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.plat.Hex;
import ru.javaroot.smpshop.shop.CurType;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopMgr;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ListGui implements Listener {
    private final ShopMgr shops;
    private final CfgLoad cfgLoad;
    private final Exec exec;
    private final NamespacedKey navKey;
    private final NamespacedKey pageKey;
    private final Map<UUID, Holder> opened = new ConcurrentHashMap<>();
    private final Map<UUID, Long> generations = new ConcurrentHashMap<>();

    public ListGui(Plugin plug, ShopMgr shops, CfgLoad cfgLoad, Exec exec) {
        this.shops = shops;
        this.cfgLoad = cfgLoad;
        this.exec = exec;
        this.navKey = new NamespacedKey(plug, "shop-nav");
        this.pageKey = new NamespacedKey(plug, "shop-page");
    }

    public void open(Player p, UUID ownerId) {
        open(p, ownerId, 0);
    }

    private void open(Player p, UUID ownerId, int page) {
        UUID viewerId = p.getUniqueId();
        long gen = generations.merge(viewerId, 1L, Long::sum);
        List<Shop> filtered = shops.all().stream()
                .filter(s -> s.ownerId().equals(ownerId))
                .sorted(Comparator.comparingLong(Shop::id))
                .toList();
        int menuSize = cfgLoad.cfg().menuSize();
        int perPage = menuSize - 9;
        int pages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
        int cur = Math.max(0, Math.min(page, pages - 1));
        int from = cur * perPage;
        int to = Math.min(filtered.size(), from + perPage);
        List<Shop> pageShops = filtered.subList(from, to);

        List<CompletableFuture<ItemStack>> icons = pageShops.stream().map(this::buildIcon).toList();
        CompletableFuture.allOf(icons.toArray(CompletableFuture[]::new)).thenAccept(ignored -> exec.entity(p, () -> {
            if (!p.isOnline() || generations.getOrDefault(viewerId, 0L) != gen) {
                return;
            }
            List<ItemStack> items = icons.stream().map(CompletableFuture::join).toList();
            Holder holder = createMenu(ownerId, cur, pages, menuSize, items);
            opened.put(viewerId, holder);
            p.openInventory(holder.getInventory());
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)
                || !(event.getView().getTopInventory().getHolder() instanceof Holder holder)
                || opened.get(p.getUniqueId()) != holder) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String nav = meta.getPersistentDataContainer().get(navKey, PersistentDataType.STRING);
        if (nav == null) {
            return;
        }
        int page = meta.getPersistentDataContainer().getOrDefault(pageKey, PersistentDataType.INTEGER, 0);
        if ("next".equals(nav)) {
            open(p, holder.ownerId, page + 1);
        } else if ("previous".equals(nav)) {
            open(p, holder.ownerId, page - 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p
                && event.getView().getTopInventory().getHolder() instanceof Holder holder
                && opened.get(p.getUniqueId()) == holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p
                && event.getView().getTopInventory().getHolder() instanceof Holder holder) {
            opened.remove(p.getUniqueId(), holder);
            generations.merge(p.getUniqueId(), 1L, Long::sum);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        opened.remove(id);
        generations.merge(id, 1L, Long::sum);
    }

    public void closeAll() {
        Map<UUID, Holder> current = Map.copyOf(opened);
        opened.clear();
        current.keySet().forEach(id -> generations.merge(id, 1L, Long::sum));
        current.forEach((id, holder) -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                exec.entity(p, () -> {
                    if (p.getOpenInventory().getTopInventory() == holder.getInventory()) {
                        p.closeInventory();
                    }
                });
            }
        });
    }

    private CompletableFuture<ItemStack> buildIcon(Shop shop) {
        CompletableFuture<ItemStack> fut = new CompletableFuture<>();
        World w = Bukkit.getWorld(shop.worldId());
        if (w == null) {
            fut.complete(new ItemStack(Material.BARRIER));
            return fut;
        }
        Location loc = new Location(w, shop.x1(), shop.y1(), shop.z1());
        if (exec.region(loc, () -> fut.complete(createIcon(shop, loc))) != Exec.Result.SCHEDULED) {
            fut.complete(new ItemStack(Material.BARRIER));
        }
        return fut;
    }

    private ItemStack createIcon(Shop shop, Location loc) {
        Material mat = mostCommon(loc);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        Msg m = cfgLoad.msg();
        meta.displayName(Hex.noItalic(m.comp("list-shop-name", Map.of("id", shop.id()))));
        meta.lore(List.of(
                Hex.noItalic(m.comp("list-owner", Map.of("owner", shop.ownerName()))),
                Hex.noItalic(m.comp("list-coordinates", Map.of("x", shop.x1(), "y", shop.y1(), "z", shop.z1()))),
                Hex.noItalic(m.comp("list-earned", Map.of("earned", shop.earnedTotal()))),
                Hex.noItalic(m.comp("list-sold", Map.of("sold", shop.soldItems()))),
                Hex.noItalic(m.comp("list-price", Map.of("price", shop.price())))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static Material mostCommon(Location loc) {
        if (loc.getBlock().getType() != Material.CHEST) {
            return Material.BARRIER;
        }
        Chest chest = (Chest) loc.getBlock().getState();
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack item : chest.getInventory().getContents()) {
            if (item == null || item.getType().isAir() || CurType.from(item.getType()) != null) {
                continue;
            }
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Material.BARRIER);
    }

    private Holder createMenu(UUID ownerId, int page, int pages, int size, List<ItemStack> icons) {
        Msg m = cfgLoad.msg();
        Holder holder = new Holder(ownerId);
        Inventory inv = Bukkit.createInventory(holder, size, m.comp("list-title", Map.of("page", page + 1)));
        holder.bind(inv);
        for (int i = 0; i < icons.size(); i++) {
            inv.setItem(i, icons.get(i));
        }
        if (page > 0) {
            inv.setItem(size - 9, navItem("previous", page, m.comp("list-previous")));
        }
        if (page + 1 < pages) {
            inv.setItem(size - 1, navItem("next", page, m.comp("list-next")));
        }
        return holder;
    }

    private ItemStack navItem(String dir, int page, Component name) {
        ItemStack item = new ItemStack("next".equals(dir) ? Material.ARROW : Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Hex.noItalic(name));
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.STRING, dir);
        meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
        item.setItemMeta(meta);
        return item;
    }

    private static final class Holder extends MenuHolder {
        private final UUID ownerId;

        private Holder(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }
}
