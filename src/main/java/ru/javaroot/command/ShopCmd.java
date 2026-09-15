package ru.javaroot.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.javaroot.SmpShop;
import ru.javaroot.config.Cfg;
import ru.javaroot.config.Msg;
import ru.javaroot.config.OtherMsg;
import ru.javaroot.gui.BalanceInv;
import ru.javaroot.gui.ShopListInv;
import ru.javaroot.integration.IntegrationHub;
import ru.javaroot.scheduler.Sched;
import ru.javaroot.sound.SoundSvc;
import ru.javaroot.shop.ShopMgr;
import ru.javaroot.logersxd.Loggers;

import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShopCmd implements CommandExecutor {
    private final SmpShop plugin;
    private final Loggers.Channel logger;
    private final Cfg cfg;
    private final Msg msg;
    private final OtherMsg other;
    private final ShopMgr shops;
    private final Sched sched;
    private final IntegrationHub integrations;
    private final SoundSvc sounds;
    private final BalanceInv balanceGui;
    private final ShopListInv listGui;
    private final Map<UUID, Action> actions = new ConcurrentHashMap<>();

    public ShopCmd(SmpShop plugin, Cfg cfg, Msg msg, OtherMsg other, ShopMgr shops, Sched sched,
                   IntegrationHub integrations, SoundSvc sounds, BalanceInv balanceGui, ShopListInv listGui) {
        this(plugin, cfg, msg, other, new Loggers(plugin.getLogger()).config(), shops, sched,
                integrations, sounds, balanceGui, listGui);
    }

    public ShopCmd(SmpShop plugin, Cfg cfg, Msg msg, OtherMsg other, Loggers.Channel logger,
                   ShopMgr shops, Sched sched, IntegrationHub integrations, SoundSvc sounds,
                   BalanceInv balanceGui, ShopListInv listGui) {
        this.plugin = plugin;
        this.logger = logger;
        this.cfg = cfg;
        this.msg = msg;
        this.other = other;
        this.shops = shops;
        this.sched = sched;
        this.integrations = integrations;
        this.sounds = sounds;
        this.balanceGui = balanceGui;
        this.listGui = listGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg.text("no-player"));
            return true;
        }
        if (!player.hasPermission(PermissionKeys.USE)) {
            msg.send(player, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            showHelp(player);
            return true;
        }
        if (!validArguments(args)) {
            showHelp(player);
            return true;
        }
        if (!plugin.isReady() && !args[0].equalsIgnoreCase("reload")) {
            msg.send(player, "loading");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> set(player, args);
            case "balance" -> balance(player);
            case "delete" -> beginAction(player, ActionType.DELETE, "shop-delete-pending");
            case "remove" -> remove(player);
            case "list" -> list(player, args);
            case "link" -> link(player, args);
            case "reload" -> reload(player);
            default -> showHelp(player);
        }
        return true;
    }

    public Action action(UUID playerId) {
        Action action = actions.get(playerId);
        if (action != null && action.expiresAt() < System.currentTimeMillis()) {
            actions.remove(playerId);
            return null;
        }
        return action;
    }

    public void clearAction(UUID playerId) {
        actions.remove(playerId);
    }

    private void set(Player player, String[] args) {
        if (args.length != 2) {
            showHelp(player);
            return;
        }
        int price;
        try {
            price = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            msg.send(player, "price-invalid");
            return;
        }
        if (price < cfg.minPrice() || price > cfg.maxPrice()) {
            msg.send(player, "price-range", Map.of("min", cfg.minPrice(), "max", cfg.maxPrice()));
            return;
        }
        int limit = player.hasPermission(PermissionKeys.PLUS) ? cfg.plusShopLimit() : cfg.regularShopLimit();
        if (shops.count(player.getUniqueId()) >= limit) {
            msg.send(player, "shop-limit", Map.of("limit", limit));
            return;
        }
        actions.put(player.getUniqueId(), new Action(ActionType.PLACE, price, System.currentTimeMillis() + cfg.placementTimeoutSeconds() * 1000L));
        msg.send(player, "placement-pending", Map.of("price", price));
    }

    private void balance(Player player) {
        if (!player.hasPermission(PermissionKeys.BALANCE)) {
            msg.send(player, "no-permission");
            return;
        }
        balanceGui.open(player);
    }

    private void remove(Player player) {
        if (!player.hasPermission(PermissionKeys.ADMIN_REMOVE)) {
            msg.send(player, "no-permission");
            return;
        }
        beginAction(player, ActionType.REMOVE, "shop-remove-pending");
    }

    private void list(Player player, String[] args) {
        UUID ownerId = player.getUniqueId();
        if (args.length > 1) {
            if (!player.hasPermission(PermissionKeys.ADMIN_LIST)) {
                msg.send(player, "no-permission");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                msg.send(player, "shop-not-found");
                return;
            }
            ownerId = target.getUniqueId();
        }
        listGui.open(player, ownerId);
    }

    private void link(Player player, String[] args) {
        if (!player.hasPermission(PermissionKeys.LINK)) {
            msg.send(player, "no-permission");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("max")) {
            msg.send(player, "link-max");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("tg")) {
            integrations.createTelegramCode(player.getUniqueId())
                    .thenAccept(code -> sched.entity(player, () -> {
                        if (cfg.telegramReferralBot()) {
                            String botName = cfg.telegramUserBot();
                            if (botName == null || botName.isBlank()) {
                                msg.send(player, "telegram-referral-not-configured");
                            } else {
                                msg.sendTelegramReferral(player, botName, code);
                            }
                        } else {
                            msg.sendCode(player, code);
                        }
                    }))
                    .exceptionally(error -> {
                        sched.entity(player, () -> msg.send(player, "integration-disabled"));
                        return null;
                    });
            return;
        }
        if (args.length != 4 || !args[1].equalsIgnoreCase("ds") || !args[2].equalsIgnoreCase("webhook")) {
            showHelp(player);
            return;
        }
        if (!cfg.discordEnabled()) {
            msg.send(player, "integration-disabled");
            return;
        }
        integrations.saveDiscordWebhook(player.getUniqueId(), args[3])
                .thenAccept(success -> sched.entity(player, () -> msg.send(player,
                        success ? "discord-webhook-saved" : "discord-webhook-invalid")))
                .exceptionally(error -> {
                    sched.entity(player, () -> msg.send(player, "discord-webhook-invalid"));
                    return null;
                });
    }

    private void reload(Player player) {
        if (!player.hasPermission(PermissionKeys.ADMIN_RELOAD)) {
            msg.send(player, "no-permission");
            return;
        }
        if (sched.async(() -> reloadAsync(player)) != Sched.ScheduleResult.SCHEDULED) {
            msg.send(player, "reload-failed");
        }
    }

    private void reloadAsync(Player player) {
        org.bukkit.configuration.file.FileConfiguration previousCfg = cfg.snapshot();
        org.bukkit.configuration.file.FileConfiguration previousMessages = msg.snapshot();
        org.bukkit.configuration.file.FileConfiguration previousOtherMessages = other.snapshot();
        IntegrationHub.MessageState previousIntegrationMessages = integrations.messageState();
        boolean restartAttempted = false;
        try {
            cfg.reload();
            other.reload();
            integrations.reloadMessages();
            msg.reload();
            sounds.reload();
            restartAttempted = true;
            integrations.reload(sched);
            sched.entity(player, () -> msg.send(player, "reload"));
        } catch (RuntimeException exception) {
            cfg.restore(previousCfg);
            msg.restore(previousMessages);
            other.restore(previousOtherMessages);
            integrations.restoreMessages(previousIntegrationMessages);
            sounds.reload();
            if (restartAttempted) {
                try {
                    integrations.reload(sched);
                } catch (RuntimeException restoreError) {
                    exception.addSuppressed(restoreError);
                }
            }
            logger.warning(other.raw("config-reload-failed",
                    java.util.Map.of("error", String.valueOf(exception.getMessage()))));
            sched.entity(player, () -> msg.send(player, "reload-failed"));
        }
    }

    private void beginAction(Player player, ActionType type, String key) {
        actions.put(player.getUniqueId(), new Action(type, 0, System.currentTimeMillis() + cfg.placementTimeoutSeconds() * 1000L));
        msg.send(player, key);
    }

    private void showHelp(Player player) {
        msg.send(player, "help");
    }

    private boolean validArguments(String[] args) {
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> args.length == 2;
            case "balance", "delete", "remove", "reload" -> args.length == 1;
            case "list" -> args.length == 1 || args.length == 2;
            case "link" -> (args.length == 2 && (args[1].equalsIgnoreCase("tg")
                    || args[1].equalsIgnoreCase("ds")
                    || args[1].equalsIgnoreCase("max")))
                    || (args.length == 4 && args[1].equalsIgnoreCase("ds")
                    && args[2].equalsIgnoreCase("webhook"));
            default -> false;
        };
    }

    public record Action(ActionType type, int price, long expiresAt) {
    }

    public enum ActionType {
        PLACE,
        DELETE,
        REMOVE
    }
}
