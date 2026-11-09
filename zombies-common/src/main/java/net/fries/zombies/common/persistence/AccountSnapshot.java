package net.fries.zombies.common.persistence;

import java.util.UUID;

public record AccountSnapshot(UUID playerId, long tokens, long experience, long matchesPlayed,
                              long kills, int highestRound) { }
