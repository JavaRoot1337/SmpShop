package ru.javaroot.integration.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookTest {
    @Test
    void acceptsDiscordWebhookUrl() {
        assertTrue(DiscordWebhook.isValid("https://discord.com/api/webhooks/123/token"));
        assertFalse(DiscordWebhook.isValid("https://example.com/api/webhooks/123/token"));
    }
}
