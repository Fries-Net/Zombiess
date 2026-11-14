package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit per-weapon tiers; levels are contiguous and limits are the configured list length. */
public record WeaponUpgrades(Map<NamespacedId, List<Tier>> weapons) {
    public record Tier(
            int cost,
            String name,
            double damage,
            int magazine,
            int reserve,
            int cooldownTicks,
            int reloadTicks,
            NamespacedId asset,
            NamespacedId fireSound,
            NamespacedId upgradeSound) {
        public Tier {
            Objects.requireNonNull(asset);
            Objects.requireNonNull(fireSound);
            Objects.requireNonNull(upgradeSound);
            if (cost < 0
                    || cost > 100000
                    || name == null
                    || name.isBlank()
                    || name.length() > 64
                    || !Double.isFinite(damage)
                    || damage < .1
                    || damage > 1000
                    || magazine < 1
                    || magazine > 200
                    || reserve < 1
                    || reserve > 2000
                    || cooldownTicks < 1
                    || cooldownTicks > 400
                    || reloadTicks < 1
                    || reloadTicks > 1200)
                throw new IllegalArgumentException("Invalid weapon upgrade tier");
        }

        public Gameplay.Weapon apply(Gameplay.Weapon base) {
            var b = base.behaviour();
            return new Gameplay.Weapon(
                    base.id(),
                    name,
                    base.material(),
                    magazine,
                    reserve,
                    damage,
                    base.headshotMultiplier(),
                    base.range(),
                    cooldownTicks,
                    reloadTicks,
                    base.price(),
                    base.ammoPrice(),
                    new Gameplay.Behaviour(
                            b.mode(),
                            b.burstShots(),
                            b.burstIntervalTicks(),
                            fireSound.value(),
                            b.pitch(),
                            b.particle(),
                            asset,
                            b.pierceTargets(),
                            b.bodyGold(),
                            b.headGold()));
        }
    }

    public WeaponUpgrades {
        var copy = new LinkedHashMap<NamespacedId, List<Tier>>();
        weapons.forEach(
                (id, levels) -> {
                    if (levels.isEmpty() || levels.size() > 5)
                        throw new IllegalArgumentException("Expected 1..5 upgrade tiers");
                    copy.put(Objects.requireNonNull(id), List.copyOf(levels));
                });
        if (copy.isEmpty() || copy.size() > 256)
            throw new IllegalArgumentException("Expected 1..256 upgrade definitions");
        weapons = Collections.unmodifiableMap(copy);
    }

    public Tier next(NamespacedId weapon, int level) {
        if (level < 0) throw new IllegalArgumentException("Invalid upgrade level");
        var tiers = weapons.getOrDefault(weapon, List.of());
        return level < tiers.size() ? tiers.get(level) : null;
    }

    public static WeaponUpgrades load(Path path, Gameplay content) throws IOException {
        return parse(ContentYaml.readBytes(path), content);
    }

    public static WeaponUpgrades parse(byte[] bytes, Gameplay content) {
        var root = ContentYaml.parse(bytes);
        root.keys("schema-version", "weapons");
        root.integer("schema-version", 1, 1);
        var weapons = new LinkedHashMap<NamespacedId, List<Tier>>();
        for (var n : root.nodes("weapons")) {
            n.keys("weapon", "tiers");
            var id = new NamespacedId(n.text("weapon"));
            var base = content.weapons().get(id);
            if (base == null) throw new IllegalArgumentException("Unknown upgraded weapon " + id);
            var tiers = new ArrayList<Tier>();
            var previous = base;
            for (var t : n.nodes("tiers")) {
                t.keys(
                        "cost",
                        "name",
                        "damage",
                        "magazine",
                        "reserve",
                        "cooldown-ticks",
                        "reload-ticks",
                        "asset",
                        "fire-sound",
                        "upgrade-sound");
                var tier =
                        new Tier(
                                t.integer("cost", 0, 100000),
                                t.text("name"),
                                t.number("damage", .1, 1000),
                                t.integer("magazine", 1, 200),
                                t.integer("reserve", 1, 2000),
                                t.integer("cooldown-ticks", 1, 400),
                                t.integer("reload-ticks", 1, 1200),
                                new NamespacedId(t.text("asset")),
                                new NamespacedId(t.text("fire-sound")),
                                new NamespacedId(t.text("upgrade-sound")));
                if (tier.damage() < previous.damage()
                        || tier.magazine() < previous.magazine()
                        || tier.reserve() < previous.reserve()
                        || tier.cooldownTicks() > previous.cooldownTicks()
                        || tier.reloadTicks() > previous.reloadTicks())
                    throw new IllegalArgumentException(
                            "Upgrade tiers cannot reduce weapon stats: " + id);
                tiers.add(tier);
                previous = tier.apply(base);
            }
            if (weapons.putIfAbsent(id, tiers) != null)
                throw new IllegalArgumentException("Duplicate upgrade weapon " + id);
        }
        return new WeaponUpgrades(weapons);
    }

    public void validate(MapTemplate map) {
        for (var object : map.objects())
            if (object.kind() == MapObject.Kind.PACK_A_PUNCH) {
                if (object.properties().containsKey("price"))
                    object.integer("price", -1, -1, 100000);
                var allowed = object.property("weapons", "*");
                if (!allowed.equals("*")) {
                    var ids = allowed.split(",", -1);
                    if (ids.length == 0 || ids.length > 16)
                        throw new IllegalArgumentException("Invalid station weapon list");
                    for (var id : ids)
                        if (!weapons.containsKey(new NamespacedId(id.trim())))
                            throw new IllegalArgumentException(
                                    "Unknown station upgrade weapon " + id);
                }
            }
    }
}
