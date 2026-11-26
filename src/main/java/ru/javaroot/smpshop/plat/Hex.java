package ru.javaroot.smpshop.plat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Hex {
    private static final Pattern AMP_HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern BARE_HEX = Pattern.compile("&([0-9a-fA-F]{6})");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final Key BLOCKS_ATLAS = Key.key("minecraft", "blocks");
    private static final Component DIAMOND_ICON = sprite("diamond_ore");
    private static final Component DEEPSLATE_ICON = sprite("deepslate_diamond_ore");

    private Hex() {
    }

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String normalized = normalizeHex(raw);
        Component comp = LEGACY.deserialize(normalized);
        comp = comp.replaceText(literal("%icon_diamond%", DIAMOND_ICON));
        return comp.replaceText(literal("%icon_deepslate%", DEEPSLATE_ICON));
    }

    public static Component parse(String raw, Map<String, ?> vars) {
        return parse(applyVars(raw, vars));
    }

    public static Component parse(String raw, Map<String, ?> vars, Map<String, Component> comps) {
        Component res = parse(raw, vars);
        for (Map.Entry<String, Component> entry : comps.entrySet()) {
            res = res.replaceText(literal("%" + entry.getKey() + "%", entry.getValue()));
        }
        return res;
    }

    public static String applyVars(String raw, Map<String, ?> vars) {
        if (raw == null || vars == null || vars.isEmpty()) {
            return raw;
        }
        String res = raw;
        for (Map.Entry<String, ?> entry : vars.entrySet()) {
            res = res.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return res;
    }

    public static Component noItalic(Component comp) {
        return comp.decoration(TextDecoration.ITALIC, false);
    }

    private static String normalizeHex(String raw) {
        Matcher m1 = AMP_HEX.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m1.find()) {
            m1.appendReplacement(sb, "&#" + m1.group(1));
        }
        m1.appendTail(sb);

        Matcher m2 = BARE_HEX.matcher(sb.toString());
        StringBuilder out = new StringBuilder();
        while (m2.find()) {
            m2.appendReplacement(out, "&#" + m2.group(1));
        }
        m2.appendTail(out);
        return out.toString();
    }

    private static Component sprite(String blockName) {
        return Component.object(ObjectContents.sprite(BLOCKS_ATLAS, Key.key("minecraft", "block/" + blockName)))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static TextReplacementConfig literal(String token, Component replacement) {
        return TextReplacementConfig.builder()
                .matchLiteral(token)
                .replacement(replacement)
                .build();
    }
}
