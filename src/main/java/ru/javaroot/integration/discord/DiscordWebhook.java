package ru.javaroot.integration.discord;

import com.google.gson.JsonObject;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.integration.ExternalMsg;
import ru.javaroot.integration.SaleLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class DiscordWebhook {
    private final Logger logger;
    private final String webhook;
    private final ExternalMsg messages;
    private final OtherMsg other;
    private final HttpClient http;

    public DiscordWebhook(Logger logger, String webhook, ExternalMsg messages, OtherMsg other) {
        this.logger = logger;
        this.webhook = webhook;
        this.messages = messages;
        this.other = other;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
                "item", sale.item(),
                "items", sale.items(),
                "amount", sale.amount()
        )));
    }

    private CompletableFuture<Boolean> send(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        logger.warning(other.raw("discord-http-error", Map.of("status", response.statusCode())));
                        return false;
                    }
                    return true;
                })
                .exceptionally(error -> {
                    logger.warning(other.raw("discord-send-error", Map.of("error", String.valueOf(error.getMessage()))));
                    return false;
                });
    }
}
