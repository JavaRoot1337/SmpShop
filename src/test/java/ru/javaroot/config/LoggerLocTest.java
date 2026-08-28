package ru.javaroot.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerLocTest {
    @Test
    void parsesItemNames() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("DIAMOND_SWORD", "Алмазный меч");
        config.set("MACE", "Булава");

        Map<String, String> names = LoggerLoc.parse(config,
                Set.of("DIAMOND_SWORD", "MACE")::contains, ignored -> { });

        assertEquals("Алмазный меч", names.get("DIAMOND_SWORD"));
        assertEquals("Булава", names.get("MACE"));
    }

    @Test
    void fallsBackToMaterialId() {
        assertEquals("DIAMOND_SWORD", LoggerLoc.displayName("DIAMOND_SWORD", null));
    }

    @Test
    void skipsInvalidAndEmptyEntries() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("NOT_AN_ITEM", "Неизвестный предмет");
        config.set("AIR", "Воздух");
        config.set("BOW", "");
        List<String> invalid = new ArrayList<>();

        Map<String, String> names = LoggerLoc.parse(config,
                Set.of("BOW")::contains, invalid::add);

        assertTrue(names.isEmpty());
        assertEquals(3, invalid.size());
    }
}
