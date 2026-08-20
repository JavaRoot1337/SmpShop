package ru.javaroot.currency;

import org.bukkit.Material;

public enum Currency {
    DIAMOND_ORE(Material.DIAMOND_ORE),
    DEEPSLATE_DIAMOND_ORE(Material.DEEPSLATE_DIAMOND_ORE);

    private final Material material;

    Currency(Material material) {
        this.material = material;
    }

    public Material material() {
        return material;
    }

    public static Currency from(Material material) {
        for (Currency currency : values()) {
            if (currency.material == material) {
                return currency;
            }
        }
        return null;
    }
}
