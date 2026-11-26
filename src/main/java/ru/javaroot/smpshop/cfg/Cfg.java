package ru.javaroot.smpshop.cfg;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

public record Cfg(
        int minPrice,
        int maxPrice,
        int maxPerPlayer,
        int plusMaxPerPlayer,
        int menuSize,
        int placementTimeoutSec,
        String sqliteFile,
        int txTimeoutSec,
        int shutdownTimeoutSec,
        SoundCfg sellerSound,
        SoundCfg buyerSound,
        boolean offlineNotify,
        boolean joinNotify,
        int httpMaxConcurrent,
        int httpConnectTimeoutSec,
        int httpRequestTimeoutSec,
        int httpResponseLimitBytes,
        boolean tgEnabled,
        String tgToken,
        int tgPollIntervalSec,
        int tgLongPollSec,
        int tgCodeTimeoutSec,
        String tgApiBaseUrl,
        boolean tgReferralBot,
        String tgUserBot,
        boolean dsEnabled
) {
    public static Cfg from(FileConfiguration raw, Plugin plug) {
        int minPrice = raw.getInt("shop.min-price", 1);
        int maxPrice = raw.getInt("shop.max-price", 2304);
        int regularLimit = raw.getInt("shop.max-per-player", 10);
        int plusLimit = raw.getInt("shop.plus-max-per-player", 50);
        int menuSize = raw.getInt("shop.menu-size", 36);
        List<Integer> allowedSizes = raw.getIntegerList("shop.allowed-menu-sizes");
        int timeoutSec = raw.getInt("shop.pending-placement-timeout-seconds", 60);

        if (minPrice < 1 || maxPrice < minPrice) {
            throw new IllegalStateException("Неверный диапазон цен в config.yml");
        }
        if (regularLimit < 1 || plusLimit < regularLimit || timeoutSec < 1) {
            throw new IllegalStateException("Неверные лимиты или таймаут магазина в config.yml");
        }
        if (!allowedSizes.contains(menuSize) || menuSize % 9 != 0 || menuSize < 9 || menuSize > 54) {
            throw new IllegalStateException("Неверный размер меню shop.menu-size: " + menuSize);
        }

        String sqliteFile = raw.getString("storage.sqlite-file", "shops.db");
        validateSqlitePath(plug, sqliteFile);
        int txTimeout = raw.getInt("storage.transaction-timeout-seconds", 10);
        int shutdownTimeout = raw.getInt("storage.shutdown-timeout-seconds", 10);
        if (txTimeout < 1 || shutdownTimeout < 1 || shutdownTimeout > 300) {
            throw new IllegalStateException("Неверные таймауты storage в config.yml");
        }

        SoundCfg sellerSound = SoundCfg.from(raw, "sounds.seller-sale", "entity.player.levelup", 0.9f);
        SoundCfg buyerSound = SoundCfg.from(raw, "sounds.buyer-purchase", "block.vault.insert_item_fail", 1.0f);

        int httpMax = raw.getInt("integrations.http.max-concurrent", 4);
        int httpConn = raw.getInt("integrations.http.connect-timeout-seconds", 10);
        int httpReq = raw.getInt("integrations.http.request-timeout-seconds", 15);
        int httpLimit = raw.getInt("integrations.http.response-limit-bytes", 1048576);
        if (httpMax < 1 || httpMax > 64 || httpConn < 1 || httpConn > 120
                || httpReq < 1 || httpReq > 300 || httpLimit < 1024 || httpLimit > 16 * 1024 * 1024) {
            throw new IllegalStateException("Неверные параметры integrations.http в config.yml");
        }

        int tgPoll = raw.getInt("integrations.telegram.poll-interval-seconds", 2);
        int tgLongPoll = raw.getInt("integrations.telegram.poll-long-poll-seconds", 1);
        int tgCodeTimeout = raw.getInt("integrations.telegram.link-code-timeout-seconds", 300);
        String tgApi = raw.getString("integrations.telegram.api-base-url", "https://api.telegram.org/bot");
        if (tgPoll < 1 || tgLongPoll < 0 || tgLongPoll > 60 || tgCodeTimeout < 1
                || tgApi == null || !tgApi.startsWith("https://api.telegram.org/bot")) {
            throw new IllegalStateException("Неверные параметры integrations.telegram в config.yml");
        }

        String userBot = raw.getString("integrations.telegram.user-bot", "");
        String normalizedBot = userBot == null ? "" : userBot.trim().replaceFirst("^@", "");

        return new Cfg(
                minPrice,
                maxPrice,
                regularLimit,
                plusLimit,
                menuSize,
                timeoutSec,
                sqliteFile,
                txTimeout,
                shutdownTimeout,
                sellerSound,
                buyerSound,
                raw.getBoolean("notifications.offline-enabled", true),
                raw.getBoolean("notifications.join-message-enabled", true),
                httpMax,
                httpConn,
                httpReq,
                httpLimit,
                raw.getBoolean("integrations.telegram.enabled", false),
                raw.getString("integrations.telegram.bot-token", ""),
                tgPoll,
                tgLongPoll,
                tgCodeTimeout,
                tgApi,
                raw.getBoolean("integrations.telegram.referral-bot", true),
                normalizedBot,
                raw.getBoolean("integrations.discord.enabled", false)
        );
    }

    private static void validateSqlitePath(Plugin plug, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalStateException("Пустой storage.sqlite-file в config.yml");
        }
        try {
            File root = plug.getDataFolder().getCanonicalFile();
            File target = new File(root, fileName).getCanonicalFile();
            if (target.equals(root) || !target.toPath().startsWith(root.toPath())) {
                throw new IllegalStateException("Путь storage.sqlite-file выходит за папку плагина");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось проверить путь storage.sqlite-file", ex);
        }
    }

    public record SoundCfg(boolean enabled, String name, float volume, float pitch) {
        private static SoundCfg from(FileConfiguration raw, String path, String defSound, float defPitch) {
            boolean enabled = raw.getBoolean(path + ".enabled", true);
            String sound = raw.getString(path + ".sound", defSound);
            float volume = (float) raw.getDouble(path + ".volume", 1.0);
            float pitch = (float) raw.getDouble(path + ".pitch", defPitch);
            if (!Float.isFinite(volume) || volume < 0 || !Float.isFinite(pitch) || pitch <= 0 || pitch > 2) {
                throw new IllegalStateException("Неверная громкость или тон звука: " + path);
            }
            return new SoundCfg(enabled, sound, volume, pitch);
        }
    }
}
