package ru.javaroot.config;

import org.bukkit.Material;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class DbLoc {
    private static final String RESOURCE = "db_locate.yml";
    private static final Map<String, String> LEGACY_NOTIFICATION_KEYS = Map.of(
            "notification-delivery-error", "notification.delivery-failed",
            "notification-load-error", "notification.load-failed",
            "notification-save-error", "notification.save-failed");

    private final JavaPlugin plugin;
    private volatile Map<String, String> values = Map.of();
    private volatile Map<Material, String> itemNames = Map.of();

    public DbLoc(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public synchronized void reload() {
        File file = new File(plugin.getDataFolder(), RESOURCE);
        boolean created = !file.exists();
        if (created) {
            plugin.saveResource(RESOURCE, false);
        }

        YamlConfiguration loaded = loadFile(file);
        loaded.setDefaults(loadDefaults());
        if (created) {
            importLegacyItems(loaded);
            importLegacyNotifications(loaded);
            save(file, loaded);
        }

        Map<String, String> nextValues = flatten(loaded);
        Map<Material, String> nextItems = parseItems(loaded);
        values = nextValues;
        itemNames = nextItems;
    }

    public Map<String, String> snapshot() {
        return values;
    }

    public synchronized void restore(Map<String, String> snapshot) {
        values = Map.copyOf(snapshot);
        EnumMap<Material, String> loaded = new EnumMap<>(Material.class);
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            if (!entry.getKey().startsWith("items.")) {
                continue;
            }
            try {
                Material material = Material.valueOf(entry.getKey().substring("items.".length()).toUpperCase(Locale.ROOT));
                if (material.isItem() && !entry.getValue().isBlank()) {
                    loaded.put(material, entry.getValue().trim());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        itemNames = Map.copyOf(loaded);
    }

    public String raw(String key) {
        String value = values.get(key);
        if (value != null) {
            return value;
        }
        String missing = values.get("loader.missing-key");
        return missing == null ? "[db_locate:" + key + "]" : missing.replace("%key%", key);
    }

    public String raw(String key, Map<String, ?> replacements) {
        String value = raw(key);
        for (Map.Entry<String, ?> entry : replacements.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value;
    }

    public String itemName(Material material) {
        return itemNames.getOrDefault(material, material.name());
    }

    private YamlConfiguration loadFile(File file) {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(raw("loader.load-failed", Map.of("resource", RESOURCE)), exception);
        }
        return loaded;
    }

    private YamlConfiguration loadDefaults() {
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

    private void importLegacyItems(YamlConfiguration loaded) {
        File legacy = new File(plugin.getDataFolder(), "logger_locate.yml");
        if (!legacy.isFile()) {
            return;
        }
        YamlConfiguration source = YamlConfiguration.loadConfiguration(legacy);
        for (String key : source.getKeys(false)) {
            String value = source.getString(key);
            if (value != null && !value.isBlank() && isItem(key)) {
                loaded.set("items." + key.toUpperCase(Locale.ROOT), value.trim());
            }
        }
    }

    private void importLegacyNotifications(YamlConfiguration loaded) {
        File legacy = new File(plugin.getDataFolder(), "message_other.yml");
        if (!legacy.isFile()) {
            return;
        }
        YamlConfiguration source = YamlConfiguration.loadConfiguration(legacy);
        for (Map.Entry<String, String> entry : LEGACY_NOTIFICATION_KEYS.entrySet()) {
            String value = source.getString(entry.getKey());
            if (value != null && !value.isBlank()) {
                loaded.set(entry.getValue(), value);
            }
        }
    }

    private Map<String, String> flatten(FileConfiguration config) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                String value = config.getString(key);
                if (value != null) {
                    result.put(key, value);
                }
            }
        }
        return Map.copyOf(result);
    }

    private Map<Material, String> parseItems(FileConfiguration config) {
        EnumMap<Material, String> result = new EnumMap<>(Material.class);
        if (config.getConfigurationSection("items") == null) {
            return Map.of();
        }
        for (String key : config.getConfigurationSection("items").getKeys(false)) {
            String value = config.getString("items." + key);
            try {
                Material material = Material.valueOf(key.toUpperCase(Locale.ROOT));
                if (material.isItem() && value != null && !value.isBlank()) {
                    result.put(material, value.trim());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Map.copyOf(result);
    }

    private boolean isItem(String id) {
        try {
            return Material.valueOf(id.toUpperCase(Locale.ROOT)).isItem();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void save(File file, YamlConfiguration config) {
        try {
            config.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException(raw("loader.save-failed", Map.of("resource", RESOURCE)), exception);
        }
    }
}
