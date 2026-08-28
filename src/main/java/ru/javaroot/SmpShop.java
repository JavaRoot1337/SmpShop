package ru.javaroot;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import java.util.logging.Level;
import ru.javaroot.command.ShopCmd;
import ru.javaroot.api.SmpShopApi;
import ru.javaroot.api.SmpShopApiImpl;
import ru.javaroot.command.ShopTab;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.LoggerLoc;
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
    private SmpShopApiImpl api;
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
        LoggerLoc loggerLoc = new LoggerLoc(this, other);
        shops = new ShopMgr();
        itemNames = new ItemName();
        sounds = new SoundSvc(getLogger(), cfg, other, sched);
        notifications = new NotifySvc(getLogger(), cfg, msg, other, notificationStore, sounds, itemNames, sched);
        integrations = new IntegrationHub(getLogger(), linkStore, webhookStore, cfg, other, loggerLoc,
                new ExternalMsg(this, "message_tg.yml", other),
                new ExternalMsg(this, "message_ds.yml", other));
        balanceGui = new BalanceInv(this, balanceStore, sched, msg);
        listGui = new ShopListInv(this, shops, cfg, msg, sched);
        trade = new ShopTrade(this, shops, shopStore, balanceStore, integrations, notifications, sounds,
                msg, itemNames, sched, getLogger());
        api = new SmpShopApiImpl(this, shops, shopStore, balanceStore, balanceGui, listGui, trade, sched, cfg);
        getServer().getServicesManager().register(SmpShopApi.class, api, this, ServicePriority.Normal);

        ShopCmd shopCmd = new ShopCmd(this, cfg, msg, other, shops, sched, integrations, sounds, balanceGui, listGui);
        PluginCommand command = getCommand("smpshop");
        if (command == null) {
            throw new IllegalStateException(other.raw("command-not-registered"));
        }
        command.setExecutor(shopCmd);
        command.setTabCompleter(new ShopTab());

        getServer().getPluginManager().registerEvents(new ShopInteract(this, shopCmd, msg, cfg,
                shopStore, shops, sched, trade), this);
        ShopProtect shopProtect = new ShopProtect(shops, sched, getLogger());
        getServer().getPluginManager().registerEvents(shopProtect, this);
        getServer().getPluginManager().registerEvents(balanceGui, this);
        getServer().getPluginManager().registerEvents(listGui, this);
        getServer().getPluginManager().registerEvents(trade, this);
        getServer().getPluginManager().registerEvents(notifications, this);

        db.start()
                .thenCompose(ignored -> shopStore.loadAll()
                        .thenCombine(webhookStore.loadAll(), StartupData::new))
                .thenCompose(data -> {
                    shops.load(data.shops());
                    integrations.loadWebhooks(data.webhooks());
                    shopProtect.clearShopNames(data.shops());
                    shopProtect.audit(data.shops());
                    return trade.recover();
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ready = false;
                        api.markFailed(error);
                        getLogger().log(Level.SEVERE, "Не удалось загрузить SQLite", error);
                        notifications.stop();
                        integrations.stop();
                        db.close();
                        sched.global(() -> getServer().getPluginManager().disablePlugin(this));
                        return;
                    }
                    notifications.start();
                    trade.start();
                    ready = true;
                    api.markReady();
                    integrations.start(sched);
                });
    }

    @Override
    public void onDisable() {
        ready = false;
        if (api != null) {
            api.close();
            getServer().getServicesManager().unregister(SmpShopApi.class, api);
        }
        if (trade != null) {
            trade.stopInput();
        }
        if (integrations != null) {
            integrations.stop();
        }
        if (notifications != null) {
            notifications.stop();
        }
        if (balanceGui != null) {
            try {
                balanceGui.closeAll().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                getLogger().log(Level.SEVERE, "Сохранение balance-сессий прервано", exception);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                getLogger().log(Level.SEVERE, "Не удалось сохранить balance-сессии", exception);
            }
        }
        if (trade != null) {
            try {
                trade.closeAll().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                getLogger().log(Level.SEVERE, "Сохранение shop-сессий прервано", exception);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                getLogger().log(Level.SEVERE, "Не удалось сохранить shop-сессии", exception);
            }
        }
        if (db != null) {
            db.close();
        }
        if (api != null) {
            api.stopped();
        }
    }

    public boolean isReady() {
        return ready;
    }

    private record StartupData(java.util.List<ru.javaroot.shop.Shop> shops,
                               java.util.Map<java.util.UUID, String> webhooks) {
    }
}
