package ru.javaroot.integration.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import ru.javaroot.config.LoggerLoc;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.data.LinkStore;
import ru.javaroot.integration.ExternalMsg;
import ru.javaroot.integration.SaleLog;
import ru.javaroot.integration.BoundedHttp;
import ru.javaroot.scheduler.Sched;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class TelegramBot {
    private final Logger logger;
    private final LinkStore links;
    private final ExternalMsg messages;
    private final OtherMsg other;
    private final LoggerLoc loggerLoc;
    private final BoundedHttp http;
    private final int pollSeconds;
    private final String api;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private long offset;
    private ScheduledTask task;
    private boolean warned;
    private int failures;
    private long nextPollAt;

    public TelegramBot(Logger logger, LinkStore links, ExternalMsg messages, OtherMsg other, LoggerLoc loggerLoc,
                       String token, int pollSeconds, BoundedHttp http) {
        this.logger = logger;
        this.links = links;
        this.messages = messages;
        this.other = other;
        this.loggerLoc = loggerLoc;
        this.pollSeconds = Math.max(1, pollSeconds);
        this.api = "https://api.telegram.org/bot" + token;
        this.http = http;
    }

    public void start(Sched sched) {
        long currentGeneration = generation.incrementAndGet();
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/deleteWebhook?drop_pending_updates=false"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        http.send(request);
        task = sched.asyncRepeating(pollSeconds, pollSeconds, java.util.concurrent.TimeUnit.SECONDS,
                ignored -> poll(currentGeneration));
    }

    public void stop() {
        generation.incrementAndGet();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void sale(SaleLog sale) {
        links.externalId(sale.sellerId(), "telegram").thenAccept(chatId -> {
            if (chatId == null) {
                return;
            }
            String text = messages.text("sale", Map.of(
                    "shop", sale.shopName(),
                    "item", loggerLoc.name(sale.material()),
                    "items", sale.items(),
                    "amount", sale.amount()
            ));
            sendMessage(chatId, text);
        });
    }

    private void poll(long currentGeneration) {
        if (currentGeneration != generation.get() || System.currentTimeMillis() < nextPollAt
                || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/getUpdates?timeout=1&offset=" + offset))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            BoundedHttp.Response response = http.send(request).join();
            if (currentGeneration != generation.get()) {
                return;
            }
            if (response.statusCode() / 100 != 2) {
                warn(other.raw("telegram-http-error", Map.of("status", response.statusCode())));
                return;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.get("ok").getAsBoolean()) {
                warn(other.raw("telegram-api-error"));
                return;
            }
            warned = false;
            failures = 0;
            JsonArray updates = root.getAsJsonArray("result");
            for (JsonElement element : updates) {
                JsonObject update = element.getAsJsonObject();
                offset = update.get("update_id").getAsLong() + 1;
                if (currentGeneration == generation.get()) {
                    handle(update);
                }
            }
        } catch (Exception exception) {
            failures = Math.min(failures + 1, 6);
            nextPollAt = System.currentTimeMillis() + (1L << failures) * 1000L;
            warn(other.raw("telegram-poll-error", Map.of("error", "request failed")));
        } finally {
            polling.set(false);
        }
    }

    private void handle(JsonObject update) {
        JsonObject message = update.has("message") ? update.getAsJsonObject("message") : null;
        if (message == null || !message.has("text") || !message.has("chat")) {
            return;
        }
        String text = message.get("text").getAsString().trim();
        String[] parts = text.split("\\s+", 2);
        String chatId = message.getAsJsonObject("chat").get("id").getAsString();
        String name = "";
        if (message.has("from")) {
            JsonObject from = message.getAsJsonObject("from");
            if (from.has("username")) {
                name = from.get("username").getAsString();
            } else if (from.has("first_name")) {
                name = from.get("first_name").getAsString();
            }
        }
        String command = parts[0].split("@", 2)[0];
        if (!command.equalsIgnoreCase("/code") && !command.equalsIgnoreCase("/start")) {
            sendMessage(chatId, messages.text("usage", Map.of()));
            return;
        }
        if (parts.length != 2 || !parts[1].matches("\\d{16}")) {
            sendMessage(chatId, messages.text("invalid-code", Map.of()));
            return;
        }
        links.consume(parts[1], "telegram", chatId, name).whenComplete((result, error) -> {
            if (error != null) {
                logger.warning(other.raw("telegram-link-error", Map.of("error", String.valueOf(error.getMessage()))));
                sendMessage(chatId, messages.text("invalid-code", Map.of()));
                return;
            }
            sendMessage(chatId, result == null
                    ? messages.text("invalid-code", Map.of())
                    : messages.text("linked", Map.of()));
        });
    }

    private CompletableFuture<Void> sendMessage(String chatId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + "/sendMessage"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(request).thenAccept(response -> {
            if (response.statusCode() / 100 != 2) {
                warn(other.raw("telegram-message-http-error", Map.of("status", response.statusCode())));
            }
        }).exceptionally(error -> {
            warn(other.raw("telegram-send-error", Map.of("error", "request failed")));
            return null;
        });
    }

    private void warn(String message) {
        if (!warned) {
            warned = true;
            logger.warning(message);
        }
    }
}
