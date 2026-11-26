package ru.javaroot.smpshop.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.cfg.Msg;
import ru.javaroot.smpshop.db.LinkRepo;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.SaleLog;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class TgBot {
    private final Logger log;
    private final LinkRepo links;
    private final CfgLoad cfgLoad;
    private final HttpCli http;
    private final String apiBase;
    private final int pollSec;
    private final int longPollSec;
    private final int reqTimeoutSec;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong gen = new AtomicLong();
    private volatile long offset;
    private ScheduledTask task;
    private volatile boolean warned;
    private volatile int failures;
    private volatile long nextPollAt;

    TgBot(Logger log, LinkRepo links, CfgLoad cfgLoad, HttpCli http,
          String token, String apiUrl, int pollSec, int longPollSec, int reqTimeoutSec) {
        this.log = log;
        this.links = links;
        this.cfgLoad = cfgLoad;
        this.http = http;
        this.apiBase = apiUrl + token;
        this.pollSec = Math.max(1, pollSec);
        this.longPollSec = Math.max(0, longPollSec);
        this.reqTimeoutSec = Math.max(1, reqTimeoutSec);
    }

    void start(Exec exec) {
        long curGen = gen.incrementAndGet();
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/deleteWebhook?drop_pending_updates=false"))
                .timeout(Duration.ofSeconds(reqTimeoutSec))
                .GET()
                .build();
        http.send(req);
        task = exec.asyncRepeating(pollSec, pollSec, TimeUnit.SECONDS, ignored -> poll(curGen));
    }

    void stop() {
        gen.incrementAndGet();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void sale(SaleLog sale) {
        links.externalId(sale.sellerId(), "telegram").thenAccept(chatId -> {
            if (chatId == null) {
                return;
            }
            Msg m = cfgLoad.msg();
            String text = m.raw("telegram.sale", Map.of(
                    "shop", sale.shopName(),
                    "item", m.itemName(sale.material()),
                    "items", sale.items(),
                    "amount", sale.amount()
            ));
            sendMessage(chatId, text);
        });
    }

    private void poll(long curGen) {
        if (curGen != gen.get() || System.currentTimeMillis() < nextPollAt || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(apiBase + "/getUpdates?timeout=" + longPollSec + "&offset=" + offset))
                    .timeout(Duration.ofSeconds(reqTimeoutSec))
                    .GET()
                    .build();
            http.send(req).whenComplete((resp, err) -> finishPoll(curGen, resp, err));
        } catch (RuntimeException ex) {
            finishPoll(curGen, null, ex);
        }
    }

    private void finishPoll(long curGen, HttpCli.Resp resp, Throwable err) {
        try {
            if (err != null) {
                warn("Ошибка опроса Telegram: " + err.getMessage());
                backoff();
                return;
            }
            if (curGen != gen.get()) {
                return;
            }
            if (resp.status() / 100 != 2) {
                warn("Telegram вернул HTTP " + resp.status());
                backoff();
                return;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.get("ok").getAsBoolean()) {
                warn("Telegram Bot API вернул ошибку");
                backoff();
                return;
            }
            warned = false;
            failures = 0;
            JsonArray updates = root.getAsJsonArray("result");
            for (JsonElement el : updates) {
                JsonObject upd = el.getAsJsonObject();
                offset = upd.get("update_id").getAsLong() + 1;
                if (curGen == gen.get()) {
                    handle(upd);
                }
            }
        } catch (RuntimeException ex) {
            warn("Ошибка разбора ответа Telegram: " + ex.getMessage());
            backoff();
        } finally {
            polling.set(false);
        }
    }

    private void handle(JsonObject upd) {
        JsonObject msgObj = upd.has("message") ? upd.getAsJsonObject("message") : null;
        if (msgObj == null || !msgObj.has("text") || !msgObj.has("chat")) {
            return;
        }
        String text = msgObj.get("text").getAsString().trim();
        String[] parts = text.split("\\s+", 2);
        String chatId = msgObj.getAsJsonObject("chat").get("id").getAsString();
        String name = extractName(msgObj);
        String cmd = parts[0].split("@", 2)[0];
        Msg m = cfgLoad.msg();
        if (!cmd.equalsIgnoreCase("/code") && !cmd.equalsIgnoreCase("/start")) {
            sendMessage(chatId, m.raw("telegram.usage"));
            return;
        }
        if (parts.length != 2 || !parts[1].matches("\\d{16}")) {
            sendMessage(chatId, m.raw("telegram.invalid-code"));
            return;
        }
        links.consume(parts[1], "telegram", chatId, name).whenComplete((res, err) -> {
            Msg latest = cfgLoad.msg();
            if (err != null) {
                log.warning("Не удалось сохранить привязку Telegram: " + err.getMessage());
                sendMessage(chatId, latest.raw("telegram.invalid-code"));
                return;
            }
            sendMessage(chatId, res == null ? latest.raw("telegram.invalid-code") : latest.raw("telegram.linked"));
        });
    }

    private static String extractName(JsonObject msgObj) {
        if (!msgObj.has("from")) {
            return "";
        }
        JsonObject from = msgObj.getAsJsonObject("from");
        if (from.has("username")) {
            return from.get("username").getAsString();
        }
        if (from.has("first_name")) {
            return from.get("first_name").getAsString();
        }
        return "";
    }

    private void sendMessage(String chatId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/sendMessage"))
                .timeout(Duration.ofSeconds(reqTimeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        http.send(req).thenAccept(resp -> {
            if (resp.status() / 100 != 2) {
                warn("Telegram не принял сообщение: HTTP " + resp.status());
            }
        }).exceptionally(err -> {
            warn("Не удалось отправить сообщение в Telegram: " + err.getMessage());
            return null;
        });
    }

    private void backoff() {
        failures = Math.min(failures + 1, 6);
        nextPollAt = System.currentTimeMillis() + (1L << failures) * 1000L;
    }

    private void warn(String message) {
        if (!warned) {
            warned = true;
            log.warning(message);
        }
    }
}
