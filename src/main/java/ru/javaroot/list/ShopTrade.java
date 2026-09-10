package ru.javaroot.list;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.config.Msg;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.data.ShopStore;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.item.ItemName;
import ru.javaroot.list.trade.TradeEventHdl;
import ru.javaroot.notification.NotifySvc;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;
import ru.javaroot.shop.ShopMgr;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ShopTrade implements Listener {
    private final TradeEventHdl events;

    public ShopTrade(JavaPlugin plugin, ShopMgr shops, ShopStore shopStore, BalanceStore balances,
                     IntegrationHub integrations, NotifySvc notifications, SoundSvc sounds, Msg msg,
                     ItemName itemNames, Sched sched, Logger logger) {
        events = new TradeEventHdl(plugin, shops, shopStore, balances, integrations, notifications, sounds,
                msg, itemNames, sched, logger);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        events.onOpen(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        events.onClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        events.onDrag(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        events.onDrop(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        events.onUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        events.onMove(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        events.onInventoryPickup(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        events.onEntityPickup(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        events.onDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        events.onSwapHands(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        events.onClose(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        events.onQuit(event);
    }

    public boolean isBusy(long shopId) {
        return events.isBusy(shopId);
    }

    public boolean reserveRemoval(long shopId) {
        return events.reserveRemoval(shopId);
    }

    public void releaseRemoval(long shopId) {
        events.releaseRemoval(shopId);
    }

    public void start() {
        events.start();
    }

    public void stopInput() {
        events.stop();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        events.onJoin(event);
    }

    public CompletableFuture<Void> recover() {
        return events.recover();
    }

    public CompletableFuture<Void> closeAll() {
        return events.closeAll();
    }
}
