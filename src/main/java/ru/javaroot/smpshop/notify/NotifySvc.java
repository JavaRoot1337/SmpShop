package ru.javaroot.smpshop.notify;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.NotifyRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.plat.SoundSvc;
import ru.javaroot.smpshop.shop.SaleLog;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NotifySvc implements Listener {
    private final Logger log;
    private final CfgLoad cfgLoad;
    private final NotifyRepo repo;
    private final SoundSvc sounds;
    private final Exec exec;
    private volatile boolean ready;

    public NotifySvc(Logger log, CfgLoad cfgLoad, NotifyRepo repo, SoundSvc sounds, Exec exec) {
        this.log = log;
        this.cfgLoad = cfgLoad;
        this.repo = repo;
        this.sounds = sounds;
        this.exec = exec;
    }

    public void sale(SaleLog sale, UUID txId) {
        if (exec.global(() -> notifySeller(sale, txId)) != Exec.Result.SCHEDULED) {
            saveOffline(sale.sellerId(), sale.amount(), txId);
        }
    }

    public void start() {
        ready = true;
    }

    public void stop() {
        ready = false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!ready || !cfgLoad.cfg().offlineNotify() || !cfgLoad.cfg().joinNotify()) {
            return;
        }
        Player p = event.getPlayer();
        repo.pending(p.getUniqueId()).thenAccept(pending -> {
            if (pending.amount() < 1 || pending.ids().isEmpty()) {
                return;
            }
            exec.entity(p, () -> {
                if (!p.isOnline()) {
                    return;
                }
                cfgLoad.msg().send(p, "offline-sales", Map.of("amount", pending.amount()));
                repo.markDelivered(pending.ids()).exceptionally(ex -> {
                    log.log(Level.WARNING, "Не удалось отметить уведомления доставленными", ex);
                    return null;
                });
            });
        }).exceptionally(ex -> {
            log.log(Level.WARNING, "Не удалось загрузить отложенные уведомления", ex);
            return null;
        });
    }

    private void notifySeller(SaleLog sale, UUID txId) {
        Player seller = Bukkit.getPlayer(sale.sellerId());
        if (seller == null) {
            saveOffline(sale.sellerId(), sale.amount(), txId);
            return;
        }
        exec.entity(seller, () -> {
            if (!seller.isOnline()) {
                saveOffline(sale.sellerId(), sale.amount(), txId);
                return;
            }
            Component item = Component.translatable(sale.material().translationKey());
            cfgLoad.msg().send(
                    seller,
                    "sale-chat",
                    Map.of("shop", sale.shopName(), "items", sale.items(), "amount", sale.amount()),
                    Map.of("item", item)
            );
            sounds.sellerSale(seller);
        }, () -> saveOffline(sale.sellerId(), sale.amount(), txId));
    }

    private void saveOffline(UUID playerId, long amount, UUID txId) {
        if (!cfgLoad.cfg().offlineNotify()) {
            return;
        }
        repo.add(playerId, amount, txId).exceptionally(ex -> {
            log.log(Level.WARNING, "Не удалось сохранить отложенное уведомление", ex);
            return null;
        });
    }
}
