package ru.javaroot.currency;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CurrencyTest {
    @Test
    void mapsOnlySupportedMaterials() {
        assertEquals(Currency.DIAMOND_ORE, Currency.from(Material.DIAMOND_ORE));
        assertEquals(Currency.DEEPSLATE_DIAMOND_ORE, Currency.from(Material.DEEPSLATE_DIAMOND_ORE));
        assertNull(Currency.from(Material.DIAMOND));
    }
}
