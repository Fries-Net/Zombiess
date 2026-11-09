package net.fries.zombies.common.match;

import net.fries.zombies.common.NamespacedId;
import java.util.Objects;
import java.util.UUID;

public record MatchIdentity(UUID matchId, String serverId, NamespacedId mapId,
                            String mapVersion, String packVersion) {
    public MatchIdentity {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(mapId, "mapId");
        for (String value : new String[]{serverId, mapVersion, packVersion}) {
            if (value == null || !value.matches("[a-zA-Z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("Server and content versions must be safe identifiers of 1-64 characters");
            }
        }
    }
}
