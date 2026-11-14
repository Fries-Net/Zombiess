package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;
import net.fries.zombies.common.content.Perks.*;
import net.fries.zombies.common.gameplay.*;
import net.fries.zombies.game.match.MatchRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;

import java.util.*;

/** Server-thread vending and native effects; no permanent account or inventory state. */
public final class PerkService {
    private static final NamespacedKey HEALTH = new NamespacedKey("friesnetzombies", "perk_health");
    private final MatchRun run;
    private final List<MapObject> stations;
    private final List<TextDisplay> labels = new ArrayList<>();
    private final Random random = new Random();

    public PerkService(MatchRun run) {
        this.run = run;
        stations = run.map.objects().stream().filter(o -> o.kind() == MapObject.Kind.PERK).toList();
        for (var station : stations) {
            var d = definition(station);
            labels.add(
                    run.world.spawn(
                            MapWorlds.location(run.world, station.point()).add(0, 1.5, 0),
                            TextDisplay.class,
                            display -> {
                                display.setPersistent(false);
                                display.setBillboard(Display.Billboard.CENTER);
                                display.text(
                                        Component.text(
                                                d.name() + " • " + price(station) + " gold",
                                                NamedTextColor.AQUA));
                                display.getPersistentDataContainer()
                                        .set(
                                                new NamespacedKey("friesnetzombies", "asset"),
                                                PersistentDataType.STRING,
                                                d.asset().value());
                            }));
        }
    }

    private Definition definition(MapObject station) {
        return run.perkRules.require(station.property("perk", "friesnet:perk/extra_health"));
    }

    private int price(MapObject station) {
        return station.integer("price", definition(station).price(), 0, 100000);
    }

    private MapObject nearest(Player p) {
        return stations.stream()
                .filter(s -> run.interactions.near(p, s.point(), 3.5))
                .min(
                        Comparator.comparingDouble(
                                s ->
                                        p.getEyeLocation()
                                                .distanceSquared(
                                                        MapWorlds.location(run.world, s.point()))))
                .orElse(null);
    }

    public boolean solo() {
        return run.session.snapshot().initialPlayerCount() == 1;
    }

    public int freeWeaponSlot(Player p) {
        var s = run.survivor(p.getUniqueId());
        if (s.weapons().size() >= s.perks().slots(run.perkRules)) return -1;
        for (int i = 0; i < 8; i++) {
            var item = p.getInventory().getItem(i);
            if (item == null || item.getType().isAir()) return i;
        }
        return -1;
    }

    public boolean interact(Player p) {
        if (!run.alive(p.getUniqueId())) return false;
        var station = nearest(p);
        if (station == null) return false;
        if (!station.rooms().stream().allMatch(run.interactions::roomUnlocked)) {
            message(p, "Unlock this perk station's room first");
            return true;
        }
        var s = run.survivor(p.getUniqueId());
        var d = definition(station);
        var reward = d;
        String denied =
                s.perks().rejection(s, run.perkRules, d, run.waves.round(), solo(), run.tick);
        if (!denied.isEmpty()) {
            message(p, denied);
            return true;
        }
        if (d.type() == Type.WUNDERFIZZ) {
            var eligible = s.perks().rewards(s, run.perkRules, run.waves.round(), solo(), run.tick);
            int total = eligible.stream().mapToInt(Definition::weight).sum();
            if (total == 0) {
                message(p, "No eligible unowned perks; no gold charged");
                return true;
            }
            reward = PerkLoadout.choose(eligible, random.nextInt(total));
        }
        int price = price(station);
        if (!s.perks()
                .purchase(
                        s, run.perkRules, d, reward, price, run.waves.round(), solo(), run.tick)) {
            message(p, "Need " + price + " gold or perk is unavailable");
            return true;
        }
        apply(p, true);
        p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1, 1.3f);
        run.world.spawnParticle(
                Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 16, .4, .5, .4, 0);
        message(
                p,
                reward.name()
                        + " acquired • "
                        + s.perks().owned().size()
                        + "/"
                        + run.perkRules.maxOwned()
                        + " perks");
        return true;
    }

    /** Remove only our modifier, including one saved by an interrupted server. */
    public static void removeHealth(Player p) {
        var attribute = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH));
        for (var modifier : attribute.getModifiers())
            if (modifier.getKey().equals(HEALTH)) attribute.removeModifier(modifier);
        if (p.getHealth() > attribute.getValue()) p.setHealth(attribute.getValue());
    }

    public void apply(Player p, boolean grantHealth) {
        var s = run.survivor(p.getUniqueId());
        if (s == null || p.getWorld() != run.world) return;
        var attribute = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH));
        double oldMax = attribute.getValue(), oldHealth = p.getHealth();
        removeHealth(p);
        double extra = s.perks().strength(Type.JUGGERNOG, 20) - 20;
        if (extra > 0)
            attribute.addModifier(
                    new AttributeModifier(HEALTH, extra, AttributeModifier.Operation.ADD_NUMBER));
        p.setHealth(
                Math.min(
                        attribute.getValue(),
                        oldHealth
                                + (grantHealth ? Math.max(0, attribute.getValue() - oldMax) : 0)));
        p.setWalkSpeed(
                s.life() == Survivor.Life.ALIVE
                        ? (float) (.2 * s.perks().strength(Type.SPEED, 1))
                        : 0);
    }

    public int reviveTicks(Survivor s) {
        return WeaponTiming.duration(
                run.content.reviveTicks(), s.perks().strength(Type.QUICK_REVIVE, 1));
    }

    public void down(Player p) {
        var s = run.survivor(p.getUniqueId());
        s.perks().down(solo(), run.tick, run.content.bleedoutTicks());
        apply(p, false);
        // Remove newest weapons first when the extra slot disappears. No world drops or duplicates.
        while (s.weapons().size() > s.perks().slots(run.perkRules)) {
            var ids = new ArrayList<>(s.weapons().keySet());
            var removed = s.weapons().remove(ids.getLast());
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                var item = p.getInventory().getItem(i);
                if (item == null || !item.hasItemMeta()) continue;
                var key = new NamespacedKey("friesnetzombies", "weapon_instance");
                if (removed.instanceId()
                        .toString()
                        .equals(
                                item.getItemMeta()
                                        .getPersistentDataContainer()
                                        .get(key, PersistentDataType.STRING)))
                    p.getInventory().setItem(i, null);
            }
        }
        message(
                p,
                s.perks().awaitingSoloRevive()
                        ? "Quick Revive consumed • self-revive pending"
                        : "Perks lost on downing");
    }

    public void frozen(Player p, Zombie z) {
        var d = run.survivor(p.getUniqueId()).perks().get(Type.FROZEN_BULLETS);
        if (d == null || z.isDead() || random.nextDouble() >= d.chance()) return;
        z.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        d.duration(),
                        (int) d.strength(),
                        false,
                        true,
                        true));
        run.world.spawnParticle(
                Particle.SNOWFLAKE, z.getLocation().add(0, 1, 0), 6, .3, .4, .3, .01);
    }

    public String context(Player p) {
        var station = nearest(p);
        if (station == null) return "";
        if (!station.rooms().stream().allMatch(run.interactions::roomUnlocked))
            return "Perk station • room locked";
        var d = definition(station);
        var s = run.survivor(p.getUniqueId());
        String rejection =
                s.perks().rejection(s, run.perkRules, d, run.waves.round(), solo(), run.tick);
        return d.name()
                + " • "
                + (rejection.isEmpty() ? "right-click • " + price(station) + " gold" : rejection);
    }

    public void clear() {
        labels.forEach(Entity::remove);
        labels.clear();
        for (var s : run.players.values()) {
            s.perks().clear();
            var p = Bukkit.getPlayer(s.id());
            if (p != null) removeHealth(p);
        }
    }

    private static void message(Player p, String text) {
        p.sendActionBar(Component.text(text, NamedTextColor.AQUA));
        p.sendMessage(Component.text(text, NamedTextColor.AQUA));
    }
}
