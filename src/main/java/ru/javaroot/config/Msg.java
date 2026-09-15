package ru.javaroot.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import ru.javaroot.item.ItemIcon;

import java.io.File;
import java.util.Map;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import ru.javaroot.logersxd.Loggers;

public class Msg {
    private final JavaPlugin plugin;
    private final OtherMsg other;
    private final Loggers.Channel logger;
    private final ItemIcon itemIcon = new ItemIcon();
    private volatile FileConfiguration messages;

    public Msg(JavaPlugin plugin, OtherMsg other) {
        this(plugin, other, new Loggers(plugin.getLogger()).config());
    }

    public Msg(JavaPlugin plugin, OtherMsg other, Loggers.Channel logger) {
        this.plugin = plugin;
        this.other = other;
        this.logger = logger;
        reload();
    }

    public void reload() {
        String resource = "message.yml";
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
        YamlConfiguration loaded = loadFile(file, resource);
        removeUnused(loaded, file);
        FileConfiguration defaults = loadDefaults(resource);
        loaded.setDefaults(defaults);
        messages = loaded;
    }

    public FileConfiguration snapshot() {
        return messages;
    }

    public void restore(FileConfiguration snapshot) {
        messages = snapshot;
    }

    public void send(Player player, String key) {
        player.sendMessage(component(key));
    }

    public void send(Player player, String key, Map<String, ?> values) {
        player.sendMessage(component(key, values));
    }

    public void send(Player player, String key, Map<String, ?> values, Map<String, Component> components) {
        player.sendMessage(component(key, values, components));
    }

    public void sendCode(Player player, String code) {
        Component message = itemIcon.replace(LegacyComponentSerializer.legacyAmpersand().deserialize(messages.getString("link-code-prefix")))
                .append(Component.text(code, NamedTextColor.AQUA).clickEvent(ClickEvent.copyToClipboard("/code " + code)))
                .append(itemIcon.replace(LegacyComponentSerializer.legacyAmpersand().deserialize(messages.getString("link-code-suffix"))));
        player.sendMessage(message);
    }

    public void sendTelegramReferral(Player player, String botName, String code) {
        String url = raw("telegram-referral-url-template")
                .replace("%bot%", botName)
                .replace("%code%", code);
        Component link = itemIcon.replace(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(messages.getString("telegram-referral-suffix")))
                .clickEvent(ClickEvent.openUrl(url));
        Component message = itemIcon.replace(LegacyComponentSerializer.legacyAmpersand().deserialize(messages.getString("telegram-referral-prefix")))
                .append(link);
        player.sendMessage(message);
    }

    public String text(String key) {
        return LegacyComponentSerializer.legacySection().serialize(component(key));
    }

    public String text(String key, Map<String, ?> values) {
        return LegacyComponentSerializer.legacySection().serialize(component(key, values));
    }

    public Component component(String key) {
        return itemIcon.replace(LegacyComponentSerializer.legacyAmpersand().deserialize(raw(key)));
    }

    public Component component(String key, Map<String, ?> values) {
        return itemIcon.replace(LegacyComponentSerializer.legacyAmpersand().deserialize(raw(key, values)));
    }

    public Component component(String key, Map<String, ?> values, Map<String, Component> components) {
        Component result = itemIcon.replace(LegacyComponentSerializer.legacyAmpersand().deserialize(raw(key, values)));
        for (Map.Entry<String, Component> entry : components.entrySet()) {
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral("%" + entry.getKey() + "%")
                    .replacement(entry.getValue())
                    .build());
        }
        return itemIcon.replace(result);
    }

    public String raw(String key) {
        String value = messages.getString(key);
        if (value == null) {
            logger.warning(other.raw("message-key-missing", Map.of("key", key)));
            return "[" + key + "]";
        }
        return value;
    }

    public String raw(String key, Map<String, ?> values) {
        String value = raw(key);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value;
    }

    private FileConfiguration loadDefaults(String resource) {
        InputStream stream = plugin.getResource(resource);
        if (stream == null) {
            throw new IllegalStateException(other.raw("message-resource-missing", Map.of("resource", resource)));
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(reader);
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(other.raw("message-resource-load-failed", Map.of("resource", resource)), exception);
        }
    }

    private YamlConfiguration loadFile(File file, String resource) {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(other.raw("message-file-invalid", Map.of("resource", resource)), exception);
        }
        return loaded;
    }

    private void removeUnused(YamlConfiguration loaded, File file) {
        boolean changed = false;
        for (String key : List.of("prefix", "usage", "placement-expired", "not-chest", "balance",
                "list-empty", "list-entry", "link-created", "balance-diamond", "balance-deepslate",
                "balance-close",
                "config-invalid-price-range", "config-invalid-menu-size", "command-not-registered",
                "telegram-token-empty-log", "telegram-disabled", "unknown-sound", "telegram-http-error",
                "telegram-api-error", "telegram-poll-error", "telegram-link-error",
                "telegram-message-http-error", "telegram-send-error", "discord-http-error",
                "discord-send-error", "notification-delivery-error", "notification-load-error",
                "notification-save-error")) {
            if (loaded.contains(key)) {
                loaded.set(key, null);
                changed = true;
            }
        }
        if (changed) {
            try {
                loaded.save(file);
            } catch (IOException exception) {
                throw new IllegalStateException(other.raw("message-file-update-failed", Map.of("resource", "message.yml")), exception);
            }
        }
    }
}
