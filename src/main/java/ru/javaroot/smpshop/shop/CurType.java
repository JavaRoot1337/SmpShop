package ru.javaroot.smpshop.shop;

import org.bukkit.Material;

public enum CurType {
    DIAMOND_ORE(Material.DIAMOND_ORE),
    DEEPSLATE_DIAMOND_ORE(Material.DEEPSLATE_DIAMOND_ORE);

    private final Material mat;

    CurType(Material mat) {
        this.mat = mat;
    }

    public Material mat() {
        return mat;
    }

    public static CurType from(Material mat) {
        if (mat == DIAMOND_ORE.mat) {
            return DIAMOND_ORE;
        }
        if (mat == DEEPSLATE_DIAMOND_ORE.mat) {
            return DEEPSLATE_DIAMOND_ORE;
        }
        return null;
    }
}
