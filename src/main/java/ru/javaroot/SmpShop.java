package ru.javaroot;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import java.util.concurrent.CompletableFuture;
import ru.javaroot.command.ShopCmd;
import ru.javaroot.api.SmpShopApi;
import ru.javaroot.api.SmpShopApiImpl;
import ru.javaroot.command.ShopTab;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.DbLoc;
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
import ru.javaroot.logersxd.Loggers;
import ru.javaroot.notification.NotifySvc;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;
import ru.javaroot.shop.ShopMgr;

public class SmpShop extends JavaPlugin {
    private Cfg cfg;
    private Msg msg;
    private OtherMsg other;
    private Loggers logs;
    private DbLoc dbLoc;
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
    private volatile boolean stopping;
    private long lifecycleGeneration;
    private CompletableFuture<Void> startup;

    @Override
    public void onEnable() {
        stopping = false;
        long generation = ++lifecycleGeneration;
        saveDefaultConfig();
        logs = new Loggers(this);
        other = new OtherMsg(this, logs.config());
        msg = new Msg(this, other, logs.config());
        cfg = new Cfg(this, other);
        sched = new Sched(this);
        dbLoc = new DbLoc(this);
        db = new Sqlite(this, cfg.sqliteFile(), cfg.transactionTimeoutSeconds(), cfg.shutdownTimeoutSeconds(), dbLoc);
        shopStore = new ShopStore(db);
        balanceStore = new BalanceStore(db);
        linkStore = new LinkStore(db);
        notificationStore = new NotificationStore(db);
        webhookStore = new WebhookStore(db);
        shops = new ShopMgr(other);
        itemNames = new ItemName();
        sounds = new SoundSvc(logs.sound(), cfg, other, sched);
        notifications = new NotifySvc(logs, cfg, msg, other, dbLoc, notificationStore, sounds, itemNames, sched);
        integrations = new IntegrationHub(logs, linkStore, webhookStore, cfg, other, dbLoc,
                new ExternalMsg(this, "message_tg.yml", other),
                new ExternalMsg(this, "message_ds.yml", other));
        balanceGui = new BalanceInv(this, balanceStore, sched, msg, other, logs.gui());
        listGui = new ShopListInv(this, shops, cfg, msg, sched);
        trade = new ShopTrade(this, shops, shopStore, balanceStore, integrations, notifications, sounds,
                msg, itemNames, sched, logs, other);
        api = new SmpShopApiImpl(this, shops, shopStore, balanceStore, balanceGui, listGui, trade, sched, cfg, other);
        getServer().getServicesManager().register(SmpShopApi.class, api, this, ServicePriority.Normal);

        ShopCmd shopCmd = new ShopCmd(this, cfg, msg, other, logs.config(), shops, sched,
                integrations, sounds, balanceGui, listGui);
        PluginCommand command = getCommand("smpshop");
        if (command == null) {
            throw new IllegalStateException(other.raw("command-not-registered"));
        }
        command.setExecutor(shopCmd);
        command.setTabCompleter(new ShopTab());

        getServer().getPluginManager().registerEvents(new ShopInteract(this, shopCmd, msg, cfg,
                dbLoc, shopStore, shops, sched, trade, logs.shop()), this);
        ShopProtect shopProtect = new ShopProtect(shops, sched, logs.shop(), other);
        getServer().getPluginManager().registerEvents(shopProtect, this);
        getServer().getPluginManager().registerEvents(balanceGui, this);
        getServer().getPluginManager().registerEvents(listGui, this);
        getServer().getPluginManager().registerEvents(trade, this);
        getServer().getPluginManager().registerEvents(notifications, this);

        startup = db.start()
                .thenCompose(ignored -> shopStore.loadAll()
                        .thenCombine(webhookStore.loadAll(), StartupData::new))
                .thenCompose(data -> {
                    if (!isCurrent(generation)) {
                        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                                other.raw("startup-superseded")));
                    }
                    CompletableFuture<Void> loaded = new CompletableFuture<>();
                    if (sched.global(() -> {
                        try {
                            if (!isCurrent(generation)) {
                                loaded.completeExceptionally(new java.util.concurrent.CancellationException(
                                        other.raw("startup-superseded")));
                                return;
                            }
                            shops.load(data.shops());
                            integrations.loadWebhooks(data.webhooks());
                            shopProtect.audit(data.shops());
                            trade.recover().whenComplete((ignoredRecovery, error) -> {
                                if (error == null) {
                                    loaded.complete(null);
                                } else {
                                    loaded.completeExceptionally(error);
                                }
                            });
                        } catch (RuntimeException exception) {
                            loaded.completeExceptionally(exception);
                        }
                    }) != Sched.ScheduleResult.SCHEDULED) {
                        loaded.completeExceptionally(new IllegalStateException(other.raw("scheduler-unavailable")));
                    }
                    return loaded;
                })
                .whenComplete((ignored, error) -> {
                    if (!isCurrent(generation)) {
                        return;
                    }
                    if (error != null) {
                        ready = false;
                        api.markFailed(error);
                        logs.lifecycle().severe(other.raw("database-startup-failed"), error);
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
        stopping = true;
        lifecycleGeneration++;
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
                balanceGui.closeAll().get(cfg.shutdownTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logs.lifecycle().severe(other.raw("balance-save-interrupted"), exception);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                logs.lifecycle().severe(other.raw("balance-save-failed"), exception);
            }
        }
        if (listGui != null) {
            listGui.closeAll();
        }
        if (trade != null) {
            try {
                trade.closeAll().get(cfg.shutdownTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logs.lifecycle().severe(other.raw("shop-save-interrupted"), exception);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                logs.lifecycle().severe(other.raw("shop-sessions-save-failed"), exception);
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

    private boolean isCurrent(long generation) {
        return !stopping && generation == lifecycleGeneration && isEnabled();
    }

    private record StartupData(java.util.List<ru.javaroot.shop.Shop> shops,
                               java.util.Map<java.util.UUID, String> webhooks) {
    }
}
