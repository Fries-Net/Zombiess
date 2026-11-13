package net.fries.zombies.game;

import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.common.match.*;
import net.fries.zombies.game.combat.CombatService;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.player.PlayerRestoration;
import net.fries.zombies.game.ui.MatchHud;
import net.fries.zombies.game.world.*;
import net.fries.zombies.persistence.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Coordinates guarded lifecycle operations; gameplay, content and player recovery have separate
 * owners.
 */
public final class MatchCoordinator {
    private final ZombiesPlugin plugin;
    private final DurableFiles disk = new DurableFiles();
    private final ResultJournal journal;
    private final PlayerRestoration players;
    private final MapWorlds worlds;
    private final Set<UUID> queued = new LinkedHashSet<>();
    private ContentCatalog catalog;
    private String queueMap;
    private long wallTick, queueAt;
    private MatchRun run;
    private boolean stopped, available;

    public MatchCoordinator(ZombiesPlugin plugin) {
        this.plugin = plugin;
        Path data = plugin.getDataFolder().toPath();
        journal = new ResultJournal(data.resolve("data/results"), disk);
        players =
                new PlayerRestoration(
                        plugin, data.resolve("data/players"), disk, plugin::onServer, this::log);
        worlds = new MapWorlds(plugin, disk);
    }

    public CompletableFuture<Integer> initialize() {
        var content = disk.submit(() -> ContentCatalog.load(plugin.getDataFolder().toPath()));
        return content.thenCombine(worlds.recover(), (c, v) -> c)
                .thenCombine(plugin.accounts().initialized(), (c, v) -> c)
                .thenCompose(
                        c -> {
                            catalog = c;
                            return journal.recover(plugin.accounts());
                        });
    }

    public void ready() {
        for (var w : catalog.gameplay().weapons().values()) {
            var material = Material.matchMaterial(w.material());
            if (material == null || material.isAir() || !material.isItem())
                throw new IllegalArgumentException("Invalid weapon material");
        }
        for (var m : catalog.maps().values()) {
            m.blocks().forEach(f -> MapWorlds.material(f.material()));
            m.barricades().forEach(b -> MapWorlds.material(b.material()));
        }
        available = true;
        plugin.getServer().getOnlinePlayers().forEach(this::recoverPlayer);
    }

    public String status() {
        if (!available) return "recovering or unavailable";
        if (run == null) return "ready • maps: " + String.join(", ", catalog.maps().keySet());
        return run.session.snapshot().phase()
                + " • "
                + run.map.name()
                + (run.waves == null ? "" : " • round " + run.waves.round());
    }

    public MatchRun current() {
        return run;
    }

    public boolean managed(UUID id) {
        return players.managed(id)
                || (run != null
                        && run.players.containsKey(id)
                        && run.players.get(id).life() != Survivor.Life.LEFT);
    }

    public void join(Player player, String map) {
        if (!available || !plugin.operational()) {
            message(player, "Matches are unavailable while storage or recovery is unavailable.");
            return;
        }
        if (managed(player.getUniqueId())) {
            message(player, "Finish recovery or leave your current match first.");
            return;
        }
        if (run != null) {
            message(player, "A match is already reserved. Join again when it finishes.");
            return;
        }
        if (!catalog.maps().containsKey(map)) {
            message(player, "Maps: " + String.join(", ", catalog.maps().keySet()));
            return;
        }
        if (!queued.isEmpty() && !map.equals(queueMap)) {
            message(player, "The lobby is gathering for " + queueMap);
            return;
        }
        if (queued.size() >= plugin.configuration().matchRules().maxPlayers()) {
            message(player, "Lobby is full.");
            return;
        }
        if (queued.isEmpty()) {
            queueMap = map;
            queueAt = wallTick + 100;
        }
        queued.add(player.getUniqueId());
        message(
                player,
                "Joined "
                        + catalog.maps().get(map).name()
                        + " • gathering teammates, then countdown. /zombies leave to exit.");
    }

    public void leave(Player player) {
        depart(player);
        players.restore(player);
        message(player, "Left Zombies. Your original equipment will be restored.");
    }

    public void quit(Player player) {
        depart(player);
        players.quit(player);
    }

    private void depart(Player player) {
        UUID id = player.getUniqueId();
        queued.remove(id);
        if (run != null) {
            run.session.leave(id);
            var s = run.survivor(id);
            if (s != null) s.leave();
        }
    }

    public void recoverPlayer(Player player) {
        players.hold(player);
        if (available) players.restore(player);
    }

    public void tick() {
        if (stopped || !available) return;
        wallTick++;
        try {
            if (run == null) {
                tickQueue();
                return;
            }
            var r = run;
            var phase = r.session.snapshot().phase();
            if (phase == MatchPhase.CLEANUP) {
                if (!r.cleaning && wallTick >= r.retryAt) cleanup(r);
                return;
            }
            if (r.ending) {
                if (!r.committing && wallTick >= r.retryAt) commit(r);
                return;
            }
            if (r.session.tick()) {
                prepare(r);
                return;
            }
            phase = r.session.snapshot().phase();
            if (phase == MatchPhase.FAILED && !r.preparing) {
                cleanup(r);
                return;
            }
            if (phase == MatchPhase.WAITING) {
                r.session.fail("Lobby lost minimum population");
                cleanup(r);
                return;
            }
            if (phase == MatchPhase.COUNTDOWN) {
                countdown(r);
                return;
            }
            if (phase == MatchPhase.PAUSED) {
                if (wallTick % 20 == 0) r.hud.paused();
                return;
            }
            if (!r.playing()) return;
            r.tick++;
            r.combat.tick();
            r.lives.tick();
            if (!r.playing()) return;
            r.interactions.tick();
            r.waves.tick();
            if (!r.playing()) return;
            r.hud.tick();
            checkpoint(r);
            events(r);
        } catch (RuntimeException e) {
            log("match_tick_failed", e);
            if (run != null) preparationFailed(run, e);
        }
    }

    private void tickQueue() {
        queued.removeIf(id -> Bukkit.getPlayer(id) == null);
        var rules = plugin.configuration().matchRules();
        if (queued.size() < rules.minPlayers() || wallTick < queueAt) return;
        var map = catalog.maps().get(queueMap);
        var identity =
                new MatchIdentity(
                        UUID.randomUUID(),
                        plugin.configuration().serverId(),
                        map.id(),
                        map.version(),
                        "vanilla-v1");
        var session = new MatchSession(identity, rules, Clock.systemUTC());
        session.reserve(Set.copyOf(queued));
        session.openLobby();
        queued.forEach(session::join);
        session.countdown();
        run = new MatchRun(session, rules, catalog.gameplay(), map);
        run.countdownEnds = Instant.now().plus(rules.countdown());
        queued.clear();
        events(run);
    }

    private void countdown(MatchRun r) {
        if (wallTick % 20 != 0) return;
        long seconds =
                Math.max(1, Duration.between(Instant.now(), r.countdownEnds).toSeconds() + 1);
        for (var id : r.session.snapshot().players()) {
            var p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendActionBar(Component.text("Starting in " + seconds + "s", NamedTextColor.GOLD));
            if (seconds <= 5)
                p.showTitle(
                        Title.title(
                                Component.text(seconds, NamedTextColor.GOLD),
                                Component.text("Get ready")));
        }
    }

    private void checkpoint(MatchRun r) {
        if (r.tick % 100 != 0 || r.checkpointing) return;
        r.checkpointing = true;
        onServer(
                journal.save(result(r, MatchResult.Outcome.ABANDONED), false),
                (v, e) -> {
                    r.checkpointing = false;
                    if (e != null) {
                        log("checkpoint_failed", e);
                        finish(r, MatchResult.Outcome.FAILED);
                    }
                });
    }

    private void prepare(MatchRun r) {
        r.preparing = true;
        onServer(
                worlds.allocate(r.session.snapshot().identity().matchId()),
                (name, error) -> {
                    if (error != null) preparationFailed(r, error);
                    else buildAndCapture(r, name);
                });
    }

    private void buildAndCapture(MatchRun r, String name) {
        r.worldName = name;
        try {
            r.world = worlds.build(name, r.map);
            var writes = new ArrayList<CompletableFuture<Void>>();
            for (var id : r.session.snapshot().players()) {
                var p = Bukkit.getPlayer(id);
                if (p == null) r.session.leave(id);
                else writes.add(players.capture(p));
            }
            onServer(
                    CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)),
                    (v, e) -> begin(r, e));
        } catch (RuntimeException e) {
            preparationFailed(r, e);
        }
    }

    private void begin(MatchRun r, Throwable error) {
        if (error != null) {
            preparationFailed(r, error);
            return;
        }
        try {
            if (r.session.snapshot().phase() != MatchPhase.STARTING)
                throw new IllegalStateException("Preparation cancelled");
            var roster = r.session.snapshot().players();
            if (roster.size() < r.rules.minPlayers())
                throw new IllegalStateException("Population changed during preparation");
            for (var id : roster) {
                if (Bukkit.getPlayer(id) == null)
                    throw new IllegalStateException("Player disconnected during preparation");
                r.players.put(id, new Survivor(id, r.content));
            }
            r.session.begin();
            onServer(
                    journal.save(result(r, MatchResult.Outcome.ABANDONED), false),
                    (v, e) -> admit(r, e));
        } catch (RuntimeException e) {
            preparationFailed(r, e);
        }
    }

    private void admit(MatchRun r, Throwable error) {
        if (error != null) {
            preparationFailed(r, error);
            return;
        }
        try {
            r.combat = new CombatService(plugin, r);
            r.lives =
                    new net.fries.zombies.game.player.LifeService(r, outcome -> finish(r, outcome));
            r.interactions = new InteractionService(r);
            r.waves = new WaveService(r, outcome -> finish(r, outcome));
            r.hud = new MatchHud(r);
            r.interactions.labels();
            for (var s : r.players.values()) {
                var p = Bukkit.getPlayer(s.id());
                if (p == null || s.life() == Survivor.Life.LEFT) {
                    s.leave();
                    continue;
                }
                players.equip(p, r);
                players.admitted(s.id());
                message(
                        p,
                        "Survive "
                                + r.content.waves().finalRound()
                                + " rounds together. Right-click to fire, F or /zombies reloadgun"
                                + " to reload. Sneak beside downed teammates to revive.");
            }
            r.preparing = false;
            events(r);
            plugin.getLogger()
                    .info(
                            "event=match_playable match="
                                    + r.session.snapshot().identity().matchId()
                                    + " scaling="
                                    + r.session.snapshot().initialPlayerCount());
        } catch (RuntimeException e) {
            preparationFailed(r, e);
        }
    }

    private void preparationFailed(MatchRun r, Throwable error) {
        log("map_preparation_failed", error);
        r.preparing = false;
        if (r.session.startedAt() != null) finish(r, MatchResult.Outcome.FAILED);
        else {
            if (r.session.snapshot().phase() != MatchPhase.FAILED)
                r.session.fail("World/equipment preparation failed");
            cleanup(r);
        }
    }

    private MatchResult result(MatchRun r, MatchResult.Outcome outcome) {
        return new MatchResult(
                r.session.snapshot().identity(),
                r.session.startedAt(),
                Instant.now(),
                outcome,
                r.players.values().stream()
                        .map(s -> s.result(r.content, outcome == MatchResult.Outcome.WIN))
                        .toList());
    }

    private void finish(MatchRun r, MatchResult.Outcome outcome) {
        if (r.ending) return;
        r.ending = true;
        r.result = result(r, outcome);
        r.session.finish(r.result);
        plugin.getLogger()
                .info(
                        "event=match_finished outcome="
                                + outcome
                                + " match="
                                + r.result.identity().matchId());
        if (r.waves != null) r.waves.pause(true);
        for (var s : r.players.values()) {
            var p = Bukkit.getPlayer(s.id());
            if (p == null || s.life() == Survivor.Life.LEFT) continue;
            p.showTitle(
                    Title.title(
                            Component.text(
                                    outcome == MatchResult.Outcome.WIN ? "VICTORY" : "MATCH OVER",
                                    NamedTextColor.GOLD),
                            Component.text("Saving results…", NamedTextColor.GRAY)));
            message(
                    p,
                    "Match ended: "
                            + outcome
                            + " • rounds "
                            + s.rounds()
                            + " • kills "
                            + s.kills());
        }
        events(r);
        commit(r);
    }

    private void commit(MatchRun r) {
        r.committing = true;
        onServer(
                journal.commit(r.result, plugin.accounts()),
                (receipt, error) -> {
                    r.committing = false;
                    if (error != null) {
                        retry(r, "match_result_pending", error);
                        return;
                    }
                    r.session.acknowledge(receipt);
                    for (var reward : r.result.players()) {
                        var p = Bukkit.getPlayer(reward.playerId());
                        if (p != null && r.survivor(reward.playerId()).life() != Survivor.Life.LEFT)
                            message(
                                    p,
                                    "Result saved • +"
                                            + reward.tokens()
                                            + " tokens • +"
                                            + reward.experience()
                                            + " XP. Join again after cleanup.");
                    }
                    cleanup(r);
                });
    }

    private void cleanup(MatchRun r) {
        if (r.cleaning) return;
        r.cleaning = true;
        if (r.session.snapshot().phase() != MatchPhase.CLEANUP) r.session.cleanup();
        r.dispose();
        onServer(
                players.restoreAll(),
                (v, e) -> {
                    if (e != null) {
                        retry(r, "cleanup_player_failed", e);
                        return;
                    }
                    releaseWorld(r);
                });
    }

    private void releaseWorld(MatchRun r) {
        var released =
                r.worldName == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : worlds.release(r.worldName);
        onServer(
                released,
                (v, e) -> {
                    if (e != null) {
                        retry(r, "world_cleanup_failed", e);
                        return;
                    }
                    r.session.ready(true);
                    events(r);
                    run = null;
                    plugin.getLogger()
                            .info(
                                    "event=match_ready previous="
                                            + r.session.snapshot().identity().matchId());
                });
    }

    private void retry(MatchRun r, String event, Throwable error) {
        r.cleaning = false;
        r.retryAt = wallTick + 100;
        log(event, error);
    }

    public void pause(boolean pause) {
        if (run == null || run.waves == null) throw new IllegalStateException("No active match");
        if (pause) run.session.pause();
        else run.session.resume();
        run.waves.pause(pause);
        events(run);
    }

    public void stopMatch() {
        if (run == null) throw new IllegalStateException("No active match");
        if (run.session.startedAt() != null) finish(run, MatchResult.Outcome.ABANDONED);
        else {
            run.session.fail("Stopped by operator");
            if (!run.preparing) cleanup(run);
        }
    }

    public void shutdown() {
        stopped = true;
        available = false;
        queued.clear();
        if (run != null) {
            if (run.session.startedAt() != null && !run.ending)
                journal.save(result(run, MatchResult.Outcome.ABANDONED), true)
                        .exceptionally(
                                e -> {
                                    log("shutdown_journal_failed", e);
                                    return null;
                                });
            run.dispose();
        }
        players.shutdown();
        disk.close();
    }

    private <T> void onServer(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        future.whenComplete((value, error) -> plugin.onServer(() -> callback.accept(value, error)));
    }

    private void events(MatchRun r) {
        for (var e : r.session.drainEvents())
            plugin.getLogger()
                    .info(
                            "event=match_phase match="
                                    + e.matchId()
                                    + " from="
                                    + e.from()
                                    + " to="
                                    + e.to());
    }

    private void log(String event, Throwable error) {
        var types = new ArrayList<String>();
        for (Throwable cause = error; cause != null && types.size() < 8; cause = cause.getCause())
            types.add(cause.getClass().getSimpleName());
        plugin.getLogger().severe("event=" + event + " cause=" + String.join("/", types));
        if (event.contains("player_restore"))
            plugin.getLogger()
                    .log(
                            java.util.logging.Level.SEVERE,
                            "Player backup restoration diagnostic",
                            error);
    }

    private void message(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.GOLD));
    }
}
