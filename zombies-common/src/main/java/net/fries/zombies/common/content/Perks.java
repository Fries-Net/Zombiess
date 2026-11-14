package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Immutable, bounded perk rules captured by each match. */
public record Perks(int maxOwned, int baseWeaponSlots, Map<NamespacedId, Definition> definitions) {
    public enum Type {
        JUGGERNOG,
        SPEED,
        FROZEN_BULLETS,
        QUICK_REVIVE,
        SPEED_COLA,
        MULE_KICK,
        DOUBLE_TAP,
        WUNDERFIZZ
    }

    public record Definition(
            NamespacedId id,
            Type type,
            String name,
            NamespacedId asset,
            int price,
            boolean enabled,
            int minRound,
            boolean solo,
            boolean team,
            int purchaseLimit,
            int weight,
            double strength,
            double secondary,
            int duration,
            double chance) {
        public Definition {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
            Objects.requireNonNull(asset);
            if (name == null
                    || name.isBlank()
                    || name.length() > 48
                    || price < 0
                    || price > 100000
                    || minRound < 1
                    || minRound > 10000
                    || (!solo && !team)
                    || purchaseLimit < 1
                    || purchaseLimit > 100
                    || weight < 0
                    || weight > 100000
                    || !Double.isFinite(strength)
                    || !Double.isFinite(secondary)
                    || !Double.isFinite(chance)
                    || strength < 0
                    || strength > 100
                    || secondary < 0
                    || secondary > 10
                    || duration < 0
                    || duration > 1200
                    || chance < 0
                    || chance > 1) throw new IllegalArgumentException("Invalid perk " + id);
            switch (type) {
                case JUGGERNOG -> {
                    if (strength < 20) throw new IllegalArgumentException("Health must be 20..100");
                }
                case SPEED, QUICK_REVIVE, SPEED_COLA -> {
                    if (strength < 1 || strength > 4)
                        throw new IllegalArgumentException("Multiplier must be 1..4");
                }
                case MULE_KICK -> {
                    if (strength < 1 || strength > 5 || strength != (int) strength)
                        throw new IllegalArgumentException("Extra slots must be 1..5");
                }
                case DOUBLE_TAP -> {
                    if (strength < 1 || strength > 4 || secondary < 1 || secondary > 4)
                        throw new IllegalArgumentException("Double Tap modifiers must be 1..4");
                }
                case FROZEN_BULLETS -> {
                    if (strength != (int) strength || strength > 3 || duration < 1)
                        throw new IllegalArgumentException("Invalid slowing effect");
                }
                case WUNDERFIZZ -> {
                    if (weight != 0)
                        throw new IllegalArgumentException("Wunderfizz cannot reward itself");
                }
            }
            if (type == Type.QUICK_REVIVE && duration < 1)
                throw new IllegalArgumentException("Solo revive delay required");
        }

        public boolean allowed(int round, boolean isSolo) {
            return enabled && round >= minRound && (isSolo ? solo : team);
        }
    }

    public Perks {
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        if (maxOwned < 1
                || maxOwned > 7
                || baseWeaponSlots < 1
                || baseWeaponSlots > 7
                || definitions.isEmpty()
                || definitions.size() > 8
                || definitions.values().stream().map(Definition::type).distinct().count()
                        != definitions.size()
                || definitions.entrySet().stream()
                        .anyMatch(e -> !e.getKey().equals(e.getValue().id()))
                || definitions.values().stream()
                        .anyMatch(
                                d ->
                                        d.type() == Type.MULE_KICK
                                                && baseWeaponSlots + d.strength() > 8))
            throw new IllegalArgumentException("Invalid perk registry or slot limits");
    }

    public Definition require(String id) {
        var d = definitions.get(new NamespacedId(id));
        if (d == null) throw new IllegalArgumentException("Unknown perk " + id);
        return d;
    }

    public static Perks load(Path path) throws IOException {
        return parse(ContentYaml.readBytes(path));
    }

    public static Perks parse(byte[] bytes) {
        var root = ContentYaml.parse(bytes);
        root.keys("schema-version", "max-owned", "base-weapon-slots", "perks");
        root.integer("schema-version", 1, 1);
        var definitions = new LinkedHashMap<NamespacedId, Definition>();
        for (var n : root.nodes("perks")) {
            n.keys(
                    "id",
                    "type",
                    "name",
                    "asset",
                    "price",
                    "enabled",
                    "min-round",
                    "solo",
                    "team",
                    "purchase-limit",
                    "weight",
                    "effects");
            var type = Type.valueOf(n.text("type"));
            var e = n.node("effects");
            double strength = 0, secondary = 0, chance = 0;
            int duration = 0;
            switch (type) {
                case JUGGERNOG -> {
                    e.keys("max-health");
                    strength = e.number("max-health", 20, 100);
                }
                case SPEED -> {
                    e.keys("movement-multiplier");
                    strength = e.number("movement-multiplier", 1, 4);
                }
                case SPEED_COLA -> {
                    e.keys("reload-multiplier");
                    strength = e.number("reload-multiplier", 1, 4);
                }
                case MULE_KICK -> {
                    e.keys("extra-slots");
                    strength = e.integer("extra-slots", 1, 5);
                }
                case QUICK_REVIVE -> {
                    e.keys("revive-multiplier", "solo-delay-ticks");
                    strength = e.number("revive-multiplier", 1, 4);
                    duration = e.integer("solo-delay-ticks", 1, 1200);
                }
                case DOUBLE_TAP -> {
                    e.keys("fire-rate-multiplier", "damage-multiplier");
                    strength = e.number("fire-rate-multiplier", 1, 4);
                    secondary = e.number("damage-multiplier", 1, 4);
                }
                case FROZEN_BULLETS -> {
                    e.keys("amplifier", "duration-ticks", "chance");
                    strength = e.integer("amplifier", 0, 3);
                    duration = e.integer("duration-ticks", 1, 1200);
                    chance = e.number("chance", 0, 1);
                }
                case WUNDERFIZZ -> e.keys();
            }
            var id = new NamespacedId(n.text("id"));
            var d =
                    new Definition(
                            id,
                            type,
                            n.text("name"),
                            new NamespacedId(n.text("asset")),
                            n.integer("price", 0, 100000),
                            n.bool("enabled"),
                            n.integer("min-round", 1, 10000),
                            n.bool("solo"),
                            n.bool("team"),
                            n.integer("purchase-limit", 1, 100),
                            n.integer("weight", 0, 100000),
                            strength,
                            secondary,
                            duration,
                            chance);
            if (definitions.putIfAbsent(id, d) != null)
                throw new IllegalArgumentException("Duplicate perk " + id);
        }
        return new Perks(
                root.integer("max-owned", 1, 7),
                root.integer("base-weapon-slots", 1, 7),
                definitions);
    }

    public void validate(MapTemplate map) {
        for (var object : map.objects())
            if (object.kind() == MapObject.Kind.PERK) {
                require(object.property("perk", "friesnet:perk/extra_health"));
                if (object.properties().containsKey("price")) object.integer("price", 0, 0, 100000);
            }
    }
}
