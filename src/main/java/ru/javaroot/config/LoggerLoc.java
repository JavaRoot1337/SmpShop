package ru.javaroot.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class LoggerLoc {
    private final DbLoc dbLoc;

    public LoggerLoc(JavaPlugin plugin, OtherMsg other) {
        dbLoc = new DbLoc(plugin);
    }

    public void reload() {
        dbLoc.reload();
    }

    public Map<Material, String> snapshot() {
        EnumMap<Material, String> loaded = new EnumMap<>(Material.class);
        dbLoc.snapshot().forEach((key, value) -> {
            if (!key.startsWith("items.")) {
                return;
            }
            try {
                Material material = Material.valueOf(key.substring("items.".length()).toUpperCase(Locale.ROOT));
                if (material.isItem()) {
                    loaded.put(material, value);
                }
            } catch (IllegalArgumentException ignored) {
            }
        });
        return Map.copyOf(loaded);
    }

    public void restore(Map<Material, String> snapshot) {
        Map<String, String> values = new LinkedHashMap<>(dbLoc.snapshot());
        snapshot.forEach((material, name) -> values.put("items." + material.name(), name));
        dbLoc.restore(values);
    }

    public String name(Material material) {
        return dbLoc.itemName(material);
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

}
