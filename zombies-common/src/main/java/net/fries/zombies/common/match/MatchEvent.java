package net.fries.zombies.common.match;

import java.time.Instant;
import java.util.UUID;

public record MatchEvent(UUID matchId, MatchPhase from, MatchPhase to, Instant at, String reason) { }
