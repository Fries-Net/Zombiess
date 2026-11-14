package net.fries.zombies.game;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class ZombiesCommand implements BasicCommand {
    private final ZombiesPlugin plugin;

    ZombiesCommand(ZombiesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("zombies.use")) {
            message(sender, "Permission required: zombies.use", true);
            return;
        }
        String command = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (command.equals("editor")) {
            if (!(sender instanceof Player p)
                    || !(sender.hasPermission("zombies.editor")
                            || sender.hasPermission("zombies.admin"))) {
                message(sender, "Editor requires a player with zombies.editor", true);
                return;
            }
            if (!plugin.operational()) {
                message(sender, "Editor is unavailable during startup or recovery", true);
                return;
            }
            plugin.matches()
                    .editor()
                    .execute(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > (command.equals("join") ? 2 : 1)) {
            message(sender, "Usage: /zombies join [map]|leave|reloadgun|status|profile", true);
            return;
        }
        if (command.equals("status")) {
            message(
                    sender,
                    "FriesNet Zombies • "
                            + plugin.configuration().serverId()
                            + " • "
                            + (plugin.operational()
                                    ? "playable " + plugin.matches().status()
                                    : "storage unavailable or initializing"),
                    false);
            return;
        }
        if (List.of("join", "leave", "reloadgun").contains(command)) {
            if (!(sender instanceof Player player)) {
                message(sender, "This command requires a player.", true);
                return;
            }
            switch (command) {
                case "join" ->
                        plugin.matches().join(player, args.length == 2 ? args[1] : "training");
                case "leave" -> plugin.matches().leave(player);
                case "reloadgun" -> {
                    var run = plugin.matches().current();
                    if (run != null && run.combat != null) run.combat.reload(player);
                }
            }
            return;
        }
        if (command.equals("profile")) {
            if (!(sender instanceof Player player)) {
                message(sender, "This command requires a player.", true);
                return;
            }
            var id = player.getUniqueId();
            plugin.accounts()
                    .account(id)
                    .whenComplete(
                            (account, error) ->
                                    plugin.onServer(
                                            () -> {
                                                var online = plugin.getServer().getPlayer(id);
                                                if (online == null) return;
                                                if (error != null) {
                                                    message(
                                                            online,
                                                            "Account unavailable. Please try again"
                                                                    + " shortly.",
                                                            true);
                                                    return;
                                                }
                                                message(
                                                        online,
                                                        "Tokens: "
                                                                + account.tokens()
                                                                + " • Experience: "
                                                                + account.experience()
                                                                + " • Matches: "
                                                                + account.matchesPlayed()
                                                                + " • Highest round: "
                                                                + account.highestRound(),
                                                        false);
                                            }));
            return;
        }
        if (!sender.hasPermission("zombies.admin")) {
            message(sender, "Permission required: zombies.admin", true);
            return;
        }
        switch (command) {
            case "pause", "resume", "stop" -> {
                try {
                    if (command.equals("stop")) plugin.matches().stopMatch();
                    else plugin.matches().pause(command.equals("pause"));
                    message(sender, "Match command accepted.", false);
                } catch (IllegalStateException e) {
                    message(sender, e.getMessage(), true);
                }
            }
            case "health" ->
                    plugin.accounts()
                            .healthy()
                            .whenComplete(
                                    (healthy, error) ->
                                            plugin.onServer(
                                                    () -> {
                                                        var metrics = plugin.accounts().metrics();
                                                        message(
                                                                sender,
                                                                "Database: "
                                                                        + (error == null
                                                                                        && Boolean
                                                                                                .TRUE
                                                                                                .equals(
                                                                                                        healthy)
                                                                                ? "healthy"
                                                                                : "unavailable")
                                                                        + " • Queue: "
                                                                        + metrics.queued()
                                                                        + " • Workers: "
                                                                        + metrics.active()
                                                                        + " • Failures: "
                                                                        + metrics.failures()
                                                                        + " • Last operation: "
                                                                        + metrics
                                                                                .lastOperationMicros()
                                                                        + " μs",
                                                                error != null);
                                                    }));
            case "validate", "reload" -> {
                try {
                    if (command.equals("reload")) plugin.reloadConfiguration();
                    else plugin.readConfiguration();
                    message(
                            sender,
                            command.equals("reload")
                                    ? "Configuration reloaded. New match rules apply to future"
                                            + " sessions."
                                    : "Configuration is valid.",
                            false);
                } catch (IOException | IllegalArgumentException e) {
                    message(sender, "Configuration rejected: " + e.getMessage(), true);
                }
            }
            default ->
                    message(sender, "Usage: /zombies status|profile|health|validate|reload", true);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission("zombies.use")) return List.of();
        if (args.length > 1) {
            if (args[0].equalsIgnoreCase("editor")
                    && plugin.operational()
                    && source.getSender() instanceof Player p
                    && (p.hasPermission("zombies.editor") || p.hasPermission("zombies.admin")))
                return plugin.matches()
                        .editor()
                        .suggest(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            if (args[0].equalsIgnoreCase("join") && args.length == 2 && plugin.operational())
                return plugin.matches().catalog().maps().keySet().stream()
                        .filter(m -> m.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            return List.of();
        }
        var choices =
                source.getSender().hasPermission("zombies.admin")
                        ? List.of(
                                "join",
                                "leave",
                                "reloadgun",
                                "status",
                                "profile",
                                "health",
                                "validate",
                                "reload",
                                "pause",
                                "resume",
                                "stop",
                                "editor")
                        : List.of("join", "leave", "reloadgun", "status", "profile");
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }

    private void message(CommandSender sender, String text, boolean error) {
        sender.sendMessage(Component.text(text, error ? NamedTextColor.RED : NamedTextColor.GOLD));
    }
}
