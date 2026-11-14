package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.util.*;

/** Typed editor object; content references remain namespaced, independent of Paper visuals. */
public record MapObject(
        String id,
        Kind kind,
        MapTemplate.Point point,
        MapTemplate.Box region,
        Set<String> rooms,
        NamespacedId asset,
        Map<String, String> properties) {
    public enum Kind {
        PLAYER_SPAWN,
        SPECTATOR_SPAWN,
        ZOMBIE_REGION,
        WALL_WEAPON,
        AMMO,
        MYSTERY_BOX,
        PERK,
        PACK_A_PUNCH,
        POWER,
        HOLOGRAM,
        BOSS_SPAWN,
        RANGE_TARGET,
        CUSTOM
    }

    public MapObject {
        rooms = Set.copyOf(rooms);
        properties = Map.copyOf(properties);
        if (!id.matches("[a-z0-9_-]{1,32}")
                || properties.size() > 24
                || properties.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !e.getKey().matches("[a-z0-9-]{1,32}")
                                                || e.getValue().length() > 256))
            throw new IllegalArgumentException("Invalid map object");
        if ((kind == Kind.ZOMBIE_REGION || kind == Kind.BOSS_SPAWN) && region.volume() > 256)
            throw new IllegalArgumentException("Spawn region exceeds 256 blocks");
    }

    public String property(String key, String fallback) {
        return properties.getOrDefault(key, fallback);
    }

    public static boolean active(Kind kind) {
        return switch (kind) {
            case PLAYER_SPAWN,
                    SPECTATOR_SPAWN,
                    ZOMBIE_REGION,
                    WALL_WEAPON,
                    AMMO,
                    HOLOGRAM,
                    MYSTERY_BOX ->
                    true;
            default -> false;
        };
    }

    public int integer(String key, int fallback, int min, int max) {
        int value = Integer.parseInt(property(key, Integer.toString(fallback)));
        if (value < min || value > max)
            throw new IllegalArgumentException("Invalid object property: " + key);
        return value;
    }

    public static MapObject load(ContentYaml n) {
        n.keys("id", "kind", "point", "region", "rooms", "asset", "properties");
        var properties = new LinkedHashMap<String, String>();
        n.node("properties")
                .values()
                .forEach(
                        (key, value) -> {
                            if (!(key instanceof String k) || !(value instanceof String v))
                                throw new IllegalArgumentException(
                                        "Object properties must be text");
                            properties.put(k, v);
                        });
        return new MapObject(
                n.text("id"),
                Kind.valueOf(n.text("kind")),
                MapTemplate.point(n.node("point")),
                MapTemplate.box(n.node("region")),
                Set.copyOf(n.strings("rooms")),
                new NamespacedId(n.text("asset")),
                properties);
    }
}
