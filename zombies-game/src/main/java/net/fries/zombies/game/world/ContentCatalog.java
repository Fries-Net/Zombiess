package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One immutable startup snapshot. Active matches retain these exact content values. */
public record ContentCatalog(
        Gameplay gameplay, Map<String, MapTemplate> maps, MysteryPools mysteryPools) {
    public ContentCatalog {
        maps = Map.copyOf(maps);
    }

    public static ContentCatalog load(Path directory) throws IOException {
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
                if (maps.values().stream().anyMatch(m -> m.id().equals(map.id())))
                    throw new IOException("Duplicate map identity");
                maps.put(key, map);
            }
        }
        return new ContentCatalog(gameplay, maps, mysteryPools);
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
}
