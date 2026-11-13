package net.fries.zombies.game.world;

import net.fries.zombies.common.content.Gameplay;
import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.common.match.MatchResult.Outcome;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Gradual native spawning and target ownership. Scaling is read once from the frozen session
 * roster.
 */
public final class WaveService {
    public record Credit(UUID player, boolean headshot) {}

    private final MatchRun run;
    private final Consumer<Outcome> finish;
    private final Map<UUID, Zombie> zombies = new LinkedHashMap<>();
    private final Map<UUID, Credit> credits = new HashMap<>();
    private int round, remaining, spawnIndex;
    private long nextRound, nextSpawn, roundStarted;
    private Gameplay.Wave plan;

    public WaveService(MatchRun run, Consumer<Outcome> finish) {
        this.run = run;
        this.finish = finish;
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
            spawn();
            remaining--;
            nextSpawn = run.tick + run.content.waves().spawnTicks();
        }
        if (run.tick % 20 == 0) {
            for (var zombie : List.copyOf(zombies.values())) {
                if (!zombie.isValid() || zombie.isDead()) {
                    zombies.remove(zombie.getUniqueId());
                    credits.remove(zombie.getUniqueId());
                    continue;
                }
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
                    remaining++;
                }
            }
        }
        if (run.tick % 40 == 0) run.interactions.zombieDamage(zombies.values());
        if (remaining == 0 && zombies.isEmpty()) {
            for (var s : run.players.values())
                if (s.life() != Survivor.Life.LEFT) s.round(run.content);
            run.lives.roundRespawn();
            broadcast("Round " + round + " cleared! +" + run.content.roundPoints() + " points");
            if (round >= run.content.waves().finalRound()) {
                finish.accept(Outcome.WIN);
                return;
            }
            nextRound = run.tick + run.content.waves().intermissionTicks();
        }
    }

    private void startRound() {
        round++;
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

    private void spawn() {
        var eligible =
                run.map.zombieSpawns().stream()
                        .filter(s -> s.gate().equals("none") || run.interactions.open(s.gate()))
                        .toList();
        var point = eligible.get(spawnIndex++ % eligible.size()).point();
        var zombie =
                run.world.spawn(
                        MapWorlds.location(run.world, point),
                        Zombie.class,
                        z -> {
                            z.setAdult();
                            z.setCanBreakDoors(false);
                            z.setShouldBurnInDay(false);
                            z.setCanPickupItems(false);
                            z.setRemoveWhenFarAway(false);
                            z.setPersistent(false);
                            Objects.requireNonNull(z.getAttribute(Attribute.MAX_HEALTH))
                                    .setBaseValue(plan.health());
                            z.setHealth(plan.health());
                            Objects.requireNonNull(z.getAttribute(Attribute.ATTACK_DAMAGE))
                                    .setBaseValue(plan.damage());
                            Objects.requireNonNull(z.getAttribute(Attribute.ARMOR)).setBaseValue(0);
                            Objects.requireNonNull(z.getAttribute(Attribute.ARMOR_TOUGHNESS))
                                    .setBaseValue(0);
                            Objects.requireNonNull(z.getAttribute(Attribute.MOVEMENT_SPEED))
                                    .setBaseValue(plan.speed());
                            Objects.requireNonNull(z.getAttribute(Attribute.FOLLOW_RANGE))
                                    .setBaseValue(100);
                            z.getEquipment().clear();
                        });
        zombies.put(zombie.getUniqueId(), zombie);
    }

    public void died(UUID id) {
        if (zombies.remove(id) == null) return;
        var credit = credits.remove(id);
        if (credit != null) {
            var s = run.players.get(credit.player());
            if (s != null) s.kill(credit.headshot(), run.content);
        }
    }

    public void attribute(UUID id, UUID player, boolean headshot) {
        credits.put(id, new Credit(player, headshot));
    }

    public void forgetAttribution(UUID id) {
        credits.remove(id);
    }

    public boolean contains(UUID id) {
        return zombies.containsKey(id);
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
    }

    private void broadcast(String text) {
        run.world
                .getPlayers()
                .forEach(p -> p.sendMessage(Component.text(text, NamedTextColor.GOLD)));
    }
}
