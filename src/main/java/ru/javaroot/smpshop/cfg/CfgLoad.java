package ru.javaroot.smpshop.cfg;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class CfgLoad {
    private final Plugin plug;
    private volatile Cfg cfg;
    private volatile Msg msg;

    public CfgLoad(Plugin plug) {
        this.plug = plug;
    }

    public Cfg cfg() {
        return cfg;
    }

    public Msg msg() {
        return msg;
    }

    public long load() {
        long start = System.currentTimeMillis();
        plug.saveDefaultConfig();
        ensureResource("messages.yml");

        YamlConfiguration nextRawCfg = loadYaml(new File(plug.getDataFolder(), "config.yml"));
        FileConfiguration defCfg = loadDefault("config.yml");
        nextRawCfg.setDefaults(defCfg);
        boolean migrated = migrateCfg(nextRawCfg);
        Cfg nextCfg = Cfg.from(nextRawCfg, plug);
        if (migrated) {
            saveYaml(nextRawCfg, new File(plug.getDataFolder(), "config.yml"));
        }

        YamlConfiguration nextRawMsg = loadYaml(new File(plug.getDataFolder(), "messages.yml"));
        FileConfiguration defMsg = loadDefault("messages.yml");
        nextRawMsg.setDefaults(defMsg);
        Msg nextMsg = Msg.from(nextRawMsg, defMsg);

        cfg = nextCfg;
        msg = nextMsg;
        return System.currentTimeMillis() - start;
    }

    private void ensureResource(String name) {
        File file = new File(plug.getDataFolder(), name);
        if (!file.exists()) {
            plug.saveResource(name, false);
        }
    }

    private YamlConfiguration loadYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            throw new IllegalStateException("Ошибка чтения " + file.getName(), ex);
        }
        return yaml;
    }

    private FileConfiguration loadDefault(String name) {
        try (InputStream stream = plug.getResource(name)) {
            if (stream == null) {
                throw new IllegalStateException("Не найден ресурс " + name);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(reader);
                return yaml;
            }
        } catch (IOException | InvalidConfigurationException ex) {
            throw new IllegalStateException("Ошибка загрузки ресурса " + name, ex);
        }
    }

    private void saveYaml(YamlConfiguration yaml, File file) {
        try {
            yaml.save(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сохранить " + file.getName(), ex);
        }
    }

    private boolean migrateCfg(YamlConfiguration yaml) {
        boolean changed = false;
        if (!yaml.isSet("integrations.telegram.referral-bot")
                && yaml.isSet("integrations.telegram.referal-bot")) {
            yaml.set("integrations.telegram.referral-bot", yaml.getBoolean("integrations.telegram.referal-bot"));
            changed = true;
        }
        for (String legacy : new String[]{
                "shop.default-price",
                "shop.buyer-lock-timeout-seconds",
                "integrations.telegram.referal-bot",
                "integrations.discord.sales-webhook-url",
                "storage.flush-interval-seconds"
        }) {
            if (yaml.contains(legacy)) {
                yaml.set(legacy, null);
                changed = true;
            }
        }
        return changed;
    }
}
