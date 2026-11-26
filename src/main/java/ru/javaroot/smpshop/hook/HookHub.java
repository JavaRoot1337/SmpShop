package ru.javaroot.smpshop.hook;

import ru.javaroot.smpshop.cfg.Cfg;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.db.LinkRepo;
import ru.javaroot.smpshop.db.WebhookRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.SaleLog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class HookHub {
    private final Logger log;
    private final LinkRepo links;
    private final WebhookRepo webhooks;
    private final CfgLoad cfgLoad;
    private final Map<UUID, String> webhookUrls = new ConcurrentHashMap<>();
    private final Map<UUID, DsHook> dsHooks = new ConcurrentHashMap<>();
    private volatile HttpCli http;
    private TgBot tg;

    public HookHub(Logger log, LinkRepo links, WebhookRepo webhooks, CfgLoad cfgLoad) {
        this.log = log;
        this.links = links;
        this.webhooks = webhooks;
        this.cfgLoad = cfgLoad;
        Cfg c = cfgLoad.cfg();
        this.http = new HttpCli(c.httpMaxConcurrent(), c.httpConnectTimeoutSec(), c.httpResponseLimitBytes());
    }

    public synchronized void start(Exec exec) {
        Cfg c = cfgLoad.cfg();
        if (!c.tgEnabled()) {
            return;
        }
        if (c.tgToken() == null || c.tgToken().isBlank()) {
            log.warning("Telegram включён, но integrations.telegram.bot-token пуст");
            return;
        }
        tg = new TgBot(log, links, cfgLoad, http, c.tgToken(), c.tgApiBaseUrl(),
                c.tgPollIntervalSec(), c.tgLongPollSec(), c.httpRequestTimeoutSec());
        tg.start(exec);
    }

    public void loadWebhooks(Map<UUID, String> saved) {
        dsHooks.clear();
        webhookUrls.clear();
        int timeout = cfgLoad.cfg().httpRequestTimeoutSec();
        saved.forEach((id, url) -> {
            if (DsHook.isValid(url)) {
                webhookUrls.put(id, url);
                dsHooks.put(id, new DsHook(log, url, cfgLoad, timeout, http));
            }
        });
    }

    public synchronized void stop() {
        if (tg != null) {
            tg.stop();
            tg = null;
        }
        dsHooks.clear();
        webhookUrls.clear();
        if (http != null) {
            http.close();
            http = null;
        }
    }

    public synchronized void reload(Exec exec) {
        if (tg != null) {
            tg.stop();
            tg = null;
        }
        if (http != null) {
            http.close();
        }
        Cfg c = cfgLoad.cfg();
        http = new HttpCli(c.httpMaxConcurrent(), c.httpConnectTimeoutSec(), c.httpResponseLimitBytes());
        dsHooks.clear();
        webhookUrls.forEach((id, url) -> dsHooks.put(id, new DsHook(log, url, cfgLoad, c.httpRequestTimeoutSec(), http)));
        start(exec);
    }

    public CompletableFuture<String> createTelegramCode(UUID playerId) {
        if (tg == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Telegram отключён"));
        }
        return links.createCode(playerId, "telegram", cfgLoad.cfg().tgCodeTimeoutSec());
    }

    public CompletableFuture<Boolean> saveDiscordWebhook(UUID playerId, String url) {
        Cfg c = cfgLoad.cfg();
        if (!c.dsEnabled() || !DsHook.isValid(url)) {
            return CompletableFuture.completedFuture(false);
        }
        return webhooks.save(playerId, url).thenApply(ignored -> {
            webhookUrls.put(playerId, url);
            dsHooks.put(playerId, new DsHook(log, url, cfgLoad, c.httpRequestTimeoutSec(), http));
            return true;
        });
    }

    public void sale(SaleLog sale) {
        if (tg != null) {
            tg.sale(sale);
        }
        if (cfgLoad.cfg().dsEnabled()) {
            DsHook hook = dsHooks.get(sale.sellerId());
            if (hook != null) {
                hook.sale(sale);
            }
        }
    }
}
