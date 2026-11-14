package net.fries.zombies.game.player;

import net.fries.zombies.common.gameplay.Survivor;
import net.fries.zombies.common.match.MatchResult.Outcome;
import net.fries.zombies.game.match.MatchRun;
import net.fries.zombies.game.world.MapWorlds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

import org.bukkit.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Consumer;

/** Down/bleed/revive clocks use match ticks, so private pause freezes every channel. */
public final class LifeService {
    private record Channel(UUID target, long started) {}

    private final MatchRun run;
    private final Consumer<Outcome> finish;
    private final Map<UUID, Channel> channels = new HashMap<>();

    public LifeService(MatchRun run, Consumer<Outcome> finish) {
        this.run = run;
        this.finish = finish;
    }

    public void damage(Player player, double damage) {
        if (!run.alive(player.getUniqueId()) || player.getNoDamageTicks() > 0) return;
        if (damage < player.getHealth()) {
            player.setHealth(player.getHealth() - damage);
            player.setNoDamageTicks(10);
            player.playHurtAnimation(0);
            return;
        }
        var s = run.survivor(player.getUniqueId());
        s.down(run.tick, run.content.bleedoutTicks());
        run.perks.down(player);
        player.setHealth(20);
        player.setWalkSpeed(0);
        player.setSprinting(false);
        player.setNoDamageTicks(20);
        player.showTitle(
                Title.title(
                        Component.text("DOWNED", NamedTextColor.RED),
                        Component.text("A teammate must sneak beside you", NamedTextColor.YELLOW)));
        broadcast(player.getName() + " is down! Sneak nearby to revive.");
    }

    public void tick() {
        for (var s : run.players.values()) {
            var p = Bukkit.getPlayer(s.id());
            if (s.selfRevive(run.tick) && p != null) {
                run.perks.apply(p, false);
                p.setHealth(10);
                p.setNoDamageTicks(100);
                broadcast(p.getName() + " consumed Quick Revive and returned!");
            }
            if (s.bleed(run.tick) && p != null) {
                p.setGameMode(GameMode.SPECTATOR);
                p.teleport(MapWorlds.location(run.world, run.map.spectatorSpawn()));
                p.sendMessage(
                        Component.text(
                                "You bled out. Return next round if your team survives.",
                                NamedTextColor.RED));
            }
            if (s.life() != Survivor.Life.ALIVE || p == null || !p.isSneaking()) {
                channels.remove(s.id());
                continue;
            }
            var target =
                    run.players.values().stream()
                            .filter(t -> t.life() == Survivor.Life.DOWNED)
                            .map(t -> Bukkit.getPlayer(t.id()))
                            .filter(Objects::nonNull)
                            .filter(
                                    t ->
                                            t.getWorld() == p.getWorld()
                                                    && t.getLocation()
                                                                    .distanceSquared(
                                                                            p.getLocation())
                                                            <= 6.25
                                                    && p.hasLineOfSight(t))
                            .min(
                                    Comparator.comparingDouble(
                                            t -> t.getLocation().distanceSquared(p.getLocation())))
                            .orElse(null);
            if (target == null) {
                channels.remove(s.id());
                continue;
            }
            var channel = channels.get(s.id());
            if (channel == null || !channel.target.equals(target.getUniqueId())) {
                channel = new Channel(target.getUniqueId(), run.tick);
                channels.put(s.id(), channel);
            }
            if (run.tick - channel.started >= run.perks.reviveTicks(s)
                    && s.revive(run.survivor(target.getUniqueId()))) {
                run.perks.apply(target, false);
                target.setHealth(10);
                target.setNoDamageTicks(60);
                channels.remove(s.id());
                target.showTitle(
                        Title.title(
                                Component.text("REVIVED", NamedTextColor.GREEN),
                                Component.text(
                                        p.getName() + " helped you up", NamedTextColor.GOLD)));
                broadcast(p.getName() + " revived " + target.getName());
            }
        }
        if (run.players.values().stream()
                .noneMatch(
                        s ->
                                s.life() == Survivor.Life.ALIVE
                                        || s.life() == Survivor.Life.DOWNED
                                                && s.perks().awaitingSoloRevive()))
            finish.accept(Outcome.LOSS);
    }

    public String channel(UUID id) {
        var c = channels.get(id);
        if (c == null) return "";
        return "Reviving "
                + Math.min(
                        100, (run.tick - c.started) * 100 / run.perks.reviveTicks(run.survivor(id)))
                + "% • keep sneaking";
    }

    public boolean reviving(UUID id) {
        return channels.containsKey(id);
    }

    public void roundRespawn() {
        for (var s : run.players.values()) {
            if (s.life() != Survivor.Life.DEAD) continue;
            var p = Bukkit.getPlayer(s.id());
            if (p == null) continue;
            s.respawn();
            p.setGameMode(GameMode.ADVENTURE);
            run.perks.apply(p, false);
            p.setHealth(20);
            p.setNoDamageTicks(60);
            p.teleport(MapWorlds.location(run.world, run.map.playerSpawn(p.getUniqueId())));
            run.combat.equip(p);
        }
    }

    private void broadcast(String text) {
        run.world
                .getPlayers()
                .forEach(p -> p.sendMessage(Component.text(text, NamedTextColor.YELLOW)));
    }
}
