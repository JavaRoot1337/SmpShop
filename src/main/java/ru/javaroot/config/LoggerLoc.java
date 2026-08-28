package ru.javaroot.config;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class LoggerLoc {
    private static final String RESOURCE = "logger_locate.yml";

    private final JavaPlugin plugin;
    private final OtherMsg other;
    private volatile Map<Material, String> names = Map.of();

    public LoggerLoc(JavaPlugin plugin, OtherMsg other) {
        this.plugin = plugin;
        this.other = other;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), RESOURCE);
        if (!file.exists()) {
            plugin.saveResource(RESOURCE, false);
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(other.raw("logger-locate-load-failed", Map.of("resource", RESOURCE)), exception);
        }

        Map<String, String> values = parse(config, LoggerLoc::isItem, item -> plugin.getLogger().warning(other.raw(
                "logger-locate-invalid-entry", Map.of("item", item))));
        EnumMap<Material, String> loaded = new EnumMap<>(Material.class);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            loaded.put(Material.valueOf(entry.getKey()), entry.getValue());
        }
        names = Map.copyOf(loaded);
    }

    public String name(Material material) {
        return displayName(material.name(), names.get(material));
    }

    static Map<String, String> parse(FileConfiguration config, Predicate<String> validId,
                                     Consumer<String> invalidEntry) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String key : config.getKeys(false)) {
            String value = config.getString(key);
            if (value == null || value.isBlank()) {
                invalidEntry.accept(key);
                continue;
            }

            String id = key.toUpperCase(Locale.ROOT);
            if (!validId.test(id)) {
                invalidEntry.accept(key);
                continue;
            }
            loaded.put(id, value.trim());
        }
        return Map.copyOf(loaded);
    }

    static String displayName(String materialId, String localizedName) {
        return localizedName == null ? materialId : localizedName;
    }

    private static boolean isItem(String id) {
        try {
            return Material.valueOf(id).isItem();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
