package net.fries.zombies.common.match;

import java.util.Objects;
import java.util.UUID;

/** Permanent reward amounts are separate from ephemeral match points. */
public record PlayerResult(UUID playerId, int rounds, int kills, int headshots, int revives,
                           long tokens, long experience) {
    public PlayerResult {
        Objects.requireNonNull(playerId, "playerId");
        if (rounds < 0 || kills < 0 || headshots < 0 || headshots > kills || revives < 0
                || tokens < 0 || experience < 0) throw new IllegalArgumentException("Invalid player result");
    }
}
