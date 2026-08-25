package ru.javaroot;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Level;
import ru.javaroot.command.ShopCmd;
import ru.javaroot.command.ShopTab;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.Msg;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.data.BalanceStore;
import ru.javaroot.data.LinkStore;
import ru.javaroot.data.NotificationStore;
import ru.javaroot.data.ShopStore;
import ru.javaroot.data.Sqlite;
import ru.javaroot.data.WebhookStore;
import ru.javaroot.gui.BalanceInv;
import ru.javaroot.gui.ShopListInv;
import ru.javaroot.integration.ExternalMsg;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.item.ItemName;
import ru.javaroot.list.ShopInteract;
import ru.javaroot.list.ShopProtect;
import ru.javaroot.list.ShopTrade;
import ru.javaroot.notification.NotifySvc;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;
import ru.javaroot.shop.ShopMgr;

public class SmpShop extends JavaPlugin {
    private Cfg cfg;
    private Msg msg;
    private OtherMsg other;
    private Sched sched;
    private Sqlite db;
    private ShopStore shopStore;
    private BalanceStore balanceStore;
    private LinkStore linkStore;
    private NotificationStore notificationStore;
    private WebhookStore webhookStore;
    private IntegrationHub integrations;
    private BalanceInv balanceGui;
    private ShopListInv listGui;
    private ShopTrade trade;
    private SoundSvc sounds;
    private NotifySvc notifications;
    private ShopMgr shops;
    private ItemName itemNames;
    private volatile boolean ready;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        other = new OtherMsg(this);
        msg = new Msg(this, other);
        cfg = new Cfg(this, other);
        sched = new Sched(this);
        db = new Sqlite(this, cfg.sqliteFile(), cfg.transactionTimeoutSeconds());
        shopStore = new ShopStore(db);
        balanceStore = new BalanceStore(db);
        linkStore = new LinkStore(db);
        notificationStore = new NotificationStore(db);
        webhookStore = new WebhookStore(db);
        shops = new ShopMgr();
        itemNames = new ItemName();
        sounds = new SoundSvc(getLogger(), cfg, other, sched);
        notifications = new NotifySvc(getLogger(), cfg, msg, other, notificationStore, sounds, itemNames, sched);
        integrations = new IntegrationHub(getLogger(), linkStore, webhookStore, cfg, other,
                new ExternalMsg(this, "message_tg.yml", other),
                new ExternalMsg(this, "message_ds.yml", other));
        balanceGui = new BalanceInv(this, balanceStore, sched, msg);
        listGui = new ShopListInv(this, shops, cfg, msg, sched);
        trade = new ShopTrade(shops, shopStore, balanceStore, integrations, notifications, sounds, msg, itemNames, sched, getLogger());

        ShopCmd shopCmd = new ShopCmd(this, cfg, msg, shops, sched, integrations, sounds, balanceGui, listGui);
        PluginCommand command = getCommand("smpshop");
        if (command == null) {
            throw new IllegalStateException(other.raw("command-not-registered"));
        }
        command.setExecutor(shopCmd);
        command.setTabCompleter(new ShopTab());

        getServer().getPluginManager().registerEvents(new ShopInteract(this, shopCmd, msg, shopStore, shops, sched), this);
        ShopProtect shopProtect = new ShopProtect(shops, sched);
        getServer().getPluginManager().registerEvents(shopProtect, this);
        getServer().getPluginManager().registerEvents(balanceGui, this);
        getServer().getPluginManager().registerEvents(listGui, this);
        getServer().getPluginManager().registerEvents(trade, this);
        getServer().getPluginManager().registerEvents(notifications, this);

        db.start()
                .thenCompose(ignored -> shopStore.loadAll()
                        .thenCombine(webhookStore.loadAll(), StartupData::new))
                .thenAccept(data -> {
                    shops.load(data.shops());
                    integrations.loadWebhooks(data.webhooks());
                    shopProtect.clearShopNames(data.shops());
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ready = false;
                        getLogger().log(Level.SEVERE, "Не удалось загрузить SQLite", error);
                        notifications.stop();
                        integrations.stop();
                        db.close();
                        return;
                    }
                    notifications.start();
                    ready = true;
                    integrations.start(sched);
                });
    }

    @Override
    public void onDisable() {
        ready = false;
        if (notifications != null) {
            notifications.stop();
        }
        if (balanceGui != null) {
            try {
                balanceGui.closeAll().join();
            } catch (java.util.concurrent.CompletionException exception) {
                getLogger().log(Level.SEVERE, "Не удалось сохранить balance-сессии", exception.getCause());
            }
        }
        if (trade != null) {
            trade.closeAll();
        }
        if (integrations != null) {
            integrations.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    public boolean isReady() {
        return ready;
    }

    private record StartupData(java.util.List<ru.javaroot.shop.Shop> shops,
                               java.util.Map<java.util.UUID, String> webhooks) {
    }
}
