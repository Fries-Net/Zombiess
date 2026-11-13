package net.fries.zombies.game.ui;

import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/** One owned sidebar per participant; original boards are restored by PlayerBackups. */
public final class MatchHud {
    private final MatchRun run;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, List<String>> previous = new HashMap<>();

    public MatchHud(MatchRun run) {
        this.run = run;
    }

    public void paused() {
        for (var p : run.world.getPlayers())
            p.sendActionBar(Component.text("Match paused", NamedTextColor.YELLOW));
    }

    public void tick() {
        for (var s : run.players.values()) {
            var p = Bukkit.getPlayer(s.id());
            if (p == null || s.life() == Survivor.Life.LEFT) continue;
            if (run.tick % 20 == 0) sidebar(p, s);
            if (run.tick % 5 != 0) continue;
            String text;
            if (!run.playing()) text = "Match paused";
            else if (s.life() == Survivor.Life.DOWNED)
                text =
                        "DOWNED • "
                                + ((s.bleedoutRemaining(run.tick) + 19) / 20)
                                + "s • wait for a teammate";
            else if (s.life() == Survivor.Life.DEAD) text = "Spectating • return next round";
            else {
                text = run.lives.channel(s.id());
                if (text.isEmpty()) text = run.interactions.context(p);
                if (text.isEmpty()) {
                    var w = run.combat.held(p);
                    text =
                            w == null
                                    ? "Select a weapon"
                                    : w.definition().name()
                                            + " • "
                                            + (w.reloading()
                                                    ? "Reloading "
                                                            + String.format(
                                                                    java.util.Locale.ROOT,
                                                                    "%.1fs",
                                                                    w.reloadRemaining(run.tick)
                                                                            / 20.0)
                                                    : w.magazine()
                                                            + " / "
                                                            + w.reserve()
                                                            + " • F to reload");
                }
            }
            p.sendActionBar(Component.text(text+s.goldFeedback(run.tick)+(run.points.doubleGoldRemaining(run.tick)>0?" • DOUBLE GOLD":""), NamedTextColor.GOLD));
        }
    }

    private void sidebar(Player p, Survivor s) {
        var board =
                boards.computeIfAbsent(
                        s.id(),
                        id -> {
                            var b = Bukkit.getScoreboardManager().getNewScoreboard();
                            var o =
                                    b.registerNewObjective(
                                            "zombies",
                                            Criteria.DUMMY,
                                            Component.text(
                                                    "FRIESNET ZOMBIES", NamedTextColor.GOLD));
                            o.setDisplaySlot(DisplaySlot.SIDEBAR);
                            o.numberFormat(
                                    io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
                            p.setScoreboard(b);
                            return b;
                        });
        int alive =
                (int)
                        run.players.values().stream()
                                .filter(v -> v.life() == Survivor.Life.ALIVE)
                                .count();
        var lines =
                List.of(
                        "§7" + run.map.name(),
                        "§fRound §c" + run.waves.round() + " / " + run.content.waves().finalRound(),
                        "§fZombies §c" + run.waves.remaining(),
                        "§fGold §6" + s.points(),
                        "§fKills §e" + s.kills(),
                        "§fTeam §a" + alive + " / " + run.session.snapshot().initialPlayerCount(),
                        "§7" + s.life().name(),
                        run.waves.intermissionRemaining() > 0
                                ? "§aNext wave: "
                                        + ((run.waves.intermissionRemaining() + 19) / 20)
                                        + "s"
                                : "§8Survive together",
                        "§8/zombies leave");
        if (lines.equals(previous.get(s.id()))) return;
        board.getEntries().forEach(board::resetScores);
        var objective = board.getObjective("zombies");
        int score = lines.size();
        for (var line : lines) Objects.requireNonNull(objective).getScore(line).setScore(score--);
        previous.put(s.id(), lines);
    }

    public void clear() {
        boards.clear();
        previous.clear();
    }
}
