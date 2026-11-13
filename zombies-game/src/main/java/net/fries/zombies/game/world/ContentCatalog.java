package net.fries.zombies.game.world;

import net.fries.zombies.common.content.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One immutable startup snapshot. Active matches retain these exact content values. */
public record ContentCatalog(Gameplay gameplay, Map<String, MapTemplate> maps) {
    public ContentCatalog {
        maps = Map.copyOf(maps);
    }

    public static ContentCatalog load(Path directory) throws IOException {
        var gameplay = Gameplay.load(directory.resolve("gameplay.yml"));
        var maps = new LinkedHashMap<String, MapTemplate>();
        try (var paths = Files.list(directory.resolve("maps"))) {
            var files =
                    paths.filter(p -> p.getFileName().toString().matches("[a-z0-9_-]+\\.yml"))
                            .sorted()
                            .toList();
            if (files.isEmpty() || files.size() > 32) throw new IOException("Expected 1..32 maps");
            for (var path : files) {
                String key = path.getFileName().toString().replace(".yml", "");
                var map = MapTemplate.load(path, gameplay);
                if (maps.values().stream().anyMatch(m -> m.id().equals(map.id())))
                    throw new IOException("Duplicate map identity");
                maps.put(key, map);
            }
        }
        return new ContentCatalog(gameplay, maps);
    }
}
