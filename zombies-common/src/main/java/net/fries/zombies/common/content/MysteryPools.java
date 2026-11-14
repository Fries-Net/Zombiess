package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Immutable weighted rewards. Each match captures this registry before purchases begin. */
public record MysteryPools(Map<NamespacedId, Pool> pools) {
    public record Reward(NamespacedId weapon, int weight, boolean rare) {
        public Reward {
            if (weight < 1 || weight > 100000)
                throw new IllegalArgumentException("Invalid mystery reward weight");
        }
    }

    public record Pool(
            NamespacedId id,
            int price,
            int openTicks,
            int claimTicks,
            double relocateChance,
            List<Reward> rewards) {
        public Pool {
            rewards = List.copyOf(rewards);
            if (price < 0
                    || price > 100000
                    || openTicks < 1
                    || openTicks > 1200
                    || claimTicks < 20
                    || claimTicks > 2400
                    || !Double.isFinite(relocateChance)
                    || relocateChance < 0
                    || relocateChance > 1
                    || rewards.isEmpty()
                    || rewards.size() > 32
                    || rewards.stream().map(Reward::weapon).distinct().count() != rewards.size())
                throw new IllegalArgumentException("Invalid mystery pool " + id);
        }

        public int totalWeight() {
            return rewards.stream().mapToInt(Reward::weight).sum();
        }

        public Reward choose(int ticket) {
            if (ticket < 0 || ticket >= totalWeight())
                throw new IllegalArgumentException("Invalid reward ticket");
            for (var reward : rewards) {
                if (ticket < reward.weight()) return reward;
                ticket -= reward.weight();
            }
            throw new IllegalStateException("Unreachable reward ticket");
        }
    }

    public MysteryPools {
        pools = Map.copyOf(pools);
        if (pools.isEmpty() || pools.size() > 32)
            throw new IllegalArgumentException("Expected 1..32 mystery pools");
    }

    public Pool require(String id) {
        var pool = pools.get(new NamespacedId(id));
        if (pool == null) throw new IllegalArgumentException("Unknown mystery pool " + id);
        return pool;
    }

    public static MysteryPools load(Path path, Gameplay gameplay) throws IOException {
        return parse(ContentYaml.readBytes(path), gameplay);
    }

    public static MysteryPools parse(byte[] bytes, Gameplay gameplay) {
        var root = ContentYaml.parse(bytes);
        root.keys("schema-version", "pools");
        root.integer("schema-version", 1, 1);
        var pools = new LinkedHashMap<NamespacedId, Pool>();
        for (var n : root.nodes("pools")) {
            n.keys("id", "price", "open-ticks", "claim-ticks", "relocate-chance", "rewards");
            var rewards =
                    n.nodes("rewards").stream()
                            .map(
                                    r -> {
                                        r.keys("weapon", "weight", "rare");
                                        var weapon = new NamespacedId(r.text("weapon"));
                                        if (!gameplay.weapons().containsKey(weapon))
                                            throw new IllegalArgumentException(
                                                    "Unknown mystery weapon " + weapon);
                                        return new Reward(
                                                weapon,
                                                r.integer("weight", 1, 100000),
                                                r.bool("rare"));
                                    })
                            .toList();
            var id = new NamespacedId(n.text("id"));
            var pool =
                    new Pool(
                            id,
                            n.integer("price", 0, 100000),
                            n.integer("open-ticks", 1, 1200),
                            n.integer("claim-ticks", 20, 2400),
                            n.number("relocate-chance", 0, 1),
                            rewards);
            if (pools.putIfAbsent(id, pool) != null)
                throw new IllegalArgumentException("Duplicate mystery pool " + id);
        }
        return new MysteryPools(pools);
    }

    public void validate(MapTemplate map) {
        for (var object : map.objects())
            if (object.kind() == MapObject.Kind.MYSTERY_BOX) {
                require(object.property("box", "friesnet:box/default"));
                if (object.properties().containsKey("price")) object.integer("price", 0, 0, 100000);
            }
    }
}
