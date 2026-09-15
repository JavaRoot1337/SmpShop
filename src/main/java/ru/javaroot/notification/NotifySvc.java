package ru.javaroot.notification;

import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.DbLoc;
import ru.javaroot.config.Msg;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.data.NotificationStore;
import ru.javaroot.integration.SaleLog;
import ru.javaroot.item.ItemName;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.logersxd.Loggers;
import ru.javaroot.sound.SoundSvc;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NotifySvc implements Listener {
    private final Loggers.Channel logger;
    private final Cfg cfg;
    private final Msg msg;
    private final OtherMsg other;
    private final DbLoc dbLoc;
    private final NotificationStore store;
    private final SoundSvc sounds;
    private final ItemName itemNames;
    private final Sched sched;
    private volatile boolean ready;

    public NotifySvc(Logger logger, Cfg cfg, Msg msg, OtherMsg other, DbLoc dbLoc, NotificationStore store, SoundSvc sounds,
                     ItemName itemNames, Sched sched) {
        this(new Loggers(logger).notifications(), cfg, msg, other, dbLoc, store, sounds, itemNames, sched);
    }

    public NotifySvc(Loggers logs, Cfg cfg, Msg msg, OtherMsg other, DbLoc dbLoc, NotificationStore store,
                     SoundSvc sounds, ItemName itemNames, Sched sched) {
        this(logs.notifications(), cfg, msg, other, dbLoc, store, sounds, itemNames, sched);
    }

    public NotifySvc(Loggers.Channel logger, Cfg cfg, Msg msg, OtherMsg other, DbLoc dbLoc,
                     NotificationStore store, SoundSvc sounds, ItemName itemNames, Sched sched) {
        this.logger = logger;
        this.cfg = cfg;
        this.msg = msg;
        this.other = other;
        this.dbLoc = dbLoc;
        this.store = store;
        this.sounds = sounds;
        this.itemNames = itemNames;
        this.sched = sched;
    }

    public void sale(SaleLog sale, UUID transactionId) {
        if (sched.global(() -> notifySeller(sale, transactionId)) != Sched.ScheduleResult.SCHEDULED) {
            saveOffline(sale.sellerId(), sale.amount(), transactionId);
        }
    }

    private void notifySeller(SaleLog sale, UUID transactionId) {
        Player seller = Bukkit.getPlayer(sale.sellerId());
        if (seller == null) {
            saveOffline(sale.sellerId(), sale.amount(), transactionId);
            return;
        }
        sched.entity(seller, () -> {
            if (!seller.isOnline()) {
                saveOffline(sale.sellerId(), sale.amount(), transactionId);
                return;
            }
            msg.send(seller, "sale-chat", Map.of(
                    "shop", sale.shopName(),
                    "items", sale.items(),
                    "amount", sale.amount()),
                    Map.of("item", itemNames.component(sale.material())));
            sounds.sellerSale(seller);
        }, () -> saveOffline(sale.sellerId(), sale.amount(), transactionId));
    }

    public void start() {
        ready = true;
    }

    public void stop() {
        ready = false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!ready || !cfg.offlineNotificationsEnabled() || !cfg.joinMessageEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        store.pending(playerId).thenAccept(pending -> {
            if (pending.amount() < 1 || pending.ids().isEmpty()) {
                return;
            }
            sched.entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                msg.send(player, "offline-sales", Map.of("amount", pending.amount()));
                store.markDelivered(pending.ids()).exceptionally(error -> {
                    logger.log(Level.WARNING, dbLoc.raw("notification.delivery-failed"), error);
                    return null;
                });
            });
        }).exceptionally(error -> {
            logger.log(Level.WARNING, dbLoc.raw("notification.load-failed"), error);
            return null;
        });
    }

    private void saveOffline(UUID playerId, long amount, UUID transactionId) {
        if (!cfg.offlineNotificationsEnabled()) {
            return;
        }
        store.add(playerId, amount, transactionId).exceptionally(error -> {
            logger.log(Level.WARNING, dbLoc.raw("notification.save-failed"), error);
            return null;
        });
    }
}
