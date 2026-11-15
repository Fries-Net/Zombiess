package net.fries.zombies.common.content;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public record PowerUps(
        double dropChance,
        int maxDrops,
        boolean downedAmmo,
        boolean deadAmmo,
        boolean refillMagazine,
        int nukeGold,
        int carpenterGold,
        double excludedNukeDamage,
        Map<Type, Definition> definitions) {
    public enum Type {
        INSTA_KILL,
        MAX_AMMO,
        DOUBLE_POINTS,
        NUKE,
        CARPENTER
    }

    public enum Stacking {
        REFRESH,
        EXTEND,
        IGNORE
    }

    public record Definition(
            Type type,
            String name,
            String material,
            boolean enabled,
            int weight,
            int minRound,
            int perRound,
            int duration,
            int maxDuration,
            int despawnTicks,
            Stacking stacking) {
        public Definition {
            if (type == null
                    || name == null
                    || name.isBlank()
                    || name.length() > 48
                    || material == null
                    || material.isBlank()
                    || stacking == null
                    || weight < 1
                    || weight > 100000
                    || minRound < 1
                    || minRound > 10000
                    || perRound < 1
                    || perRound > 20
                    || duration < 0
                    || maxDuration < duration
                    || maxDuration > 72000
                    || despawnTicks < 20
                    || despawnTicks > 2400)
                throw new IllegalArgumentException("Invalid power-up definition");
        }

        public long expiry(long previous, long tick) {
            if (stacking == Stacking.IGNORE && previous > tick) return previous;
            long remaining = stacking == Stacking.EXTEND ? Math.max(0, previous - tick) : 0;
            return Math.max(previous, tick + Math.min(maxDuration, remaining + duration));
        }
    }

    public PowerUps {
        definitions = Map.copyOf(definitions);
        if (definitions.size() != 5
                || !Double.isFinite(dropChance + excludedNukeDamage)
                || dropChance < 0
                || dropChance > .3
                || maxDrops < 1
                || maxDrops > 32
                || nukeGold < 0
                || nukeGold > 10000
                || carpenterGold < 0
                || carpenterGold > 10000
                || excludedNukeDamage < 0
                || excludedNukeDamage > 1000
                || definitions.entrySet().stream().anyMatch(e -> e.getKey() != e.getValue().type()))
            throw new IllegalArgumentException("Invalid power-up rules");
    }

    public static PowerUps load(Path path) throws IOException {
        return parse(ContentYaml.readBytes(path));
    }

    public static PowerUps parse(byte[] bytes) {
        var root = ContentYaml.parse(bytes);
        root.keys(
                "drop-chance",
                "max-active-drops",
                "include-downed-ammo",
                "include-dead-ammo",
                "refill-magazines",
                "nuke-gold",
                "carpenter-gold",
                "excluded-nuke-damage",
                "power-ups");
        var definitions = new EnumMap<Type, Definition>(Type.class);
        for (var n : root.nodes("power-ups")) {
            n.keys(
                    "type",
                    "name",
                    "material",
                    "enabled",
                    "weight",
                    "min-round",
                    "per-round-limit",
                    "duration-ticks",
                    "max-duration-ticks",
                    "despawn-ticks",
                    "stacking");
            var type = Type.valueOf(n.text("type"));
            int duration = n.integer("duration-ticks", 0, 72000),
                    max = n.integer("max-duration-ticks", duration, 72000);
            if ((type == Type.INSTA_KILL || type == Type.DOUBLE_POINTS) && duration < 1
                    || n.text("name").length() > 48)
                throw new IllegalArgumentException("Invalid power-up duration/name");
            var def =
                    new Definition(
                            type,
                            n.text("name"),
                            n.text("material"),
                            n.bool("enabled"),
                            n.integer("weight", 1, 100000),
                            n.integer("min-round", 1, 10000),
                            n.integer("per-round-limit", 1, 20),
                            duration,
                            max,
                            n.integer("despawn-ticks", 20, 2400),
                            Stacking.valueOf(n.text("stacking")));
            if (definitions.putIfAbsent(type, def) != null)
                throw new IllegalArgumentException("Duplicate power-up");
        }
        return new PowerUps(
                root.number("drop-chance", 0, .3),
                root.integer("max-active-drops", 1, 32),
                root.bool("include-downed-ammo"),
                root.bool("include-dead-ammo"),
                root.bool("refill-magazines"),
                root.integer("nuke-gold", 0, 10000),
                root.integer("carpenter-gold", 0, 10000),
                root.number("excluded-nuke-damage", 0, 1000),
                definitions);
    }

    public Set<Type> allowed(MapTemplate map, MapTemplate.Point point) {
        var allowed = EnumSet.allOf(Type.class);
        for (var zone : map.objects())
            if (zone.kind() == MapObject.Kind.POWERUP_ZONE && zone.region().contains(point)) {
                if (!zone.property("enabled", "true").equals("true")) allowed.clear();
                else
                    allowed.retainAll(
                            parseAllowed(
                                    zone.property(
                                            "allowed",
                                            "INSTA_KILL,MAX_AMMO,DOUBLE_POINTS,NUKE,CARPENTER")));
            }
        return Set.copyOf(allowed);
    }

    private static Set<Type> parseAllowed(String text) {
        var result = EnumSet.noneOf(Type.class);
        if (text.isBlank() || text.equals("NONE")) return result;
        for (var part : text.split(",", -1)) result.add(Type.valueOf(part.trim()));
        return result;
    }

    public void validate(MapTemplate map) {
        for (var zone : map.objects())
            if (zone.kind() == MapObject.Kind.POWERUP_ZONE) {
                if (!Set.of("true", "false").contains(zone.property("enabled", "true")))
                    throw new IllegalArgumentException("Invalid power-up zone enabled flag");
                parseAllowed(
                        zone.property(
                                "allowed", "INSTA_KILL,MAX_AMMO,DOUBLE_POINTS,NUKE,CARPENTER"));
            }
    }
}
