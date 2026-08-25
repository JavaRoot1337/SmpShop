package ru.javaroot.item;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;

public class ItemIcon {
    private static final Key BLOCKS_ATLAS = Key.key("minecraft", "blocks");
    private static final Component DIAMOND_ORE_ICON = icon("diamond_ore");
    private static final Component DEEPSLATE_DIAMOND_ORE_ICON = icon("deepslate_diamond_ore");

    public Component replace(Component message) {
        Component result = message.replaceText(replacement("🟦", DIAMOND_ORE_ICON));
        result = result.replaceText(replacement("⬛️", DEEPSLATE_DIAMOND_ORE_ICON));
        return result.replaceText(replacement("⬛", DEEPSLATE_DIAMOND_ORE_ICON));
    }

    private static Component icon(String material) {
        String sprite = "block/" + material;
        return Component.object(ObjectContents.sprite(BLOCKS_ATLAS, Key.key("minecraft", sprite)))
                .decoration(TextDecoration.ITALIC, false);
    }

    private TextReplacementConfig replacement(String placeholder, Component icon) {
        return TextReplacementConfig.builder()
                .matchLiteral(placeholder)
                .replacement(icon)
                .build();
    }
}
