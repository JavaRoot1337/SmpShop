package ru.javaroot.integration;

import ru.javaroot.config.Cfg;
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
    private final Map<UUID, DiscordWebhook> discordWebhooks = new ConcurrentHashMap<>();
    private TelegramBot telegram;

    public IntegrationHub(Logger logger, LinkStore links, WebhookStore webhooks, Cfg cfg, OtherMsg other,
                          ExternalMsg telegramMessages, ExternalMsg discordMessages) {
        this.logger = logger;
        this.links = links;
        this.webhooks = webhooks;
        this.cfg = cfg;
        this.other = other;
        this.telegramMessages = telegramMessages;
        this.discordMessages = discordMessages;
    }

    public void start(Sched sched) {
        if (cfg.telegramEnabled()) {
            if (cfg.telegramToken() == null || cfg.telegramToken().isBlank()) {
                logger.warning(other.raw("telegram-token-empty-log"));
            } else {
                telegram = new TelegramBot(logger, links, telegramMessages, other, cfg.telegramToken(), cfg.telegramPollIntervalSeconds());
                telegram.start(sched);
            }
        }
    }

    public void loadWebhooks(Map<UUID, String> saved) {
        discordWebhooks.clear();
        saved.forEach((playerId, webhook) -> {
            if (DiscordWebhook.isValid(webhook)) {
                discordWebhooks.put(playerId, new DiscordWebhook(logger, webhook, discordMessages, other));
            }
        });
    }

    public void stop() {
        if (telegram != null) {
            telegram.stop();
            telegram = null;
        }
        discordWebhooks.clear();
    }

    public void reload(Sched sched) {
        if (telegram != null) {
            telegram.stop();
            telegram = null;
        }
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
            discordWebhooks.put(playerId, new DiscordWebhook(logger, webhook, discordMessages, other));
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
        telegramMessages.reload();
        discordMessages.reload();
    }
}
