package ru.javaroot.sound;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.scheduler.Sched;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class SoundSvc {
    private final Logger logger;
    private final Cfg cfg;
    private final OtherMsg other;
    private final Sched sched;
    private final Set<String> invalidSounds = ConcurrentHashMap.newKeySet();

    public SoundSvc(Logger logger, Cfg cfg, OtherMsg other, Sched sched) {
        this.logger = logger;
        this.cfg = cfg;
        this.other = other;
        this.sched = sched;
    }

    public void buyerPurchase(Player player) {
        play(player, cfg.buyerPurchaseSoundEnabled(), cfg.buyerPurchaseSound(),
                cfg.buyerPurchaseVolume(), cfg.buyerPurchasePitch());
    }

    public void sellerSale(Player player) {
        play(player, cfg.sellerSaleSoundEnabled(), cfg.sellerSaleSound(),
                cfg.sellerSaleVolume(), cfg.sellerSalePitch());
    }

    public void reload() {
        invalidSounds.clear();
    }

    private void play(Player player, boolean enabled, String configuredName, float volume, float pitch) {
        if (!enabled || configuredName == null || configuredName.isBlank() || !player.isOnline()) {
            return;
        }
        Sound sound = resolve(configuredName);
        if (sound == null) {
            return;
        }
        sched.entity(player, () -> player.playSound(player.getLocation(), sound, volume, pitch));
    }

    private Sound resolve(String configuredName) {
        String name = configuredName.trim().toLowerCase(Locale.ROOT);
        if (name.equals("block.vault.insert_fail")) {
            name = "block.vault.insert_item_fail";
        }
        NamespacedKey key = name.contains(":") ? NamespacedKey.fromString(name) : NamespacedKey.minecraft(name);
        Sound sound = key == null ? null : Registry.SOUNDS.get(key);
        if (sound == null) {
            if (invalidSounds.add(configuredName)) {
                logger.warning(other.raw("unknown-sound", java.util.Map.of("sound", configuredName)));
            }
        }
        return sound;
    }
}
