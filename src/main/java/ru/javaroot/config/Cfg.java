package ru.javaroot.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class Cfg {
    private final JavaPlugin plugin;
    private final OtherMsg other;
    private FileConfiguration cfg;

    public Cfg(JavaPlugin plugin, OtherMsg other) {
        this.plugin = plugin;
        this.other = other;
        this.cfg = plugin.getConfig();
        migrate();
        validate();
    }

    public void reload() {
        plugin.reloadConfig();
        cfg = plugin.getConfig();
        migrate();
        validate();
    }

    public int minPrice() {
        return cfg.getInt("shop.min-price");
    }

    public int maxPrice() {
        return cfg.getInt("shop.max-price");
    }

    public int regularShopLimit() {
        return cfg.getInt("shop.max-per-player");
    }

    public int plusShopLimit() {
        return cfg.getInt("shop.plus-max-per-player");
    }

    public int placementTimeoutSeconds() {
        return cfg.getInt("shop.pending-placement-timeout-seconds");
    }

    public String sqliteFile() {
        return cfg.getString("storage.sqlite-file");
    }

    public int transactionTimeoutSeconds() {
        return cfg.getInt("storage.transaction-timeout-seconds");
    }

    public int menuSize() {
        return cfg.getInt("shop.menu-size");
    }

    public boolean telegramEnabled() {
        return cfg.getBoolean("integrations.telegram.enabled");
    }

    public String telegramToken() {
        return cfg.getString("integrations.telegram.bot-token");
    }

    public int telegramPollIntervalSeconds() {
        return cfg.getInt("integrations.telegram.poll-interval-seconds");
    }

    public int telegramCodeTimeoutSeconds() {
        return cfg.getInt("integrations.telegram.link-code-timeout-seconds");
    }

    public boolean telegramReferralBot() {
        return cfg.getBoolean("integrations.telegram.referal-bot");
    }

    public String telegramUserBot() {
        String value = cfg.getString("integrations.telegram.user-bot");
        return value == null ? null : value.trim().replaceFirst("^@", "");
    }

    public boolean discordEnabled() {
        return cfg.getBoolean("integrations.discord.enabled");
    }

    public boolean sellerSaleSoundEnabled() {
        return cfg.getBoolean("sounds.seller-sale.enabled");
    }

    public String sellerSaleSound() {
        return cfg.getString("sounds.seller-sale.sound");
    }

    public float sellerSaleVolume() {
        return (float) cfg.getDouble("sounds.seller-sale.volume");
    }

    public float sellerSalePitch() {
        return (float) cfg.getDouble("sounds.seller-sale.pitch");
    }

    public boolean buyerPurchaseSoundEnabled() {
        return cfg.getBoolean("sounds.buyer-purchase.enabled");
    }

    public String buyerPurchaseSound() {
        return cfg.getString("sounds.buyer-purchase.sound");
    }

    public float buyerPurchaseVolume() {
        return (float) cfg.getDouble("sounds.buyer-purchase.volume");
    }

    public float buyerPurchasePitch() {
        return (float) cfg.getDouble("sounds.buyer-purchase.pitch");
    }

    public boolean offlineNotificationsEnabled() {
        return cfg.getBoolean("notifications.offline-enabled");
    }

    public boolean joinMessageEnabled() {
        return cfg.getBoolean("notifications.join-message-enabled");
    }

    private void validate() {
        if (minPrice() < 1 || maxPrice() < minPrice()) {
            throw new IllegalStateException(other.raw("config-invalid-price-range"));
        }
        List<Integer> sizes = cfg.getIntegerList("shop.allowed-menu-sizes");
        int menuSize = cfg.getInt("shop.menu-size");
        if (!sizes.contains(menuSize) || menuSize % 9 != 0 || menuSize < 9 || menuSize > 54) {
            throw new IllegalStateException(other.raw("config-invalid-menu-size"));
        }
    }

    private void migrate() {
        boolean changed = false;
        changed |= remove("shop.default-price");
        changed |= remove("shop.buyer-lock-timeout-seconds");
        if (!cfg.contains("integrations.telegram.referal-bot")) {
            cfg.set("integrations.telegram.referal-bot", true);
            changed = true;
        }
        if (!cfg.contains("integrations.telegram.user-bot")) {
            cfg.set("integrations.telegram.user-bot", "");
            changed = true;
        }
        if (cfg.contains("integrations.discord.sales-webhook-url")) {
            cfg.set("integrations.discord.sales-webhook-url", null);
            changed = true;
        }
        if (cfg.contains("storage.flush-interval-seconds")) {
            cfg.set("storage.flush-interval-seconds", null);
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    private boolean remove(String path) {
        if (!cfg.contains(path)) {
            return false;
        }
        cfg.set(path, null);
        return true;
    }
}
