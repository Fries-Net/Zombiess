package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Bounded archetype curves and explicit weighted pools. Match counts still come from wave rules.
 */
public record ZombieArchetypes(
        double maxHealth,
        double maxDamage,
        double maxSpeed,
        NamespacedId defaultPool,
        Map<NamespacedId, Type> types,
        Map<NamespacedId, List<Entry>> pools,
        Map<NamespacedId, NamespacedId> mapPools) {
    public record Type(
            NamespacedId id,
            String name,
            NamespacedId asset,
            NamespacedId provider,
            boolean baby,
            boolean elite,
            double health,
            double speed,
            double damage,
            double variation,
            double armour,
            String equipment,
            double headshotScale,
            boolean instaKill,
            boolean nuke) {
        public Type {
            Objects.requireNonNull(id);
            Objects.requireNonNull(asset);
            Objects.requireNonNull(provider);
            if (name == null
                    || name.isBlank()
                    || name.length() > 48
                    || !Double.isFinite(
                            health + speed + damage + variation + armour + headshotScale)
                    || health < .25
                    || health > 5
                    || speed < .25
                    || speed > 2
                    || damage < .25
                    || damage > 5
                    || variation < 0
                    || variation > .15
                    || armour < 0
                    || armour > 20
                    || headshotScale < 0
                    || headshotScale > 1
                    || !Set.of("NONE", "LEATHER", "IRON", "DIAMOND").contains(equipment))
                throw new IllegalArgumentException("Invalid zombie archetype");
        }
    }

    public record Entry(
            NamespacedId type,
            int weight,
            int minRound,
            int maxRound,
            int activeLimit,
            int roundLimit) {
        public Entry {
            if (type == null
                    || weight < 1
                    || weight > 100000
                    || minRound < 1
                    || maxRound < minRound
                    || maxRound > 10000
                    || activeLimit < 1
                    || activeLimit > 128
                    || roundLimit < 0
                    || roundLimit > 10000)
                throw new IllegalArgumentException("Invalid zombie pool entry");
        }
    }

    public record Stats(double health, double speed, double damage) {}

    public ZombieArchetypes {
        if (!Double.isFinite(maxHealth + maxDamage + maxSpeed)
                || maxHealth < 20
                || maxHealth > 1000
                || maxDamage < 1
                || maxDamage > 40
                || maxSpeed < .1
                || maxSpeed > .5) throw new IllegalArgumentException("Invalid zombie caps");
        types = Map.copyOf(types);
        mapPools = Map.copyOf(mapPools);
        var copy = new LinkedHashMap<NamespacedId, List<Entry>>();
        pools.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        pools = Map.copyOf(copy);
        var frozenTypes = types;
        var frozenPools = pools;
        if (types.isEmpty()
                || types.size() > 64
                || pools.isEmpty()
                || pools.size() > 32
                || !pools.containsKey(defaultPool))
            throw new IllegalArgumentException("Invalid zombie registry");
        for (var pool : pools.values()) {
            if (pool.isEmpty()
                    || pool.size() > 64
                    || pool.stream().anyMatch(e -> !frozenTypes.containsKey(e.type()))
                    || pool.stream()
                            .noneMatch(
                                    e ->
                                            e.minRound() == 1
                                                    && e.maxRound() == 10000
                                                    && e.roundLimit() == 0))
                throw new IllegalArgumentException(
                        "Zombie pools require a positive-weight, unlimited early-round fallback");
        }
        if (mapPools.values().stream().anyMatch(id -> !frozenPools.containsKey(id)))
            throw new IllegalArgumentException("Unknown map zombie pool");
    }

    public Type choose(
            NamespacedId map,
            int round,
            Map<NamespacedId, Integer> active,
            Map<NamespacedId, Integer> spawned,
            Random random) {
        var entries =
                pools.get(mapPools.getOrDefault(map, defaultPool)).stream()
                        .filter(
                                e ->
                                        round >= e.minRound()
                                                && round <= e.maxRound()
                                                && active.getOrDefault(e.type(), 0)
                                                        < e.activeLimit()
                                                && (e.roundLimit() == 0
                                                        || spawned.getOrDefault(e.type(), 0)
                                                                < e.roundLimit()))
                        .toList();
        int total = entries.stream().mapToInt(Entry::weight).sum();
        if (total == 0) return null;
        int ticket = random.nextInt(total);
        for (var e : entries) {
            if (ticket < e.weight()) return types.get(e.type());
            ticket -= e.weight();
        }
        throw new IllegalStateException("Unreachable zombie ticket");
    }

    public Stats stats(Type type, Gameplay.Wave wave, Random random) {
        double health =
                wave.health()
                        * type.health()
                        * (1 + (random.nextDouble() * 2 - 1) * type.variation());
        double speed =
                wave.speed()
                        * type.speed()
                        * (1 + (random.nextDouble() * 2 - 1) * type.variation());
        double damage =
                wave.damage()
                        * type.damage()
                        * (1 + (random.nextDouble() * 2 - 1) * type.variation());
        return new Stats(
                Math.max(1, Math.min(maxHealth, health)),
                Math.max(.05, Math.min(maxSpeed, speed)),
                Math.max(.1, Math.min(maxDamage, damage)));
    }

    public static ZombieArchetypes load(Path path) throws IOException {
        return parse(ContentYaml.readBytes(path));
    }

    public static ZombieArchetypes parse(byte[] bytes) {
        var root = ContentYaml.parse(bytes);
        root.keys(
                "max-health",
                "max-damage",
                "max-speed",
                "default-pool",
                "archetypes",
                "pools",
                "map-pools");
        var types = new LinkedHashMap<NamespacedId, Type>();
        for (var n : root.nodes("archetypes")) {
            n.keys(
                    "id",
                    "name",
                    "asset",
                    "provider",
                    "baby",
                    "elite",
                    "health",
                    "speed",
                    "damage",
                    "variation",
                    "armour",
                    "equipment",
                    "headshot-scale",
                    "insta-kill",
                    "nuke");
            var id = new NamespacedId(n.text("id"));
            String name = n.text("name"), equipment = n.text("equipment");
            if (name.length() > 48
                    || !Set.of("NONE", "LEATHER", "IRON", "DIAMOND").contains(equipment))
                throw new IllegalArgumentException("Invalid zombie presentation");
            var type =
                    new Type(
                            id,
                            name,
                            new NamespacedId(n.text("asset")),
                            new NamespacedId(n.text("provider")),
                            n.bool("baby"),
                            n.bool("elite"),
                            n.number("health", .25, 5),
                            n.number("speed", .25, 2),
                            n.number("damage", .25, 5),
                            n.number("variation", 0, .15),
                            n.number("armour", 0, 20),
                            equipment,
                            n.number("headshot-scale", 0, 1),
                            n.bool("insta-kill"),
                            n.bool("nuke"));
            if (types.putIfAbsent(id, type) != null)
                throw new IllegalArgumentException("Duplicate zombie archetype");
        }
        var pools = new LinkedHashMap<NamespacedId, List<Entry>>();
        for (var n : root.nodes("pools")) {
            n.keys("id", "entries");
            var entries = new ArrayList<Entry>();
            for (var e : n.nodes("entries")) {
                e.keys("type", "weight", "min-round", "max-round", "active-limit", "round-limit");
                int min = e.integer("min-round", 1, 10000),
                        max = e.integer("max-round", min, 10000);
                entries.add(
                        new Entry(
                                new NamespacedId(e.text("type")),
                                e.integer("weight", 1, 100000),
                                min,
                                max,
                                e.integer("active-limit", 1, 128),
                                e.integer("round-limit", 0, 10000)));
            }
            if (pools.putIfAbsent(new NamespacedId(n.text("id")), entries) != null)
                throw new IllegalArgumentException("Duplicate zombie pool");
        }
        var maps = new LinkedHashMap<NamespacedId, NamespacedId>();
        root.node("map-pools")
                .values()
                .forEach(
                        (k, v) -> {
                            if (!(k instanceof String key) || !(v instanceof String value))
                                throw new IllegalArgumentException("Invalid map pool binding");
                            maps.put(new NamespacedId(key), new NamespacedId(value));
                        });
        return new ZombieArchetypes(
                root.number("max-health", 20, 1000),
                root.number("max-damage", 1, 40),
                root.number("max-speed", .1, .5),
                new NamespacedId(root.text("default-pool")),
                types,
                pools,
                maps);
    }
}
