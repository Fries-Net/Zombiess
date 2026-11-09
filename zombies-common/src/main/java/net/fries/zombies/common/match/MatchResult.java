package net.fries.zombies.common.match;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record MatchResult(MatchIdentity identity, Instant startedAt, Instant endedAt,
                          Outcome outcome, List<PlayerResult> players) {
    public enum Outcome { WIN, LOSS, ABANDONED, FAILED }
    public MatchResult {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(outcome, "outcome");
        players = List.copyOf(players).stream().sorted(Comparator.comparing(p -> p.playerId().toString())).toList();
        if (endedAt.isBefore(startedAt) || players.isEmpty() || players.size() > 64
                || new HashSet<>(players.stream().map(PlayerResult::playerId).toList()).size() != players.size()) {
            throw new IllegalArgumentException("Invalid match interval or duplicate/empty participants");
        }
    }

    /** Canonical length-delimited encoding; changes to a retried result are never silently accepted. */
    public String fingerprint() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                out.writeUTF(identity.matchId().toString()); out.writeUTF(identity.serverId());
                out.writeUTF(identity.mapId().value()); out.writeUTF(identity.mapVersion()); out.writeUTF(identity.packVersion());
                out.writeUTF(startedAt.toString()); out.writeUTF(endedAt.toString()); out.writeUTF(outcome.name());
                out.writeInt(players.size());
                for (var p : players) {
                    out.writeUTF(p.playerId().toString()); out.writeInt(p.rounds()); out.writeInt(p.kills());
                    out.writeInt(p.headshots()); out.writeInt(p.revives()); out.writeLong(p.tokens()); out.writeLong(p.experience());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
