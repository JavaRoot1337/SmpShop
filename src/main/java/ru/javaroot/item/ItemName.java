package ru.javaroot.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

public class ItemName {
    public Component component(Material material) {
        return Component.translatable(material.translationKey());
    }
}
