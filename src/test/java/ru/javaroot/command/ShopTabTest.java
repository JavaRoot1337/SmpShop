package ru.javaroot.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopTabTest {
    @Test
    void completesDiscordWebhookArgument() {
        assertEquals(List.of("webhook"), new ShopTab().onTabComplete(null, null, null,
                new String[]{"link", "ds", ""}));
    }
}
