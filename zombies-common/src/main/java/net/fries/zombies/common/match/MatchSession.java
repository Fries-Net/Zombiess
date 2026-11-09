package net.fries.zombies.common.match;

import net.fries.zombies.common.persistence.ResultReceipt;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Synchronized domain aggregate. Adapters drain immutable events after each operation. No world access. */
public final class MatchSession {
    private final MatchIdentity identity;
    private final MatchRules rules;
    private final Clock clock;
    private final Set<UUID> reservations = new HashSet<>();
    private final Set<UUID> players = new HashSet<>();
    private final List<MatchEvent> events = new ArrayList<>();
    private Set<UUID> starters = Set.of();
    private MatchPhase phase = MatchPhase.IDLE;
    private Instant reservationExpires;
    private Instant countdownEnds;
    private Instant startedAt;
    private MatchResult result;
    private boolean persisted;
    private String failure = "";

    public MatchSession(MatchIdentity identity, MatchRules rules, Clock clock) {
        this.identity = Objects.requireNonNull(identity);
        this.rules = Objects.requireNonNull(rules);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void reserve(Set<UUID> members) {
        require(MatchPhase.IDLE);
        var copy = Set.copyOf(members);
        if (copy.isEmpty() || copy.size() > rules.maxPlayers()) throw new IllegalArgumentException("Invalid reservation size");
        reservations.addAll(copy);
        reservationExpires = clock.instant().plus(rules.reservationTtl());
        move(MatchPhase.RESERVED, "reserved");
    }

    public synchronized void openLobby() { require(MatchPhase.RESERVED); checkReservation(); move(MatchPhase.WAITING, "lobby ready"); }

    public synchronized void join(UUID player) {
        require(MatchPhase.WAITING, MatchPhase.COUNTDOWN);
        checkReservation();
        if (!reservations.contains(Objects.requireNonNull(player))) throw new IllegalArgumentException("Player has no reservation");
        if (!players.contains(player) && players.size() >= rules.maxPlayers()) throw new IllegalStateException("Match full");
        players.add(player);
    }

    public synchronized void leave(UUID player) {
        if (phase == MatchPhase.CLEANUP || phase == MatchPhase.READY || phase == MatchPhase.DRAINING) return;
        players.remove(player);
        if (phase == MatchPhase.COUNTDOWN && players.size() < rules.minPlayers()) move(MatchPhase.WAITING, "insufficient players");
    }

    public synchronized void countdown() {
        require(MatchPhase.WAITING); checkReservation(); checkCapacity();
        countdownEnds = clock.instant().plus(rules.countdown());
        move(MatchPhase.COUNTDOWN, "countdown started");
    }

    /** Returns true when the adapter should prepare the world and starting equipment. */
    public synchronized boolean tick() {
        if (phase == MatchPhase.RESERVED || phase == MatchPhase.WAITING || phase == MatchPhase.COUNTDOWN) {
            if (!clock.instant().isBefore(reservationExpires)) { fail("reservation expired"); return false; }
        }
        if (phase != MatchPhase.COUNTDOWN || clock.instant().isBefore(countdownEnds)) return false;
        checkCapacity();
        move(MatchPhase.STARTING, "countdown elapsed");
        return true;
    }

    public synchronized void begin() {
        require(MatchPhase.STARTING); checkCapacity();
        starters = Set.copyOf(players); startedAt = clock.instant();
        move(MatchPhase.IN_PROGRESS, "match started");
    }

    public synchronized void pause() {
        require(MatchPhase.IN_PROGRESS);
        if (!rules.privateMatch() || !rules.pauseAllowed()) throw new IllegalStateException("Pausing is disabled");
        move(MatchPhase.PAUSED, "paused");
    }
    public synchronized void resume() { require(MatchPhase.PAUSED); move(MatchPhase.IN_PROGRESS, "resumed"); }

    public synchronized void finish(MatchResult completed) {
        require(MatchPhase.IN_PROGRESS, MatchPhase.PAUSED, MatchPhase.FAILED, MatchPhase.ENDING);
        Objects.requireNonNull(completed);
        if (result != null) {
            if (!result.fingerprint().equals(completed.fingerprint())) throw new IllegalArgumentException("Result changed during retry");
            if (phase == MatchPhase.ENDING) return;
        } else if (startedAt == null || !completed.identity().equals(identity) || !completed.startedAt().equals(startedAt)
                || !Set.copyOf(completed.players().stream().map(PlayerResult::playerId).toList()).equals(starters)) {
            throw new IllegalArgumentException("Result does not match the frozen match roster and identity");
        }
        result = completed;
        move(MatchPhase.ENDING, "awaiting durable result");
    }

    public synchronized void acknowledge(ResultReceipt receipt) {
        require(MatchPhase.ENDING);
        if (!receipt.matchId().equals(identity.matchId()) || !receipt.fingerprint().equals(result.fingerprint())) {
            throw new IllegalArgumentException("Mismatched persistence receipt");
        }
        persisted = true;
    }

    public synchronized void cleanup() {
        require(MatchPhase.ENDING, MatchPhase.FAILED);
        if (startedAt != null && !persisted) throw new IllegalStateException("Cannot release an uncommitted match");
        move(MatchPhase.CLEANUP, "cleanup started");
    }

    /** Adapter calls only after tasks, players, entities and world restoration have been checked. */
    public synchronized void ready(boolean restorationVerified) {
        require(MatchPhase.CLEANUP);
        if (!restorationVerified) { fail("restoration failed"); return; }
        players.clear(); reservations.clear();
        move(MatchPhase.READY, "restoration verified");
    }

    public synchronized void drain() { require(MatchPhase.IDLE, MatchPhase.READY); move(MatchPhase.DRAINING, "maintenance"); }
    public synchronized void fail(String reason) {
        require(MatchPhase.RESERVED, MatchPhase.WAITING, MatchPhase.COUNTDOWN, MatchPhase.STARTING,
                MatchPhase.IN_PROGRESS, MatchPhase.PAUSED, MatchPhase.ENDING, MatchPhase.CLEANUP);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Failure reason required");
        failure = reason;
        move(MatchPhase.FAILED, reason);
    }

    public synchronized MatchSnapshot snapshot() { return new MatchSnapshot(identity, phase, players, starters.size(), persisted, failure); }
    public synchronized List<MatchEvent> drainEvents() { var copy = List.copyOf(events); events.clear(); return copy; }
    public synchronized Instant startedAt() { return startedAt; }
    private void checkCapacity() {
        if (players.size() < rules.minPlayers() || players.size() > rules.maxPlayers()) throw new IllegalStateException("Invalid player count");
    }
    private void checkReservation() {
        if (!clock.instant().isBefore(reservationExpires)) throw new IllegalStateException("Reservation expired");
    }
    private void require(MatchPhase... allowed) {
        for (var candidate : allowed) if (phase == candidate) return;
        throw new IllegalStateException("Operation is unavailable in " + phase);
    }
    private void move(MatchPhase next, String reason) {
        events.add(new MatchEvent(identity.matchId(), phase, next, clock.instant(), reason)); phase = next;
    }
}
