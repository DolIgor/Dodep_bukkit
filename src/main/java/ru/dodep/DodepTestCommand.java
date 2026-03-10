package ru.dodep;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class DodepTestCommand implements CommandExecutor {
    private final DodepPlugin plugin;

    public DodepTestCommand(DodepPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dodep.test")) {
            sender.sendMessage(ChatColor.RED + "Нет прав: dodep.test");
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(ChatColor.YELLOW + "Использование: /dodeptest <player> <amount>");
            return true;
        }

        String player = args[0];
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "amount должен быть числом");
            return true;
        }

        if (amount <= 0) {
            sender.sendMessage(ChatColor.RED + "amount должен быть > 0");
            return true;
        }

        if (!plugin.isWebhookAvailable()) {
            sender.sendMessage(ChatColor.RED + "Плагин загружен, но webhook-обработчик недоступен. Проверь /dodepstatus");
            return true;
        }

        plugin.triggerDonationFromCommand(player, amount, "manual-command");
        sender.sendMessage(ChatColor.GREEN + "Тестовый додеп отправлен: " + player + " -> " + amount);
        return true;
    }
}
