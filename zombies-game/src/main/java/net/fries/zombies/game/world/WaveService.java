package net.fries.zombies.game.world;

import net.fries.zombies.common.content.Gameplay;
import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.common.match.MatchResult.Outcome;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

import org.bukkit.*;
import org.bukkit.entity.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Gradual native spawning and target ownership. Scaling is read once from the frozen session
 * roster.
 */
public final class WaveService {
    public record Credit(UUID player, boolean headshot, boolean drop) {}

    private final MatchRun run;
    private final Consumer<Outcome> finish;
    private final Map<UUID, Zombie> zombies = new LinkedHashMap<>();
    private final Map<UUID, Credit> credits = new HashMap<>();
    private int round, remaining, spawnIndex;
    private long nextRound, nextSpawn, roundStarted;
    private Gameplay.Wave plan;
    private final Map<UUID, net.fries.zombies.common.content.ZombieArchetypes.Type> archetypes =
            new HashMap<>();
    private final Map<net.fries.zombies.common.NamespacedId, Integer> spawned = new HashMap<>();
    private final ZombieFactory factory = new ZombieFactory();
    private final Random random;

    public WaveService(MatchRun run, Consumer<Outcome> finish) {
        this.run = run;
        this.finish = finish;
        random = new Random(run.session.snapshot().identity().matchId().getLeastSignificantBits());
        nextRound = run.tick + 40;
    }

    public void tick() {
        if (nextRound >= 0 && run.tick >= nextRound) {
            startRound();
            return;
        }
        if (nextRound >= 0) return;
        if (run.tick - roundStarted >= run.content.waves().timeoutTicks()) {
            finish.accept(Outcome.FAILED);
            return;
        }
        if (remaining > 0
                && zombies.size() < run.content.waves().aliveLimit()
                && run.tick >= nextSpawn) {
            if (spawn()) remaining--;
            nextSpawn = run.tick + run.content.waves().spawnTicks();
        }
        if (run.tick % 20 == 0) {
            for (var zombie : List.copyOf(zombies.values())) {
                if (!zombie.isValid() || zombie.isDead()) {
                    zombies.remove(zombie.getUniqueId());
                    credits.remove(zombie.getUniqueId());
                    archetypes.remove(zombie.getUniqueId());
                    continue;
                }
                if (zombie.getLocation().getY() < run.map.bounds().y1()
                        || zombie.getLocation().getY() > run.map.bounds().y2() + 2) {
                    zombie.remove();
                    zombies.remove(zombie.getUniqueId());
                    credits.remove(zombie.getUniqueId());
                    archetypes.remove(zombie.getUniqueId());
                    remaining++;
                    continue;
                }
                if (run.windows.guide(zombie)) continue;
                var target =
                        run.players.values().stream()
                                .filter(s -> s.life() == Survivor.Life.ALIVE)
                                .map(s -> Bukkit.getPlayer(s.id()))
                                .filter(Objects::nonNull)
                                .filter(p -> p.getWorld() == run.world)
                                .min(
                                        Comparator.comparingDouble(
                                                p ->
                                                        p.getLocation()
                                                                .distanceSquared(
                                                                        zombie.getLocation())))
                                .orElse(null);
                zombie.setTarget(target);
                if (zombie.getLocation().getY() < run.map.bounds().y1()
                        || zombie.getLocation().getY() > run.map.bounds().y2() + 2) {
                    zombie.remove();
                    zombies.remove(zombie.getUniqueId());
                    credits.remove(zombie.getUniqueId());
                    archetypes.remove(zombie.getUniqueId());
                    remaining++;
                }
            }
        }
        if (run.tick % 40 == 0) run.interactions.zombieDamage(zombies.values());
        if (remaining == 0 && zombies.isEmpty()) {
            for (var s : run.players.values())
                if (s.life() != Survivor.Life.LEFT) s.round(run.content);
            run.lives.roundRespawn();
            broadcast(
                    "Round "
                            + round
                            + " cleared!"
                            + (run.content.scoring().fixedRewards()
                                    ? " +" + run.content.roundPoints() + " gold"
                                    : ""));
            if (round >= run.content.waves().finalRound()) {
                finish.accept(Outcome.WIN);
                return;
            }
            nextRound = run.tick + run.content.waves().intermissionTicks();
        }
    }

    private void startRound() {
        round++;
        spawned.clear();
        plan = run.content.waves().at(round, run.session.snapshot().initialPlayerCount());
        remaining = plan.count();
        nextRound = -1;
        nextSpawn = run.tick;
        roundStarted = run.tick;
        for (var p : run.world.getPlayers()) {
            p.showTitle(
                    Title.title(
                            Component.text("ROUND " + round, NamedTextColor.RED),
                            Component.text(remaining + " zombies", NamedTextColor.GRAY)));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, .3f, 1.5f);
        }
    }

    private boolean spawn() {
        var active = new HashMap<net.fries.zombies.common.NamespacedId, Integer>();
        archetypes.forEach(
                (id, type) -> {
                    if (zombies.containsKey(id)) active.merge(type.id(), 1, Integer::sum);
                });
        var type = run.archetypes.choose(run.map.id(), round, active, spawned, random);
        if (type == null) return false;
        var eligible =
                run.map.zombieSpawns().stream()
                        .filter(s -> s.gate().equals("none") || run.interactions.open(s.gate()))
                        .toList();
        net.fries.zombies.common.content.WindowDefinition window = null;
        if (!run.map.windows().isEmpty()) {
            var windows = run.windows.active();
            if (windows.isEmpty()) throw new IllegalStateException("No unlocked zombie window");
            window = windows.get(spawnIndex % windows.size());
        }
        Location regionSpawn = null;
        if (window == null) {
            var regions =
                    run.map.objects().stream()
                            .filter(
                                    o ->
                                            o.kind()
                                                            == net.fries.zombies.common.content
                                                                    .MapObject.Kind.ZOMBIE_REGION
                                                    && o.rooms().stream()
                                                            .allMatch(
                                                                    run.interactions::roomUnlocked))
                            .toList();
            if (!regions.isEmpty()) {
                var safe = new ArrayList<Location>();
                MapWorlds.each(
                        run.world,
                        regions.get(spawnIndex % regions.size()).region(),
                        b -> {
                            if (b.isPassable()
                                    && b.getRelative(0, 1, 0).isPassable()
                                    && b.getRelative(0, -1, 0).getType().isSolid())
                                safe.add(b.getLocation().add(.5, 0, .5));
                        });
                if (safe.isEmpty())
                    throw new IllegalStateException("No safe spawn in editor region");
                regionSpawn = safe.get(spawnIndex % safe.size());
            }
        }
        var spawn =
                regionSpawn != null
                        ? regionSpawn
                        : window == null
                                ? MapWorlds.location(
                                        run.world,
                                        eligible.get(spawnIndex % eligible.size()).point())
                                : run.windows.spawn(window, spawnIndex);
        spawnIndex++;
        var zombie = factory.spawn(spawn, type, run.archetypes.stats(type, plan, random));
        archetypes.put(zombie.getUniqueId(), type);
        spawned.merge(type.id(), 1, Integer::sum);
        zombies.put(zombie.getUniqueId(), zombie);
        run.points.register(zombie.getUniqueId());
        if (window != null) run.windows.assign(zombie, window);
        return true;
    }

    public void died(UUID id) {
        var zombie = zombies.remove(id);
        if (zombie == null) return;
        archetypes.remove(id);
        var credit = credits.remove(id);
        if (credit != null) {
            var s = run.players.get(credit.player());
            if (s != null) s.kill(credit.headshot(), run.content);
            if (run.powerups != null && credit.drop()) run.powerups.maybeDrop(zombie.getLocation());
        }
    }

    public void attribute(UUID id, UUID player, boolean headshot) {
        attribute(id, player, headshot, true);
    }

    public void attribute(UUID id, UUID player, boolean headshot, boolean drop) {
        credits.put(id, new Credit(player, headshot, drop));
    }

    public void forgetAttribution(UUID id) {
        credits.remove(id);
    }

    public boolean contains(UUID id) {
        return zombies.containsKey(id);
    }

    public net.fries.zombies.common.content.ZombieArchetypes.Type archetype(UUID id) {
        return archetypes.get(id);
    }

    public double headshotScale(UUID id) {
        var type = archetype(id);
        return type == null ? 1 : type.headshotScale();
    }

    public List<Zombie> enemies() {
        return List.copyOf(zombies.values());
    }

    public int round() {
        return round;
    }

    public int remaining() {
        return remaining + zombies.size();
    }

    public int alive() {
        return zombies.size();
    }

    public long intermissionRemaining() {
        return Math.max(0, nextRound - run.tick);
    }

    public void pause(boolean paused) {
        zombies.values()
                .forEach(
                        z -> {
                            z.setAI(!paused);
                            z.setGravity(!paused);
                            if (paused) z.setVelocity(new org.bukkit.util.Vector());
                        });
    }

    public void clear() {
        zombies.values().forEach(Entity::remove);
        zombies.clear();
        credits.clear();
        archetypes.clear();
        spawned.clear();
    }

    private void broadcast(String text) {
        run.world
                .getPlayers()
                .forEach(p -> p.sendMessage(Component.text(text, NamedTextColor.GOLD)));
    }
}
