package ru.javaroot.integration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.javaroot.config.OtherMsg;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.List;

public class ExternalMsg {
    private final JavaPlugin plugin;
    private final String resource;
    private final OtherMsg other;
    private YamlConfiguration messages;

    public ExternalMsg(JavaPlugin plugin, String resource, OtherMsg other) {
        this.plugin = plugin;
        this.resource = resource;
        this.other = other;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
        messages = new YamlConfiguration();
        try {
            messages.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(other.raw("external-message-load-failed", Map.of("resource", resource)), exception);
        }
        removeLegacyTestMessages(file);
    }

    public String text(String key, Map<String, ?> values) {
        String value = messages.getString(key);
        if (value == null) {
            throw new IllegalStateException(other.raw("external-message-key-missing", Map.of("key", key, "resource", resource)));
        }
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value;
    }

    private void removeLegacyTestMessages(File file) {
        List<String> keys;
        if (resource.equals("message_tg.yml")) {
            keys = List.of("test-sale");
        } else if (resource.equals("message_ds.yml")) {
            keys = List.of("test-sale", "webhook-ok", "webhook-error");
        } else {
            keys = List.of();
        }
        boolean changed = false;
        for (String key : keys) {
            if (messages.contains(key)) {
                messages.set(key, null);
                changed = true;
            }
        }
        if (changed) {
            try {
                messages.save(file);
            } catch (IOException exception) {
                throw new IllegalStateException(other.raw("external-message-update-failed", Map.of("resource", resource)), exception);
            }
        }
    }
}
