package ru.javaroot.smpshop.plat;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.javaroot.smpshop.cfg.Cfg;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class SoundSvc {
    private final Logger log;
    private final Supplier<Cfg> cfgSup;
    private final Exec exec;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public SoundSvc(Logger log, Supplier<Cfg> cfgSup, Exec exec) {
        this.log = log;
        this.cfgSup = cfgSup;
        this.exec = exec;
    }

    public void buyerPurchase(Player p) {
        Cfg.SoundCfg s = cfgSup.get().buyerSound();
        play(p, s);
    }

    public void sellerSale(Player p) {
        Cfg.SoundCfg s = cfgSup.get().sellerSound();
        play(p, s);
    }

    public void reload() {
        warned.clear();
    }

    private void play(Player p, Cfg.SoundCfg s) {
        if (!s.enabled() || s.name() == null || s.name().isBlank() || !p.isOnline()) {
            return;
        }
        Sound sound = resolve(s.name());
        if (sound == null) {
            return;
        }
        exec.entity(p, () -> p.playSound(p.getLocation(), sound, s.volume(), s.pitch()));
    }

    private Sound resolve(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.equals("block.vault.insert_fail")) {
            name = "block.vault.insert_item_fail";
        }
        NamespacedKey key = name.contains(":") ? NamespacedKey.fromString(name) : NamespacedKey.minecraft(name);
        Sound sound = key == null ? null : Registry.SOUNDS.get(key);
        if (sound == null && warned.add(raw)) {
            log.warning("Неизвестный звук в конфиге: " + raw);
        }
        return sound;
    }
}
