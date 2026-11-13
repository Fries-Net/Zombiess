package net.fries.zombies.common.config;

import net.fries.zombies.common.match.MatchRules;
import java.util.Objects;
import java.util.logging.Level;

public record AppConfiguration(String serverId, Level logLevel, MatchRules matchRules, DatabaseSettings database) {
    public AppConfiguration {
        if (serverId == null || !serverId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("server-id must be a stable lowercase identifier of 1-64 characters");
        }
        Objects.requireNonNull(logLevel); Objects.requireNonNull(matchRules); Objects.requireNonNull(database);
    }
    public boolean canReloadFrom(AppConfiguration candidate) {
        return serverId.equals(candidate.serverId) && database.equals(candidate.database);
    }
}
