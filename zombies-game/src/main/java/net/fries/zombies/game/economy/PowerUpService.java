package net.fries.zombies.game.economy;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.content.PowerUps.*;
import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** One owner for bounded drops, timed effects, team grants and exactly-once collection. */
public final class PowerUpService {
    private record Drop(Type type, Item item, TextDisplay label, long expires) {}

    private final MatchRun run;
    private final Map<UUID, Drop> drops = new LinkedHashMap<>();
    private final Map<Type, Integer> perRound = new EnumMap<>(Type.class);
    private final Random random = new Random();
    private int round = -1;
    private long instaUntil;

    public PowerUpService(MatchRun run) {
        this.run = run;
    }

    private void round() {
        if (round != run.waves.round()) {
            round = run.waves.round();
            perRound.clear();
        }
    }

    public void maybeDrop(Location at) {
        round();
        if (random.nextDouble() >= run.powerupRules.dropChance()
                || drops.size() >= run.powerupRules.maxDrops()) return;
        var allowed =
                run.powerupRules.allowed(
                        run.map, new MapTemplate.Point(at.getX(), at.getY(), at.getZ()));
        var eligible =
                run.powerupRules.definitions().values().stream()
                        .filter(
                                d ->
                                        d.enabled()
                                                && allowed.contains(d.type())
                                                && round >= d.minRound()
                                                && perRound.getOrDefault(d.type(), 0)
                                                        < d.perRound())
                        .toList();
        int weight = eligible.stream().mapToInt(Definition::weight).sum();
        if (weight == 0) return;
        int ticket = random.nextInt(weight);
        for (var definition : eligible) {
            if (ticket < definition.weight()) {
                spawn(at, definition.type());
                return;
            }
            ticket -= definition.weight();
        }
    }

    /** Compatibility entry point for an explicitly spawned Double Points pickup. */
    public Item spawn(Location at) {
        return spawn(at, Type.DOUBLE_POINTS);
    }

    public Item spawn(Location at, Type type) {
        round();
        var definition = run.powerupRules.definitions().get(type);
        if (at.getWorld() != run.world
                || !definition.enabled()
                || round < definition.minRound()
                || drops.size() >= run.powerupRules.maxDrops()
                || perRound.getOrDefault(type, 0) >= definition.perRound()
                || !run.powerupRules
                        .allowed(run.map, new MapTemplate.Point(at.getX(), at.getY(), at.getZ()))
                        .contains(type)) return null;
        var item =
                run.world.dropItem(
                        at.clone().add(0, .3, 0),
                        new ItemStack(Material.valueOf(definition.material())));
        item.setPickupDelay(10);
        item.setUnlimitedLifetime(true);
        item.setGlowing(true);
        item.setGravity(false);
        item.setVelocity(new org.bukkit.util.Vector());
        item.setCanMobPickup(false);
        var label =
                run.world.spawn(
                        item.getLocation().add(0, .65, 0),
                        TextDisplay.class,
                        d -> {
                            d.setPersistent(false);
                            d.setBillboard(Display.Billboard.CENTER);
                            d.text(Component.text(definition.name(), NamedTextColor.GOLD));
                        });
        drops.put(
                item.getUniqueId(),
                new Drop(type, item, label, run.tick + definition.despawnTicks()));
        perRound.merge(type, 1, Integer::sum);
        return item;
    }

    public boolean contains(Item item) {
        return drops.containsKey(item.getUniqueId());
    }

    public boolean pickup(Player player, Item item) {
        var drop = drops.get(item.getUniqueId());
        if (drop == null) return false;
        if (!run.alive(player.getUniqueId())
                || player.getWorld() != run.world
                || item.getPickupDelay() > 0) return true;
        // Remove ownership before any damage or callback can recursively invoke pickup again.
        drops.remove(item.getUniqueId());
        remove(drop);
        if (run.tick >= drop.expires()) return true;
        var definition = run.powerupRules.definitions().get(drop.type());
        switch (drop.type()) {
            case INSTA_KILL -> instaUntil = definition.expiry(instaUntil, run.tick);
            case DOUBLE_POINTS -> {
                long until =
                        definition.expiry(
                                run.tick + run.points.doubleGoldRemaining(run.tick), run.tick);
                run.points.doubleGold(run.tick, (int) (until - run.tick));
            }
            case MAX_AMMO -> {
                for (var s : run.players.values())
                    if (s.life() == Survivor.Life.ALIVE
                            || s.life() == Survivor.Life.DOWNED && run.powerupRules.downedAmmo()
                            || s.life() == Survivor.Life.DEAD && run.powerupRules.deadAmmo())
                        s.weapons()
                                .values()
                                .forEach(w -> w.maxAmmo(run.powerupRules.refillMagazine()));
            }
            case NUKE -> {
                var action = UUID.randomUUID();
                double applied = 0;
                for (var enemy : run.waves.enemies()) {
                    var type = run.waves.archetype(enemy.getUniqueId());
                    double damage =
                            type == null || type.nuke()
                                    ? Math.max(100, enemy.getHealth() * 100)
                                    : run.powerupRules.excludedNukeDamage();
                    applied +=
                            run.combat
                                    .damageService()
                                    .apply(
                                            action,
                                            player,
                                            enemy,
                                            damage,
                                            false,
                                            PointsLedger.Kind.POWER_UP,
                                            run.content.weapons().get(run.content.starter()));
                }
                if (applied > 0) reward(item.getUniqueId(), run.powerupRules.nukeGold());
            }
            case CARPENTER -> {
                if (run.windows.carpenter() > 0)
                    reward(item.getUniqueId(), run.powerupRules.carpenterGold());
            }
        }
        for (var p : run.world.getPlayers()) {
            p.sendMessage(
                    Component.text(
                            definition.name() + " activated by " + player.getName(),
                            NamedTextColor.GOLD));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .6f, 1.4f);
        }
        run.world.spawnParticle(
                Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 20, .5, .5, .5, .05);
        return true;
    }

    private void reward(UUID pickup, int amount) {
        if (amount == 0) return;
        for (var s : run.players.values())
            if (s.life() == Survivor.Life.ALIVE)
                run.points.powerup(s.id(), pickup.toString(), amount, run.tick);
    }

    public boolean instaKill(UUID enemy, PointsLedger.Kind kind) {
        var type = run.waves.archetype(enemy);
        return run.tick < instaUntil
                && (kind == PointsLedger.Kind.BULLET || kind == PointsLedger.Kind.MELEE)
                && (type == null || type.instaKill());
    }

    public long instaRemaining() {
        return Math.max(0, instaUntil - run.tick);
    }

    public int activeDrops() {
        return drops.size();
    }

    public String status() {
        return instaRemaining() > 0 ? " • INSTA-KILL " + ((instaRemaining() + 19) / 20) + "s" : "";
    }

    public void tick() {
        round();
        for (var drop : List.copyOf(drops.values())) {
            if (run.tick >= drop.expires() || !drop.item().isValid()) {
                drops.remove(drop.item().getUniqueId());
                remove(drop);
                continue;
            }
            if (run.tick % 20 == 0) {
                drop.label()
                        .text(
                                Component.text(
                                        run.powerupRules.definitions().get(drop.type()).name()
                                                + " • "
                                                + ((drop.expires() - run.tick + 19) / 20)
                                                + "s",
                                        NamedTextColor.GOLD));
                run.world.spawnParticle(
                        Particle.END_ROD, drop.item().getLocation(), 3, .15, .2, .15, .01);
            }
        }
    }

    private void remove(Drop drop) {
        drop.item().remove();
        drop.label().remove();
    }

    public void clear() {
        drops.values().forEach(this::remove);
        drops.clear();
        perRound.clear();
        instaUntil = 0;
    }
}
