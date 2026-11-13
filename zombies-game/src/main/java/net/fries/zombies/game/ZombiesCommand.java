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
    ZombiesCommand(ZombiesPlugin plugin) { this.plugin = plugin; }

    @Override public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("zombies.use")) { message(sender, "Permission required: zombies.use", true); return; }
        String command = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) { message(sender, "Usage: /zombies status|profile|health|validate|reload", true); return; }
        if (command.equals("status")) {
            message(sender, "FriesNet Zombies • " + plugin.configuration().serverId() + " • "
                    + (plugin.operational() ? "foundation ready" : "storage unavailable or initializing"), false);
            message(sender, "Phase 1 foundation. Playable matches arrive in Phase 2.", false);
            return;
        }
        if (command.equals("profile")) {
            if (!(sender instanceof Player player)) { message(sender, "This command requires a player.", true); return; }
            var id = player.getUniqueId();
            plugin.accounts().account(id).whenComplete((account, error) -> plugin.onServer(() -> {
                var online = plugin.getServer().getPlayer(id);
                if (online == null) return;
                if (error != null) { message(online, "Account unavailable. Please try again shortly.", true); return; }
                message(online, "Tokens: " + account.tokens() + " • Experience: " + account.experience()
                        + " • Matches: " + account.matchesPlayed() + " • Highest round: " + account.highestRound(), false);
            }));
            return;
        }
        if (!sender.hasPermission("zombies.admin")) { message(sender, "Permission required: zombies.admin", true); return; }
        switch (command) {
            case "health" -> plugin.accounts().healthy().whenComplete((healthy, error) -> plugin.onServer(() -> {
                var metrics = plugin.accounts().metrics();
                message(sender, "Database: " + (error == null && Boolean.TRUE.equals(healthy) ? "healthy" : "unavailable")
                        + " • Queue: " + metrics.queued() + " • Workers: " + metrics.active()
                        + " • Failures: " + metrics.failures() + " • Last operation: " + metrics.lastOperationMicros() + " μs", error != null);
            }));
            case "validate", "reload" -> {
                try {
                    if (command.equals("reload")) plugin.reloadConfiguration(); else plugin.readConfiguration();
                    message(sender, command.equals("reload") ? "Configuration reloaded. New match rules apply to future sessions."
                            : "Configuration is valid.", false);
                } catch (IOException | IllegalArgumentException e) { message(sender, "Configuration rejected: " + e.getMessage(), true); }
            }
            default -> message(sender, "Usage: /zombies status|profile|health|validate|reload", true);
        }
    }

    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission("zombies.use") || args.length > 1) return List.of();
        var choices = source.getSender().hasPermission("zombies.admin")
                ? List.of("status", "profile", "health", "validate", "reload") : List.of("status", "profile");
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }
    private void message(CommandSender sender, String text, boolean error) {
        sender.sendMessage(Component.text(text, error ? NamedTextColor.RED : NamedTextColor.GOLD));
    }
}
