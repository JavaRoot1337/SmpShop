package ru.javaroot.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public class ShopTab implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("help", "set", "balance", "delete", "remove", "list", "link", "reload").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("link")) {
            return List.of("tg", "ds", "max").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("link") && args[1].equalsIgnoreCase("ds")) {
            return "webhook".startsWith(args[2].toLowerCase(Locale.ROOT))
                    ? List.of("webhook")
                    : List.of();
        }
        return List.of();
    }
}
