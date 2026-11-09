package org.johnfries.jZombies.service;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.johnfries.jZombies.config.ConfigService;
import org.johnfries.jZombies.data.PlayerDataStore;
import org.johnfries.jZombies.game.GameManager;
import org.johnfries.jZombies.game.GameState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ScoreboardService {
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final PlayerDataStore dataStore;
    private final GameManager gameManager;
    private BukkitTask task;

    public ScoreboardService(JavaPlugin plugin, ConfigService config, PlayerDataStore dataStore, GameManager gameManager) {
        this.plugin = plugin;
        this.config = config;
        this.dataStore = dataStore;
        this.gameManager = gameManager;
    }

    public void start() {
        stop();
        if (!config.isScoreboardEnabled()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, config.getScoreboardUpdateTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void updateAll() {
        if (!config.isScoreboardEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(build(player));
        }
    }

    private Scoreboard build(Player viewer) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("jz", "dummy", TextService.colorize(config.getScoreboardTitle()));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = new ArrayList<>();
        lines.add(TextService.colorize("&#FFD54FWave: &f" + gameManager.getCurrentWaveNumber()));
        lines.add(TextService.colorize("&#81D4FAZombies: &f" + gameManager.getTrackedZombieCount()));
        lines.add(TextService.colorize("&#FFAB91Time Left: &f" + gameManager.getWaveTimeRemainingSeconds() + "s"));
        lines.add(TextService.colorize("&8&m----------------"));
        int ownGold = dataStore.getProfile(viewer.getUniqueId()).getCoins();
        lines.add(TextService.colorize("&#FBC02DYour Gold: &6" + ownGold));
        lines.add(TextService.colorize("&7Players:"));

        List<Player> participants = gameManager.getParticipantPlayers().stream()
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int shown = 0;
        for (Player player : participants) {
            if (shown >= 6) {
                break;
            }
            int gold = dataStore.getProfile(player.getUniqueId()).getCoins();
            lines.add(TextService.colorize(" &f" + player.getName() + "&7: &6" + gold));
            shown++;
        }

        if (gameManager.getState() == GameState.WAITING) {
            lines.add(TextService.colorize("&aWaiting to start"));
        } else if (gameManager.getState() == GameState.STARTING) {
            lines.add(TextService.colorize("&eStarting..."));
        } else if (gameManager.getState() == GameState.ENDED) {
            lines.add(TextService.colorize("&cRound Ended"));
        }

        while (lines.size() > 15) {
            lines.remove(lines.size() - 1);
        }

        int score = lines.size();
        ChatColor[] colors = ChatColor.values();
        for (int i = 0; i < lines.size(); i++) {
            String entry = lines.get(i) + colors[i % colors.length];
            objective.getScore(entry).setScore(score--);
        }
        return scoreboard;
    }
}
