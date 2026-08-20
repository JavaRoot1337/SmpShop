package ru.javaroot.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class OtherMsg {
    private static final String RESOURCE = "message_other.yml";

    private final JavaPlugin plugin;
    private FileConfiguration messages;
    private boolean invalidFile;

    public OtherMsg(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), RESOURCE);
        invalidFile = false;
        if (!file.exists()) {
            plugin.saveResource(RESOURCE, false);
        }
        messages = loadFile(file);
        messages.setDefaults(loadDefaults());
        if (invalidFile) {
            plugin.getLogger().warning(raw("message-file-invalid", Map.of("resource", RESOURCE)));
        }
    }

    public String raw(String key) {
        String value = messages.getString(key);
        if (value == null) {
            String fallback = messages.getString("message-key-missing");
            if (fallback == null) {
                fallback = "[message_other:" + key + "]";
            } else {
                fallback = fallback.replace("%key%", key);
            }
            plugin.getLogger().warning(fallback);
            return fallback;
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

    private FileConfiguration loadDefaults() {
        InputStream stream = plugin.getResource(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(RESOURCE);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(reader);
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(RESOURCE, exception);
        }
    }

    private YamlConfiguration loadFile(File file) {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            invalidFile = true;
        }
        return loaded;
    }
}
