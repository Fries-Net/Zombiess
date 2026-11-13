package net.fries.zombies.common.match;

import java.util.Set;
import java.util.UUID;

public record MatchSnapshot(MatchIdentity identity, MatchPhase phase, Set<UUID> players,
                            int initialPlayerCount, boolean resultPersisted, String failure) {
    public MatchSnapshot { players = Set.copyOf(players); }
}
