package ru.javaroot.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class Cfg {
    private final JavaPlugin plugin;
    private final OtherMsg other;
    private volatile FileConfiguration cfg;

    public Cfg(JavaPlugin plugin, OtherMsg other) {
        this.plugin = plugin;
        this.other = other;
        this.cfg = plugin.getConfig();
        validate(cfg);
        migrate(cfg, true);
        validate(cfg);
    }

    public synchronized void reload() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration candidate = YamlConfiguration.loadConfiguration(file);
        validate(candidate);
        boolean changed = migrate(candidate, false);
        validate(candidate);
        if (changed) {
            try {
                candidate.save(file);
            } catch (IOException exception) {
                throw new IllegalStateException(other.raw("config-invalid-value"), exception);
            }
        }
        cfg = candidate;
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
        return cfg.getBoolean("integrations.telegram.referral-bot");
    }

    public String telegramUserBot() {
        String value = cfg.getString("integrations.telegram.user-bot");
        return value == null ? null : value.trim().replaceFirst("^@", "");
    }

    public boolean discordEnabled() {
        return cfg.getBoolean("integrations.discord.enabled");
    }

    public int httpMaxConcurrent() {
        return cfg.getInt("integrations.http.max-concurrent");
    }

    public int httpConnectTimeoutSeconds() {
        return cfg.getInt("integrations.http.connect-timeout-seconds");
    }

    public int httpResponseLimitBytes() {
        return cfg.getInt("integrations.http.response-limit-bytes");
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

    private void validate(FileConfiguration candidate) {
        int minPrice = candidate.getInt("shop.min-price");
        int maxPrice = candidate.getInt("shop.max-price");
        if (minPrice < 1 || maxPrice < minPrice) {
            throw new IllegalStateException(other.raw("config-invalid-price-range"));
        }
        int regularLimit = candidate.getInt("shop.max-per-player");
        if (regularLimit < 1 || candidate.getInt("shop.plus-max-per-player") < regularLimit
                || candidate.getInt("shop.pending-placement-timeout-seconds") < 1
                || candidate.getInt("storage.transaction-timeout-seconds") < 1
                || candidate.getInt("integrations.telegram.poll-interval-seconds") < 1
                || candidate.getInt("integrations.telegram.link-code-timeout-seconds") < 1
                || candidate.getInt("integrations.http.max-concurrent") < 1
                || candidate.getInt("integrations.http.max-concurrent") > 64
                || candidate.getInt("integrations.http.connect-timeout-seconds") < 1
                || candidate.getInt("integrations.http.connect-timeout-seconds") > 120
                || candidate.getInt("integrations.http.response-limit-bytes") < 1024
                || candidate.getInt("integrations.http.response-limit-bytes") > 16 * 1024 * 1024
                || !validVolume((float) candidate.getDouble("sounds.seller-sale.volume"))
                || !validVolume((float) candidate.getDouble("sounds.buyer-purchase.volume"))
                || !validPitch((float) candidate.getDouble("sounds.seller-sale.pitch"))
                || !validPitch((float) candidate.getDouble("sounds.buyer-purchase.pitch"))) {
            throw new IllegalStateException(other.raw("config-invalid-value"));
        }
        validateSqlitePath(candidate);
        List<Integer> sizes = candidate.getIntegerList("shop.allowed-menu-sizes");
        int menuSize = candidate.getInt("shop.menu-size");
        if (!sizes.contains(menuSize) || menuSize % 9 != 0 || menuSize < 9 || menuSize > 54) {
            throw new IllegalStateException(other.raw("config-invalid-menu-size"));
        }
    }

    private boolean validVolume(float value) {
        return Float.isFinite(value) && value >= 0;
    }

    private boolean validPitch(float value) {
        return Float.isFinite(value) && value > 0 && value <= 2;
    }

    private void validateSqlitePath(FileConfiguration candidate) {
        String fileName = candidate.getString("storage.sqlite-file");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalStateException(other.raw("config-invalid-value"));
        }
        try {
            File root = plugin.getDataFolder().getCanonicalFile();
            File file = new File(root, fileName).getCanonicalFile();
            if (file.equals(root) || !file.toPath().startsWith(root.toPath())) {
                throw new IllegalStateException(other.raw("config-invalid-value"));
            }
        } catch (IOException exception) {
            throw new IllegalStateException(other.raw("config-invalid-value"), exception);
        }
    }

    private boolean migrate(FileConfiguration candidate, boolean pluginConfig) {
        boolean changed = false;
        changed |= remove(candidate, "shop.default-price");
        changed |= remove(candidate, "shop.buyer-lock-timeout-seconds");
        if (!candidate.isSet("integrations.telegram.referral-bot")
                && candidate.isSet("integrations.telegram.referal-bot")) {
            candidate.set("integrations.telegram.referral-bot",
                    candidate.getBoolean("integrations.telegram.referal-bot"));
            changed = true;
        }
        if (candidate.isSet("integrations.telegram.referal-bot")) {
            candidate.set("integrations.telegram.referal-bot", null);
            changed = true;
        }
        if (candidate.contains("integrations.discord.sales-webhook-url")) {
            candidate.set("integrations.discord.sales-webhook-url", null);
            changed = true;
        }
        if (candidate.contains("storage.flush-interval-seconds")) {
            candidate.set("storage.flush-interval-seconds", null);
            changed = true;
        }
        if (changed && pluginConfig) {
            plugin.saveConfig();
        }
        return changed;
    }

    private boolean remove(FileConfiguration candidate, String path) {
        if (!candidate.contains(path)) {
            return false;
        }
        candidate.set(path, null);
        return true;
    }
}
