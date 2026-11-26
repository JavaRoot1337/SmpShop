package ru.javaroot.smpshop.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.cfg.Msg;
import ru.javaroot.smpshop.shop.SaleLog;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

final class DsHook {
    private final Logger log;
    private final String webhook;
    private final CfgLoad cfgLoad;
    private final int timeoutSec;
    private final HttpCli http;

    DsHook(Logger log, String webhook, CfgLoad cfgLoad, int timeoutSec, HttpCli http) {
        this.log = log;
        this.webhook = webhook;
        this.cfgLoad = cfgLoad;
        this.timeoutSec = Math.max(1, timeoutSec);
        this.http = http;
    }

    static boolean isValid(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("discord.com")
                    || host.endsWith(".discord.com")
                    || host.equalsIgnoreCase("discordapp.com"))
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/api/webhooks/");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    void sale(SaleLog sale) {
        Msg m = cfgLoad.msg();
        String content = m.raw("discord.sale", Map.of(
                "shop", sale.shopName(),
                "item", m.itemName(sale.material()),
                "items", sale.items(),
                "amount", sale.amount()
        ));
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);

        HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        http.send(req).thenAccept(resp -> {
            if (resp.status() / 100 != 2) {
                log.warning("Discord webhook вернул HTTP " + resp.status());
            }
        }).exceptionally(err -> {
            log.warning("Не удалось отправить уведомление в Discord: " + err.getMessage());
            return null;
        });
    }
}
