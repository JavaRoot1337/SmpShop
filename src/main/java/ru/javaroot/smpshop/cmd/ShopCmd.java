package ru.javaroot.smpshop.cmd;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.javaroot.smpshop.cfg.Cfg;
import ru.javaroot.smpshop.cfg.CfgLoad;
import ru.javaroot.smpshop.cfg.Msg;
import ru.javaroot.smpshop.gui.BalGui;
import ru.javaroot.smpshop.gui.ListGui;
import ru.javaroot.smpshop.hook.HookHub;
import ru.javaroot.smpshop.plat.Exec;
import ru.javaroot.smpshop.shop.PendingAct;
import ru.javaroot.smpshop.shop.ShopMgr;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class ShopCmd {
    public static final String PERM_USE = "smpshop.use";
    public static final String PERM_BALANCE = "smpshop.balance";
    public static final String PERM_LINK = "smpshop.link";
    public static final String PERM_PLUS = "smpshop.plus";
    public static final String PERM_ADMIN_REMOVE = "smpshop.admin.remove";
    public static final String PERM_ADMIN_LIST = "smpshop.admin.list";
    public static final String PERM_ADMIN_RELOAD = "smpshop.admin.reload";

    private final Logger log;
    private final CfgLoad cfgLoad;
    private final ShopMgr shops;
    private final PendingAct pending;
    private final Exec exec;
    private final HookHub hooks;
    private final BalGui balGui;
    private final ListGui listGui;
    private final BooleanSupplier readySup;
    private final LongSupplier reloadAct;

    public ShopCmd(Logger log, CfgLoad cfgLoad, ShopMgr shops, PendingAct pending, Exec exec,
                   HookHub hooks, BalGui balGui, ListGui listGui,
                   BooleanSupplier readySup, LongSupplier reloadAct) {
        this.log = log;
        this.cfgLoad = cfgLoad;
        this.shops = shops;
        this.pending = pending;
        this.exec = exec;
        this.hooks = hooks;
        this.balGui = balGui;
        this.listGui = listGui;
        this.readySup = readySup;
        this.reloadAct = reloadAct;
    }

    public void register(Commands cmds) {
        cmds.register(build().build(), "Управление магазинами на сундуках", List.of("shop"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("smpshop")
                .requires(s -> s.getSender().hasPermission(PERM_USE))
                .executes(ctx -> help(ctx.getSource().getSender()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())))
                .then(Commands.literal("set")
                        .executes(ctx -> help(ctx.getSource().getSender()))
                        .then(Commands.argument("price", IntegerArgumentType.integer())
                                .executes(ctx -> set(ctx.getSource().getSender(),
                                        IntegerArgumentType.getInteger(ctx, "price")))))
                .then(Commands.literal("balance")
                        .requires(s -> s.getSender().hasPermission(PERM_BALANCE))
                        .executes(ctx -> balance(ctx.getSource().getSender())))
                .then(Commands.literal("delete")
                        .executes(ctx -> beginAction(ctx.getSource().getSender(),
                                PendingAct.Type.DELETE, "shop-delete-pending")))
                .then(Commands.literal("remove")
                        .requires(s -> s.getSender().hasPermission(PERM_ADMIN_REMOVE))
                        .executes(ctx -> beginAction(ctx.getSource().getSender(),
                                PendingAct.Type.REMOVE, "shop-remove-pending")))
                .then(Commands.literal("list")
                        .executes(ctx -> listSelf(ctx.getSource().getSender()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(s -> s.getSender().hasPermission(PERM_ADMIN_LIST))
                                .suggests((ctx, b) -> {
                                    suggestPlayers(b);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> listOther(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("link")
                        .requires(s -> s.getSender().hasPermission(PERM_LINK))
                        .then(Commands.literal("tg").executes(ctx -> linkTg(ctx.getSource().getSender())))
                        .then(Commands.literal("ds")
                                .then(Commands.literal("webhook")
                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> linkDs(ctx.getSource().getSender(),
                                                        StringArgumentType.getString(ctx, "url")))))))
                .then(Commands.literal("reload")
                        .requires(s -> s.getSender().hasPermission(PERM_ADMIN_RELOAD))
                        .executes(ctx -> reload(ctx.getSource().getSender())));
    }

    private int help(CommandSender snd) {
        cfgLoad.msg().send(snd, "help");
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandSender snd, int price) {
        Player p = requireReadyPlayer(snd);
        if (p == null) {
            return Command.SINGLE_SUCCESS;
        }
        Cfg cfg = cfgLoad.cfg();
        Msg msg = cfgLoad.msg();
        if (price < cfg.minPrice() || price > cfg.maxPrice()) {
            msg.send(p, "price-range", Map.of("min", cfg.minPrice(), "max", cfg.maxPrice()));
            return Command.SINGLE_SUCCESS;
        }
        int limit = p.hasPermission(PERM_PLUS) ? cfg.plusMaxPerPlayer() : cfg.maxPerPlayer();
        if (shops.count(p.getUniqueId()) >= limit) {
            msg.send(p, "shop-limit", Map.of("limit", limit));
            return Command.SINGLE_SUCCESS;
        }
        pending.put(p.getUniqueId(), PendingAct.Type.PLACE, price, cfg.placementTimeoutSec());
        msg.send(p, "placement-pending", Map.of("price", price));
        return Command.SINGLE_SUCCESS;
    }

    private int balance(CommandSender snd) {
        Player p = requireReadyPlayer(snd);
        if (p != null) {
            balGui.open(p);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int beginAction(CommandSender snd, PendingAct.Type type, String key) {
        Player p = requireReadyPlayer(snd);
        if (p != null) {
            pending.put(p.getUniqueId(), type, 0, cfgLoad.cfg().placementTimeoutSec());
            cfgLoad.msg().send(p, key);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listSelf(CommandSender snd) {
        Player p = requireReadyPlayer(snd);
        if (p != null) {
            listGui.open(p, p.getUniqueId());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listOther(CommandSender snd, String name) {
        Player p = requireReadyPlayer(snd);
        if (p == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID ownerId = resolveOwnerUuid(name);
        if (ownerId == null) {
            cfgLoad.msg().send(p, "shop-not-found");
            return Command.SINGLE_SUCCESS;
        }
        listGui.open(p, ownerId);
        return Command.SINGLE_SUCCESS;
    }

    private UUID resolveOwnerUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        UUID known = shops.findOwnerByName(name);
        if (known != null) {
            return known;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private int linkTg(CommandSender snd) {
        Player p = requireReadyPlayer(snd);
        if (p == null) {
            return Command.SINGLE_SUCCESS;
        }
        hooks.createTelegramCode(p.getUniqueId())
                .thenAccept(code -> exec.entity(p, () -> sendTelegramLink(p, code)))
                .exceptionally(err -> {
                    exec.entity(p, () -> cfgLoad.msg().send(p, "integration-disabled"));
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    private void sendTelegramLink(Player p, String code) {
        Cfg cfg = cfgLoad.cfg();
        Msg msg = cfgLoad.msg();
        if (!cfg.tgReferralBot()) {
            Component comp = msg.comp("link-code-prefix")
                    .append(Component.text(code, NamedTextColor.AQUA).clickEvent(ClickEvent.copyToClipboard("/code " + code)))
                    .append(msg.comp("link-code-suffix"));
            p.sendMessage(comp);
            return;
        }
        String bot = cfg.tgUserBot();
        if (bot == null || bot.isBlank()) {
            msg.send(p, "telegram-referral-not-configured");
            return;
        }
        String url = msg.raw("telegram-referral-url-template", Map.of("bot", bot, "code", code));
        Component link = msg.comp("telegram-referral-suffix").clickEvent(ClickEvent.openUrl(url));
        p.sendMessage(msg.comp("telegram-referral-prefix").append(link));
    }

    private int linkDs(CommandSender snd, String url) {
        Player p = requireReadyPlayer(snd);
        if (p == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (!cfgLoad.cfg().dsEnabled()) {
            cfgLoad.msg().send(p, "integration-disabled");
            return Command.SINGLE_SUCCESS;
        }
        hooks.saveDiscordWebhook(p.getUniqueId(), url)
                .thenAccept(ok -> exec.entity(p, () ->
                        cfgLoad.msg().send(p, ok ? "discord-webhook-saved" : "discord-webhook-invalid")))
                .exceptionally(err -> {
                    exec.entity(p, () -> cfgLoad.msg().send(p, "discord-webhook-invalid"));
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender snd) {
        if (exec.async(() -> {
            try {
                long ms = reloadAct.getAsLong();
                exec.sender(snd, () -> cfgLoad.msg().send(snd, "reload", Map.of("ms", ms)));
            } catch (RuntimeException ex) {
                log.warning("Не удалось перезагрузить конфигурацию: " + ex.getMessage());
                exec.sender(snd, () -> cfgLoad.msg().send(snd, "reload-failed"));
            }
        }) != Exec.Result.SCHEDULED) {
            cfgLoad.msg().send(snd, "reload-failed");
        }
        return Command.SINGLE_SUCCESS;
    }

    private Player requireReadyPlayer(CommandSender snd) {
        if (!(snd instanceof Player p)) {
            cfgLoad.msg().send(snd, "no-player");
            return null;
        }
        if (!readySup.getAsBoolean()) {
            cfgLoad.msg().send(p, "loading");
            return null;
        }
        return p;
    }

    private static void suggestPlayers(SuggestionsBuilder b) {
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(rem)) {
                b.suggest(p.getName());
            }
        }
    }
}
