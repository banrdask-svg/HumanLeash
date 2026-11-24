package com.olis.humanleash;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;

public final class LeashCommand implements CommandExecutor, TabCompleter {
    private final HumanLeashPlugin plugin;
    private final LeashManager manager;
    private final RopeRenderer ropes;

    public LeashCommand(HumanLeashPlugin plugin, LeashManager manager, RopeRenderer ropes) {
        this.plugin = plugin;
        this.manager = manager;
        this.ropes = ropes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("humanleash.admin")) {
            error(sender, "У вас нет права humanleash.admin.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "bind", "tie" -> bind(sender, args);
                case "unbind", "untie" -> unbind(sender, args);
                case "distance", "length" -> distance(sender, args);
                case "info" -> info(sender, args);
                case "reload" -> {
                    requireLength(args, 1, "/leash reload");
                    plugin.reloadSettings();
                    send(sender, "Настройки перечитаны. Длина существующих поводков сохранена.");
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage());
        } catch (InvalidConfigurationException exception) {
            plugin.getLogger().log(Level.WARNING, "Некорректный config.yml.", exception);
            error(sender, "Некорректный config.yml. Предыдущие настройки сохранены.");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось прочитать настройки или сохранить изменение поводка.", exception);
            error(sender, "Ошибка чтения или сохранения. Изменение отменено; подробности в консоли сервера.");
        }
        return true;
    }

    private void bind(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Использование: /leash bind <игрок> [держатель] [дистанция]");
        }
        Player target = online(args[1]);
        Player holder;
        double length = plugin.defaultDistance();
        if (args.length == 4) {
            holder = online(args[2]);
            length = parseDistance(args[3]);
        } else if (args.length == 3 && plugin.getServer().getPlayerExact(args[2]) != null) {
            holder = online(args[2]);
        } else {
            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException("Из консоли: /leash bind <игрок> <держатель> [дистанция]");
            }
            holder = player;
            if (args.length == 3) {
                length = parseDistance(args[2]);
            }
        }
        manager.bind(target, holder, length);
        send(sender, target.getName() + " привязан к " + holder.getName() + ". Дистанция: " + length + " блоков.");
        send(target, "Вы привязаны к " + holder.getName() + ". Снять поводок можно только командой.");
        if (!holder.getUniqueId().equals(target.getUniqueId()) && !holder.equals(sender)) {
            send(holder, "К вам привязан " + target.getName() + ". Дистанция: " + length + " блоков.");
        }
    }

    private void unbind(CommandSender sender, String[] args) throws IOException {
        requireLength(args, 2, "/leash unbind <игрок>");
        LeashLink link = bound(args[1]);
        manager.unbind(link.targetId());
        ropes.remove(link.targetId());
        send(sender, "Поводок с " + link.targetName() + " снят.");
        Player target = plugin.getServer().getPlayer(link.targetId());
        if (target != null && !target.equals(sender)) {
            send(target, "С вас сняли поводок.");
        }
    }

    private void distance(CommandSender sender, String[] args) throws IOException {
        requireLength(args, 3, "/leash distance <игрок> <блоки>");
        LeashLink link = bound(args[1]);
        double length = parseDistance(args[2]);
        manager.setDistance(link.targetId(), length);
        send(sender, "Дистанция поводка для " + link.targetName() + ": " + length + " блоков.");
    }

    private void info(CommandSender sender, String[] args) {
        requireLength(args, 2, "/leash info <игрок>");
        LeashLink link = bound(args[1]);
        send(sender, link.targetName() + " → " + link.holderName() + ", дистанция: " + link.distance() + " блоков.");
    }

    private Player online(String name) {
        Player player = plugin.getServer().getPlayerExact(name);
        if (player == null) {
            throw new IllegalArgumentException("Игрок " + name + " должен быть онлайн.");
        }
        return player;
    }

    private LeashLink bound(String name) {
        LeashLink link = manager.find(name);
        if (link == null) {
            throw new IllegalArgumentException("Игрок " + name + " не привязан.");
        }
        return link;
    }

    private double parseDistance(String value) {
        double length;
        try {
            length = Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Дистанция должна быть числом, например 15 или 7.5.", exception);
        }
        LeashLink.validateDistance(length, plugin.maxDistance());
        return length;
    }

    private static void requireLength(String[] args, int count, String usage) {
        if (args.length != count) {
            throw new IllegalArgumentException("Использование: " + usage);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "/leash bind <игрок> [дистанция] — привязать к себе (по умолчанию "
                + plugin.defaultDistance() + " блоков)");
        send(sender, "/leash bind <игрок> <держатель> [дистанция] — привязать к другому игроку");
        send(sender, "/leash unbind <игрок> — снять поводок, в том числе с офлайн-игрока");
        send(sender, "/leash distance <игрок> <блоки> — изменить длину");
        send(sender, "/leash info <игрок> — посмотреть привязку");
        send(sender, "/leash reload — перечитать config.yml");
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Поводок] " + message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[Поводок] " + message, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("humanleash.admin") || args.length == 0) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            suggestions.addAll(List.of("bind", "unbind", "distance", "info", "reload"));
        } else if (args.length == 2 && List.of("bind", "tie", "unbind", "untie", "distance", "length", "info").contains(action)) {
            if (action.equals("bind") || action.equals("tie")) {
                plugin.getServer().getOnlinePlayers().forEach(player -> suggestions.add(player.getName()));
            } else {
                manager.links().forEach(link -> suggestions.add(link.targetName()));
            }
        } else if ((args.length == 3 || args.length == 4) && (action.equals("bind") || action.equals("tie"))) {
            suggestions.addAll(Arrays.asList("5", "10", "15", "20", "30"));
            if (args.length == 3) {
                plugin.getServer().getOnlinePlayers().forEach(player -> suggestions.add(player.getName()));
            }
        } else if (args.length == 3 && (action.equals("distance") || action.equals("length"))) {
            suggestions.addAll(Arrays.asList("5", "10", "15", "20", "30"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
