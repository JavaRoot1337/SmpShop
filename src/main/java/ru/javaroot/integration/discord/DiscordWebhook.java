package ru.javaroot.integration.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import ru.javaroot.config.DbLoc;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.integration.ExternalMsg;
import ru.javaroot.integration.SaleLog;
import ru.javaroot.integration.BoundedHttp;
import ru.javaroot.logersxd.Loggers;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class DiscordWebhook {
    private final Loggers.Channel logger;
    private final String webhook;
    private final ExternalMsg messages;
    private final OtherMsg other;
    private final DbLoc dbLoc;
    private final int requestTimeoutSeconds;
    private final BoundedHttp http;

    public DiscordWebhook(Logger logger, String webhook, ExternalMsg messages, OtherMsg other,
                          DbLoc dbLoc, int requestTimeoutSeconds, BoundedHttp http) {
        this(new Loggers(logger).integrations(), webhook, messages, other, dbLoc, requestTimeoutSeconds, http);
    }

    public DiscordWebhook(Loggers.Channel logger, String webhook, ExternalMsg messages, OtherMsg other,
                          DbLoc dbLoc, int requestTimeoutSeconds, BoundedHttp http) {
        this.logger = logger;
        this.webhook = webhook;
        this.messages = messages;
        this.other = other;
        this.dbLoc = dbLoc;
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
        this.http = http;
    }

    public static boolean isValid(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("discord.com")
                    || host.endsWith(".discord.com")
                    || host.equalsIgnoreCase("discordapp.com"))
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/api/webhooks/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public CompletableFuture<Boolean> sale(SaleLog sale) {
        return send(messages.text("sale", Map.of(
                "shop", sale.shopName(),
                "item", dbLoc.itemName(sale.material()),
                "items", sale.items(),
                "amount", sale.amount()
        )));
    }

    private CompletableFuture<Boolean> send(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(request)
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        logger.warning(other.raw("discord-http-error", Map.of("status", response.statusCode())));
                        return false;
                    }
                    return true;
                })
                .exceptionally(error -> {
                    logger.warning(other.raw("discord-send-error", Map.of("error", other.raw("request-failed"))));
                    return false;
                });
    }
}
