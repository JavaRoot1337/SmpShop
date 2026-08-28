package ru.javaroot.integration;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.LoggerLoc;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.data.LinkStore;
import ru.javaroot.data.WebhookStore;
import ru.javaroot.integration.discord.DiscordWebhook;
import ru.javaroot.integration.telegram.TelegramBot;
import ru.javaroot.scheduler.Sched;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class IntegrationHub {
    private final Logger logger;
    private final LinkStore links;
    private final WebhookStore webhooks;
    private final ExternalMsg telegramMessages;
    private final ExternalMsg discordMessages;
    private final Cfg cfg;
    private final OtherMsg other;
    private final LoggerLoc loggerLoc;
    private volatile BoundedHttp http;
    private final Map<UUID, String> webhookUrls = new ConcurrentHashMap<>();
    private final Map<UUID, DiscordWebhook> discordWebhooks = new ConcurrentHashMap<>();
    private TelegramBot telegram;

    public IntegrationHub(Logger logger, LinkStore links, WebhookStore webhooks, Cfg cfg, OtherMsg other,
                          LoggerLoc loggerLoc,
                          ExternalMsg telegramMessages, ExternalMsg discordMessages) {
        this.logger = logger;
        this.links = links;
        this.webhooks = webhooks;
        this.cfg = cfg;
        this.other = other;
        this.loggerLoc = loggerLoc;
        this.telegramMessages = telegramMessages;
        this.discordMessages = discordMessages;
        this.http = new BoundedHttp(cfg.httpMaxConcurrent(), cfg.httpConnectTimeoutSeconds(),
                cfg.httpResponseLimitBytes());
    }

    public void start(Sched sched) {
        if (cfg.telegramEnabled()) {
            if (cfg.telegramToken() == null || cfg.telegramToken().isBlank()) {
                logger.warning(other.raw("telegram-token-empty-log"));
            } else {
                telegram = new TelegramBot(logger, links, telegramMessages, other, loggerLoc,
                        cfg.telegramToken(), cfg.telegramPollIntervalSeconds(), http);
                telegram.start(sched);
            }
        }
    }

    public void loadWebhooks(Map<UUID, String> saved) {
        discordWebhooks.clear();
        webhookUrls.clear();
        saved.forEach((playerId, webhook) -> {
            if (DiscordWebhook.isValid(webhook)) {
                webhookUrls.put(playerId, webhook);
                discordWebhooks.put(playerId, new DiscordWebhook(logger, webhook, discordMessages, other, loggerLoc, http));
            }
        });
    }

    public void stop() {
        if (telegram != null) {
            telegram.stop();
            telegram = null;
        }
        discordWebhooks.clear();
        webhookUrls.clear();
    }

    public void reload(Sched sched) {
        if (telegram != null) {
            telegram.stop();
            telegram = null;
        }
        http = new BoundedHttp(cfg.httpMaxConcurrent(), cfg.httpConnectTimeoutSeconds(),
                cfg.httpResponseLimitBytes());
        discordWebhooks.clear();
        webhookUrls.forEach((playerId, webhook) -> discordWebhooks.put(playerId,
                new DiscordWebhook(logger, webhook, discordMessages, other, loggerLoc, http)));
        start(sched);
    }

    public CompletableFuture<String> createTelegramCode(java.util.UUID playerId) {
        if (telegram == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(other.raw("telegram-disabled")));
        }
        return links.createCode(playerId, "telegram", cfg.telegramCodeTimeoutSeconds());
    }

    public CompletableFuture<Boolean> saveDiscordWebhook(UUID playerId, String webhook) {
        if (!cfg.discordEnabled() || !DiscordWebhook.isValid(webhook)) {
            return CompletableFuture.completedFuture(false);
        }
        return webhooks.save(playerId, webhook).thenApply(ignored -> {
            webhookUrls.put(playerId, webhook);
            discordWebhooks.put(playerId, new DiscordWebhook(logger, webhook, discordMessages, other, loggerLoc, http));
            return true;
        });
    }

    public void sale(SaleLog sale) {
        if (telegram != null) {
            telegram.sale(sale);
        }
        if (cfg.discordEnabled()) {
            DiscordWebhook webhook = discordWebhooks.get(sale.sellerId());
            if (webhook != null) {
                webhook.sale(sale);
            }
        }
    }

    public void reloadMessages() {
        loggerLoc.reload();
        telegramMessages.reload();
        discordMessages.reload();
    }

    public MessageState messageState() {
        return new MessageState(loggerLoc.snapshot(), telegramMessages.snapshot(), discordMessages.snapshot());
    }

    public void restoreMessages(MessageState state) {
        loggerLoc.restore(state.loggerNames());
        telegramMessages.restore(state.telegram());
        discordMessages.restore(state.discord());
    }

    public record MessageState(Map<Material, String> loggerNames,
                               YamlConfiguration telegram,
                               YamlConfiguration discord) {
    }
}
