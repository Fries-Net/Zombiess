package net.fries.zombies.common.match;

import java.time.Duration;
import java.util.Objects;

public record MatchRules(int minPlayers, int maxPlayers, Duration countdown,
                         Duration reservationTtl, boolean privateMatch, boolean pauseAllowed) {
    public MatchRules {
        if (minPlayers < 1 || maxPlayers < minPlayers || maxPlayers > 64) {
            throw new IllegalArgumentException("Player capacity must satisfy 1 <= min <= max <= 64");
        }
        Objects.requireNonNull(countdown, "countdown");
        Objects.requireNonNull(reservationTtl, "reservationTtl");
        if (countdown.isNegative() || countdown.compareTo(Duration.ofMinutes(5)) > 0
                || reservationTtl.isNegative() || reservationTtl.isZero()
                || reservationTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid countdown or reservation TTL");
        }
        if (pauseAllowed && !privateMatch) throw new IllegalArgumentException("Only private matches may pause");
    }
}
