package ru.javaroot.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.SpriteObjectContents;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemIconTest {
    @Test
    void replacesOrePlaceholdersWithVanillaSprites() {
        Component result = new ItemIcon().replace(Component.text("🟦 ⬛️ ⬛"));
        List<SpriteObjectContents> sprites = new ArrayList<>();
        collectSprites(result, sprites);

        assertEquals(List.of("block/diamond_ore", "block/deepslate_diamond_ore", "block/deepslate_diamond_ore"),
                sprites.stream().map(sprite -> sprite.sprite().value()).toList());
        assertEquals(List.of("minecraft:blocks", "minecraft:blocks", "minecraft:blocks"),
                sprites.stream().map(sprite -> sprite.atlas().asString()).toList());

        String json = GsonComponentSerializer.gson().serialize(result);
        assertEquals(3, json.split("minecraft:block/", -1).length - 1, json);
    }

    private void collectSprites(Component component, List<SpriteObjectContents> sprites) {
        if (component instanceof ObjectComponent object && object.contents() instanceof SpriteObjectContents sprite) {
            sprites.add(sprite);
        }
        for (Component child : component.children()) {
            collectSprites(child, sprites);
        }
    }
}
