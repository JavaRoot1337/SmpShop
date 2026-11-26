package ru.javaroot.smpshop;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.api.ApiProvider;
import ru.javaroot.api.SmpShopApi;
import ru.javaroot.smpshop.api.ApiSvc;
import ru.javaroot.smpshop.cfg.Cfg;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.cmd.ShopCmd;
import ru.javaroot.smpshop.db.BalRepo;
import ru.javaroot.smpshop.db.Db;
import ru.javaroot.smpshop.db.LinkRepo;
import ru.javaroot.smpshop.db.NotifyRepo;
import ru.javaroot.smpshop.db.ShopRepo;
import ru.javaroot.smpshop.db.TxRepo;
import ru.javaroot.smpshop.db.WebhookRepo;
import ru.javaroot.smpshop.gui.BalGui;
import ru.javaroot.smpshop.gui.ListGui;
import ru.javaroot.smpshop.hook.HookHub;
import ru.javaroot.smpshop.list.InteractList;
import ru.javaroot.smpshop.list.ProtectList;
import ru.javaroot.smpshop.list.TradeList;
import ru.javaroot.smpshop.notify.NotifySvc;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.plat.SoundSvc;
import ru.javaroot.smpshop.shop.PendingAct;
import ru.javaroot.smpshop.shop.Shop;
import ru.javaroot.smpshop.shop.ShopMgr;
import ru.javaroot.smpshop.trade.TradeSvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class SmpShop extends JavaPlugin {
    private CfgLoad cfgLoad;
    private Exec exec;
    private Db db;
    private HookHub hooks;
    private NotifySvc notify;
    private SoundSvc sounds;
    private BalGui balGui;
    private ListGui listGui;
    private TradeSvc trade;
    private ApiSvc api;
    private volatile boolean ready;
    private volatile boolean stopping;
    private long generation;

    @Override
    public void onEnable() {
        stopping = false;
        long gen = ++generation;
        PluginManager pm = getServer().getPluginManager();
        registerPermissions(pm);

        cfgLoad = new CfgLoad(this);
        try {
            cfgLoad.load();
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Ошибка конфигурации при запуске", ex);
            pm.disablePlugin(this);
            return;
        }

        Cfg cfg = cfgLoad.cfg();
        exec = new Exec(this);
        db = new Db(this, cfg.sqliteFile(), cfg.txTimeoutSec(), cfg.shutdownTimeoutSec());
        ShopRepo shopRepo = new ShopRepo(db);
        BalRepo balRepo = new BalRepo(db);
        TxRepo txRepo = new TxRepo(db);
        LinkRepo linkRepo = new LinkRepo(db);
        NotifyRepo notifyRepo = new NotifyRepo(db);
        WebhookRepo webhookRepo = new WebhookRepo(db);

        ShopMgr shops = new ShopMgr();
        PendingAct pending = new PendingAct();
        sounds = new SoundSvc(getLogger(), cfgLoad::cfg, exec);
        notify = new NotifySvc(getLogger(), cfgLoad, notifyRepo, sounds, exec);
        hooks = new HookHub(getLogger(), linkRepo, webhookRepo, cfgLoad);
        balGui = new BalGui(getLogger(), balRepo, exec, cfgLoad);
        listGui = new ListGui(this, shops, cfgLoad, exec);
        trade = new TradeSvc(this, shops, txRepo, balRepo, hooks, notify, sounds, cfgLoad, exec);
        api = new ApiSvc(shops, shopRepo, balRepo, balGui, listGui, trade, exec, cfgLoad);

        getServer().getServicesManager().register(SmpShopApi.class, api, this, ServicePriority.Normal);
        ApiProvider.register(api);

        ProtectList protectList = new ProtectList(getLogger(), shops, exec);
        pm.registerEvents(new InteractList(getLogger(), pending, cfgLoad, shopRepo, shops, exec, trade), this);
        pm.registerEvents(protectList, this);
        pm.registerEvents(balGui, this);
        pm.registerEvents(listGui, this);
        pm.registerEvents(new TradeList(trade), this);
        pm.registerEvents(notify, this);

        ShopCmd cmd = new ShopCmd(getLogger(), cfgLoad, shops, pending, exec, hooks, balGui, listGui,
                () -> ready, this::reloadAll);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> cmd.register(e.registrar()));

        startStorage(gen, shopRepo, webhookRepo, shops, protectList);
        getLogger().info("SmpShop включён");
    }

    @Override
    public void onDisable() {
        stopping = true;
        generation++;
        ready = false;
        if (api != null) {
            api.close();
            ApiProvider.unregister();
            getServer().getServicesManager().unregisterAll(this);
        }
        if (trade != null) {
            trade.stopInput();
        }
        if (hooks != null) {
            hooks.stop();
        }
        if (notify != null) {
            notify.stop();
        }
        int timeout = cfgLoad != null && cfgLoad.cfg() != null ? cfgLoad.cfg().shutdownTimeoutSec() : 10;
        awaitShutdown(balGui == null ? null : balGui.closeAll(), timeout, "баланс-сессий");
        if (listGui != null) {
            listGui.closeAll();
        }
        awaitShutdown(trade == null ? null : trade.closeAll(), timeout, "торговых сессий");
        if (exec != null) {
            exec.cancelAll();
        }
        if (db != null) {
            db.close();
        }
        if (api != null) {
            api.stopped();
        }
        getLogger().info("SmpShop выключен");
    }

    private void startStorage(long gen, ShopRepo shopRepo, WebhookRepo webhookRepo,
                              ShopMgr shops, ProtectList protectList) {
        db.start()
                .thenCompose(ignored -> shopRepo.loadAll().thenCombine(webhookRepo.loadAll(), StartupData::new))
                .thenCompose(data -> {
                    if (!isCurrent(gen)) {
                        return CompletableFuture.failedFuture(new CancellationException("Запуск отменён"));
                    }
                    CompletableFuture<Void> loaded = new CompletableFuture<>();
                    if (exec.global(() -> {
                        try {
                            if (!isCurrent(gen)) {
                                loaded.completeExceptionally(new CancellationException("Запуск отменён"));
                                return;
                            }
                            shops.load(data.shops());
                            hooks.loadWebhooks(data.webhooks());
                            protectList.audit(data.shops());
                            trade.recover().whenComplete((ignored, err) -> {
                                if (err == null) {
                                    loaded.complete(null);
                                } else {
                                    loaded.completeExceptionally(err);
                                }
                            });
                        } catch (RuntimeException ex) {
                            loaded.completeExceptionally(ex);
                        }
                    }) != Exec.Result.SCHEDULED) {
                        loaded.completeExceptionally(new IllegalStateException("Планировщик недоступен"));
                    }
                    return loaded;
                })
                .whenComplete((ignored, err) -> {
                    if (!isCurrent(gen)) {
                        return;
                    }
                    if (err != null) {
                        ready = false;
                        api.markFailed(err);
                        getLogger().log(Level.SEVERE, "Не удалось запустить базу данных SmpShop", err);
                        notify.stop();
                        hooks.stop();
                        db.close();
                        exec.global(() -> getServer().getPluginManager().disablePlugin(this));
                        return;
                    }
                    notify.start();
                    trade.start();
                    ready = true;
                    api.markReady();
                    hooks.start(exec);
                });
    }

    private long reloadAll() {
        long ms = cfgLoad.load();
        sounds.reload();
        hooks.reload(exec);
        return ms;
    }

    private void awaitShutdown(CompletableFuture<Void> future, int timeoutSec, String label) {
        if (future == null) {
            return;
        }
        try {
            future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.SEVERE, "Прервано сохранение " + label, ex);
        } catch (ExecutionException | TimeoutException ex) {
            getLogger().log(Level.SEVERE, "Не удалось сохранить " + label, ex);
        }
    }

    private boolean isCurrent(long gen) {
        return !stopping && gen == generation && isEnabled();
    }

    private static void registerPermissions(PluginManager pm) {
        regPerm(pm, ShopCmd.PERM_USE, "Использование команды /smpshop", PermissionDefault.TRUE);
        regPerm(pm, ShopCmd.PERM_BALANCE, "Открытие меню баланса", PermissionDefault.TRUE);
        regPerm(pm, ShopCmd.PERM_LINK, "Привязка Telegram и Discord", PermissionDefault.TRUE);
        regPerm(pm, ShopCmd.PERM_PLUS, "Увеличенный лимит магазинов", PermissionDefault.OP);
        regPerm(pm, ShopCmd.PERM_ADMIN_LIST, "Просмотр магазинов другого игрока", PermissionDefault.OP);
        regPerm(pm, ShopCmd.PERM_ADMIN_REMOVE, "Удаление любого магазина", PermissionDefault.OP);
        regPerm(pm, ShopCmd.PERM_ADMIN_RELOAD, "Перезагрузка конфигурации", PermissionDefault.OP);
    }

    private static void regPerm(PluginManager pm, String name, String desc, PermissionDefault def) {
        if (pm.getPermission(name) == null) {
            pm.addPermission(new Permission(name, desc, def));
        }
    }

    private record StartupData(List<Shop> shops, Map<UUID, String> webhooks) {
    }
}
