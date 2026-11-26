package ru.javaroot.smpshop.cfg;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.javaroot.smpshop.plat.Hex;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Msg {
    private final Map<String, String> texts;
    private final Map<String, String> items;

    private Msg(Map<String, String> texts, Map<String, String> items) {
        this.texts = Map.copyOf(texts);
        this.items = Map.copyOf(items);
    }

    public static Msg from(FileConfiguration raw, FileConfiguration defaults) {
        Map<String, String> loadedTexts = new HashMap<>();
        collectStrings(defaults, "", loadedTexts);
        collectStrings(raw, "", loadedTexts);

        Map<String, String> loadedItems = new HashMap<>();
        collectItems(defaults.getConfigurationSection("items"), loadedItems);
        collectItems(raw.getConfigurationSection("items"), loadedItems);

        return new Msg(loadedTexts, loadedItems);
    }

    public String raw(String key) {
        return texts.getOrDefault(key, "[" + key + "]");
    }

    public String raw(String key, Map<String, ?> vars) {
        return Hex.applyVars(raw(key), vars);
    }

    public Component comp(String key) {
        return Hex.parse(raw(key));
    }

    public Component comp(String key, Map<String, ?> vars) {
        return Hex.parse(raw(key), vars);
    }

    public Component comp(String key, Map<String, ?> vars, Map<String, Component> comps) {
        return Hex.parse(raw(key), vars, comps);
    }

    public void send(CommandSender snd, String key) {
        snd.sendMessage(comp(key));
    }

    public void send(CommandSender snd, String key, Map<String, ?> vars) {
        snd.sendMessage(comp(key, vars));
    }

    public void send(CommandSender snd, String key, Map<String, ?> vars, Map<String, Component> comps) {
        snd.sendMessage(comp(key, vars, comps));
    }

    public String itemName(Material mat) {
        if (mat == null) {
            return "";
        }
        String custom = items.get(mat.name());
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static void collectStrings(ConfigurationSection sec, String prefix, Map<String, String> out) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            if (prefix.isEmpty() && key.equals("items")) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (sec.isConfigurationSection(key)) {
                collectStrings(sec.getConfigurationSection(key), path, out);
            } else if (sec.isString(key)) {
                out.put(path, sec.getString(key));
            }
        }
    }

    private static void collectItems(ConfigurationSection sec, Map<String, String> out) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            if (sec.isString(key)) {
                out.put(key.toUpperCase(Locale.ROOT), sec.getString(key));
            }
        }
    }
}
