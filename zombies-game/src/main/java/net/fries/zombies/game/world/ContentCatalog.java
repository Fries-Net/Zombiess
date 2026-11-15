package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One immutable startup snapshot. Active matches retain these exact content values. */
public record ContentCatalog(
        Gameplay gameplay,
        Map<String, MapTemplate> maps,
        MysteryPools mysteryPools,
        Perks perks,
        WeaponUpgrades upgrades,
        HudSettings hud,
        ZombieArchetypes archetypes,
        PowerUps powerups) {
    public ContentCatalog {
        maps = Map.copyOf(maps);
    }

    public static ContentCatalog load(Path directory) throws IOException {
        rebalance(directory);
        try (var input = ContentCatalog.class.getResourceAsStream("/gameplay.yml")) {
            if (input == null) throw new IOException("Bundled gameplay content missing");
            Path file = directory.resolve("gameplay.yml");
            byte[] old = ContentYaml.readBytes(file);
            byte[] next = GameplayMigration.upgrade(old, input.readAllBytes());
            if (!java.util.Arrays.equals(old, next)) {
                Path backup =
                        file.resolveSibling(
                                "gameplay.v"
                                        + ContentYaml.parse(old).integer("schema-version", 1, 3)
                                        + ".backup.yml");
                if (!Files.exists(backup))
                    net.fries.zombies.persistence.DurableFiles.replace(backup, old);
                // Validate the complete candidate before replacing a deployed content document.
                Path candidate = file.resolveSibling("gameplay.migration.yml");
                net.fries.zombies.persistence.DurableFiles.replace(candidate, next);
                Gameplay.load(candidate);
                net.fries.zombies.persistence.DurableFiles.replace(file, next);
                Files.delete(candidate);
            }
        }
        var gameplay = Gameplay.load(directory.resolve("gameplay.yml"));
        var mysteryPools = MysteryPools.load(directory.resolve("mystery-boxes.yml"), gameplay);
        var perks = Perks.load(directory.resolve("perks.yml"));
        var upgrades = WeaponUpgrades.load(directory.resolve("weapon-upgrades.yml"), gameplay);
        var hud = HudSettings.load(directory.resolve("hud.yml"));
        var archetypes = ZombieArchetypes.load(directory.resolve("zombie-archetypes.yml"));
        if (archetypes.types().values().stream()
                .anyMatch(t -> !t.provider().value().equals("minecraft:zombie")))
            throw new IllegalArgumentException("Unavailable zombie provider");
        var powerups = PowerUps.load(directory.resolve("power-ups.yml"));
        powerups.definitions().values().forEach(d -> validatePowerupMaterial(d.material()));
        var maps = new LinkedHashMap<String, MapTemplate>();
        try (var paths = Files.list(directory.resolve("maps"))) {
            var files =
                    paths.filter(p -> p.getFileName().toString().matches("[a-z0-9_-]+\\.yml"))
                            .sorted()
                            .toList();
            if (files.isEmpty() || files.size() > 32) throw new IOException("Expected 1..32 maps");
            for (var path : files) {
                if (path.getFileName().toString().equals("training.yml"))
                    migrateTraining(directory, path);
                String key = path.getFileName().toString().replace(".yml", "");
                var map = MapTemplate.load(path, gameplay);
                mysteryPools.validate(map);
                perks.validate(map);
                upgrades.validate(map);
                powerups.validate(map);
                if (maps.values().stream().anyMatch(m -> m.id().equals(map.id())))
                    throw new IOException("Duplicate map identity");
                maps.put(key, map);
            }
        }
        return new ContentCatalog(
                gameplay, maps, mysteryPools, perks, upgrades, hud, archetypes, powerups);
    }

    public static void validatePowerupMaterial(String name) {
        var material = org.bukkit.Material.matchMaterial(name);
        if (material == null || material.isAir() || !material.isItem())
            throw new IllegalArgumentException("Power-up material must be a non-air item");
    }

    private static void migrateTraining(Path directory, Path file) throws IOException {
        try (var legacy =
                        ContentCatalog.class.getResourceAsStream(
                                "/maps/migrations/phase2-training.yml");
                var replacement = ContentCatalog.class.getResourceAsStream("/maps/training.yml")) {
            if (legacy == null || replacement == null) return;
            byte[] old = ContentYaml.readBytes(file);
            byte[] upgraded =
                    MapContentMigration.upgradeDefault(
                            old, legacy.readAllBytes(), replacement.readAllBytes());
            if (!java.util.Arrays.equals(old, upgraded)) {
                var backup = directory.resolve("maps/backups/training-v1.yml");
                if (!Files.exists(backup))
                    net.fries.zombies.persistence.DurableFiles.replace(backup, old);
                net.fries.zombies.persistence.DurableFiles.replace(file, upgraded);
            }
        }
    }

    private static void rebalance(Path directory) throws IOException {
        var g = directory.resolve("gameplay.yml");
        var u = directory.resolve("weapon-upgrades.yml");
        byte[] oldG = ContentYaml.readBytes(g), oldU = ContentYaml.readBytes(u);
        var result =
                WeaponBalanceMigration.upgrade(
                        oldG,
                        oldU,
                        resource("/migrations/phase3-gameplay.yml"),
                        resource("/migrations/phase3-weapon-upgrades.yml"),
                        resource("/gameplay.yml"),
                        resource("/weapon-upgrades.yml"));
        if (Arrays.equals(oldG, result.gameplay()) && Arrays.equals(oldU, result.upgrades()))
            return;
        var candidate = directory.resolve("gameplay.balance-candidate.yml");
        try {
            net.fries.zombies.persistence.DurableFiles.replace(candidate, result.gameplay());
            var gameplay = Gameplay.load(candidate);
            WeaponUpgrades.parse(result.upgrades(), gameplay);
            for (var entry :
                    Map.of(
                                    "gameplay.pre-3-5.backup.yml",
                                    oldG,
                                    "weapon-upgrades.pre-3-5.backup.yml",
                                    oldU)
                            .entrySet())
                if (!Files.exists(directory.resolve(entry.getKey())))
                    net.fries.zombies.persistence.DurableFiles.replace(
                            directory.resolve(entry.getKey()), entry.getValue());
            // New upgrade stats are also compatible with the old shipped weapons. Write them first;
            // rerunning after interruption recognizes both old and new shipped upgrade documents.
            net.fries.zombies.persistence.DurableFiles.replace(u, result.upgrades());
            net.fries.zombies.persistence.DurableFiles.replace(g, result.gameplay());
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (var input = ContentCatalog.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing bundled content " + path);
            return input.readAllBytes();
        }
    }
}
