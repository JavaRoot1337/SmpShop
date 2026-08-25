package ru.javaroot.notification;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.Msg;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.data.NotificationStore;
import ru.javaroot.integration.SaleLog;
import ru.javaroot.item.ItemName;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NotifySvc implements Listener {
    private final Logger logger;
    private final Cfg cfg;
    private final Msg msg;
    private final OtherMsg other;
    private final NotificationStore store;
    private final SoundSvc sounds;
    private final ItemName itemNames;
    private final Sched sched;
    private volatile boolean ready;

    public NotifySvc(Logger logger, Cfg cfg, Msg msg, OtherMsg other, NotificationStore store, SoundSvc sounds,
                     ItemName itemNames, Sched sched) {
        this.logger = logger;
        this.cfg = cfg;
        this.msg = msg;
        this.other = other;
        this.store = store;
        this.sounds = sounds;
        this.itemNames = itemNames;
        this.sched = sched;
    }

    public void sale(SaleLog sale, Player seller) {
        if (seller == null || !seller.isOnline()) {
            saveOffline(sale.sellerId(), sale.amount());
            return;
        }
        sched.entity(seller, () -> {
            msg.send(seller, "sale-chat", Map.of(
                    "shop", sale.shopName(),
                    "items", sale.items(),
                    "amount", sale.amount()),
                    Map.of("item", itemNames.component(sale.material())));
            sounds.sellerSale(seller);
        }, () -> saveOffline(sale.sellerId(), sale.amount()));
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
                    logger.log(Level.WARNING, other.raw("notification-delivery-error"), error);
                    return null;
                });
            });
        }).exceptionally(error -> {
            logger.log(Level.WARNING, other.raw("notification-load-error"), error);
            return null;
        });
    }

    private void saveOffline(UUID playerId, long amount) {
        if (!cfg.offlineNotificationsEnabled()) {
            return;
        }
        store.add(playerId, amount).exceptionally(error -> {
            logger.log(Level.WARNING, other.raw("notification-save-error"), error);
            return null;
        });
    }
}
