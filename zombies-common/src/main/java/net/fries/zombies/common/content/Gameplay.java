package net.fries.zombies.common.content;

import net.fries.zombies.common.NamespacedId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public record Gameplay(
        Map<NamespacedId, Weapon> weapons,
        NamespacedId starter,
        Waves waves,
        int startingPoints,
        int killPoints,
        int headshotPoints,
        int roundPoints,
        int bleedoutTicks,
        int reviveTicks,
        int repairTicks,
        int repairPoints,
        int tokensPerRound,
        int experiencePerKill,
        int victoryTokens) {
    public Gameplay {
        weapons = Map.copyOf(weapons);
    }

    public record Weapon(
            NamespacedId id,
            String name,
            String material,
            int magazine,
            int reserve,
            double damage,
            double headshotMultiplier,
            double range,
            int cooldownTicks,
            int reloadTicks,
            int price,
            int ammoPrice,
            Behaviour behaviour) {}

    public enum FireMode {
        SEMI_AUTOMATIC,
        AUTOMATIC,
        BURST
    }

    public record Behaviour(
            FireMode mode,
            int burstShots,
            int burstIntervalTicks,
            String sound,
            float pitch,
            String particle,
            NamespacedId asset,
            int pierceTargets) {
        public static Behaviour legacy(NamespacedId id) {
            return new Behaviour(
                    FireMode.SEMI_AUTOMATIC,
                    1,
                    1,
                    "minecraft:entity.firework_rocket.blast",
                    1.8f,
                    "CRIT",
                    id,
                    1);
        }
    }

    public record Waves(
            int finalRound,
            int baseCount,
            int countPerRound,
            double extraPlayerFactor,
            double baseHealth,
            double healthPerRound,
            double baseDamage,
            double damagePerRound,
            double speed,
            int spawnTicks,
            int intermissionTicks,
            int aliveLimit,
            int timeoutTicks) {
        public Wave at(int round, int frozenPlayers) {
            if (round < 1 || round > finalRound || frozenPlayers < 1 || frozenPlayers > 64)
                throw new IllegalArgumentException("Invalid wave inputs");
            int count =
                    (int)
                            Math.ceil(
                                    (baseCount + (round - 1) * countPerRound)
                                            * (1 + (frozenPlayers - 1) * extraPlayerFactor));
            return new Wave(
                    count,
                    baseHealth + (round - 1) * healthPerRound,
                    baseDamage + (round - 1) * damagePerRound,
                    Math.min(.36, speed + (round - 1) * .003));
        }
    }

    public record Wave(int count, double health, double damage, double speed) {}

    public static Gameplay load(Path path) throws IOException {
        var root = ContentYaml.read(path);
        root.keys("schema-version", "starter", "weapons", "waves", "economy", "life");
        int schema = root.integer("schema-version", 1, 2);
        var weapons = new LinkedHashMap<NamespacedId, Weapon>();
        for (var w : root.nodes("weapons")) {
            var fields =
                    new java.util.ArrayList<>(
                            java.util.List.of(
                                    "id",
                                    "name",
                                    "material",
                                    "magazine",
                                    "reserve",
                                    "damage",
                                    "headshot-multiplier",
                                    "range",
                                    "cooldown-ticks",
                                    "reload-ticks",
                                    "price",
                                    "ammo-price"));
            if (schema >= 2)
                fields.addAll(
                        java.util.List.of(
                                "fire-mode",
                                "burst-shots",
                                "burst-interval-ticks",
                                "fire-sound",
                                "sound-pitch",
                                "shot-particle",
                                "asset",
                                "pierce-targets"));
            w.keys(fields.toArray(String[]::new));
            var id = new NamespacedId(w.text("id"));
            var behaviour =
                    schema == 1
                            ? Behaviour.legacy(id)
                            : new Behaviour(
                                    FireMode.valueOf(w.text("fire-mode")),
                                    w.integer("burst-shots", 1, 10),
                                    w.integer("burst-interval-ticks", 1, 200),
                                    new NamespacedId(w.text("fire-sound")).value(),
                                    (float) w.number("sound-pitch", .5, 2),
                                    w.text("shot-particle"),
                                    new NamespacedId(w.text("asset")),
                                    w.integer("pierce-targets", 1, 16));
            var weapon =
                    new Weapon(
                            id,
                            w.text("name"),
                            w.text("material"),
                            w.integer("magazine", 1, 200),
                            w.integer("reserve", 1, 2000),
                            w.number("damage", .1, 1000),
                            w.number("headshot-multiplier", 1, 10),
                            w.number("range", 1, 128),
                            w.integer("cooldown-ticks", 1, 400),
                            w.integer("reload-ticks", 1, 1200),
                            w.integer("price", 0, 1000000),
                            w.integer("ammo-price", 1, 100000),
                            behaviour);
            if (weapons.put(id, weapon) != null)
                throw new IllegalArgumentException("Duplicate weapon identity");
        }
        if (weapons.isEmpty() || weapons.size() > 256)
            throw new IllegalArgumentException("Expected 1..256 weapon definitions");
        var starter = new NamespacedId(root.text("starter"));
        if (!weapons.containsKey(starter))
            throw new IllegalArgumentException("Unknown starting weapon");
        var w = root.node("waves");
        w.keys(
                "final-round",
                "base-count",
                "count-per-round",
                "extra-player-factor",
                "base-health",
                "health-per-round",
                "base-damage",
                "damage-per-round",
                "speed",
                "spawn-ticks",
                "intermission-ticks",
                "alive-limit",
                "timeout-ticks");
        var waves =
                new Waves(
                        w.integer("final-round", 1, 100),
                        w.integer("base-count", 1, 100),
                        w.integer("count-per-round", 0, 20),
                        w.number("extra-player-factor", 0, 2),
                        w.number("base-health", 1, 500),
                        w.number("health-per-round", 0, 100),
                        w.number("base-damage", .1, 20),
                        w.number("damage-per-round", 0, 2),
                        w.number("speed", .1, .3),
                        w.integer("spawn-ticks", 1, 200),
                        w.integer("intermission-ticks", 1, 1200),
                        w.integer("alive-limit", 1, 100),
                        w.integer("timeout-ticks", 200, 72000));
        var e = root.node("economy");
        e.keys(
                "starting-points",
                "kill-points",
                "headshot-points",
                "round-points",
                "repair-points",
                "tokens-per-round",
                "experience-per-kill",
                "victory-tokens");
        var l = root.node("life");
        l.keys("bleedout-ticks", "revive-ticks", "repair-ticks");
        return new Gameplay(
                weapons,
                starter,
                waves,
                e.integer("starting-points", 0, 100000),
                e.integer("kill-points", 1, 1000),
                e.integer("headshot-points", 0, 1000),
                e.integer("round-points", 0, 10000),
                l.integer("bleedout-ticks", 20, 2400),
                l.integer("revive-ticks", 1, 600),
                l.integer("repair-ticks", 1, 600),
                e.integer("repair-points", 0, 1000),
                e.integer("tokens-per-round", 0, 1000),
                e.integer("experience-per-kill", 0, 1000),
                e.integer("victory-tokens", 0, 10000));
    }
}
