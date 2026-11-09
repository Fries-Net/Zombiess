package net.fries.zombies.common.match;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.persistence.ResultReceipt;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MatchSessionTest {
    private final MutableClock clock = new MutableClock();
    private final Set<UUID> members = Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final MatchIdentity identity = new MatchIdentity(UUID.randomUUID(), "test-01", new NamespacedId("friesnet:test"), "1", "1");

    private MatchSession session(boolean privateMatch) {
        return new MatchSession(identity, new MatchRules(2, 4, Duration.ofSeconds(10), Duration.ofSeconds(120), privateMatch, privateMatch), clock);
    }
    private MatchSession started(boolean privateMatch) {
        var session = session(privateMatch); session.reserve(members); session.openLobby(); members.forEach(session::join);
        session.countdown(); clock.advance(10); assertTrue(session.tick()); session.begin(); return session;
    }
    private MatchResult result(MatchSession session) {
        return new MatchResult(identity, session.startedAt(), clock.instant(), MatchResult.Outcome.LOSS,
                members.stream().map(id -> new PlayerResult(id, 12, 20, 5, 1, 7, 9)).toList());
    }
    @Test void completeLifecycleRequiresExactCommitAndVerifiedRestoration() {
        var session = started(false); var result = result(session); session.finish(result);
        assertThrows(IllegalStateException.class, session::cleanup);
        assertThrows(IllegalArgumentException.class, () -> session.acknowledge(new ResultReceipt(UUID.randomUUID(), result.fingerprint(), true)));
        session.acknowledge(new ResultReceipt(identity.matchId(), result.fingerprint(), true)); session.cleanup(); session.ready(true);
        assertEquals(MatchPhase.READY, session.snapshot().phase()); assertTrue(session.snapshot().players().isEmpty());
        assertEquals(List.of(MatchPhase.RESERVED, MatchPhase.WAITING, MatchPhase.COUNTDOWN, MatchPhase.STARTING,
                MatchPhase.IN_PROGRESS, MatchPhase.ENDING, MatchPhase.CLEANUP, MatchPhase.READY), session.drainEvents().stream().map(MatchEvent::to).toList());
        assertTrue(session.drainEvents().isEmpty()); session.drain(); assertEquals(MatchPhase.DRAINING, session.snapshot().phase());
    }
    @Test void frozenScalingSurvivesPlayersLeaving() {
        var session = started(false); var list = new ArrayList<>(members); session.leave(list.get(0)); session.leave(list.get(1));
        assertEquals(4, session.snapshot().initialPlayerCount()); assertEquals(2, session.snapshot().players().size());
        assertThrows(IllegalStateException.class, () -> session.join(list.get(0)));
    }
    @Test void unknownReservationCannotJoin() {
        var session = session(false); session.reserve(members); session.openLobby();
        assertThrows(IllegalArgumentException.class, () -> session.join(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, session::countdown);
    }
    @Test void leavingBelowMinimumCancelsCountdown() {
        var session = session(false); session.reserve(members); session.openLobby(); var list = new ArrayList<>(members);
        session.join(list.get(0)); session.join(list.get(1)); session.countdown(); session.leave(list.get(0));
        clock.advance(11); assertFalse(session.tick()); assertEquals(MatchPhase.WAITING, session.snapshot().phase());
        session.join(list.get(0)); session.countdown(); clock.advance(9); assertFalse(session.tick()); clock.advance(1); assertTrue(session.tick());
    }
    @Test void reservationExpiryFailsAndCanCleanUpWithoutRewards() {
        var session = session(false); session.reserve(members); clock.advance(120);
        assertFalse(session.tick()); assertEquals(MatchPhase.FAILED, session.snapshot().phase());
        session.cleanup(); session.ready(true); assertEquals(MatchPhase.READY, session.snapshot().phase());
    }
    @Test void expiredReservationRejectedBeforeTick() {
        var session = session(false); session.reserve(members); clock.advance(121);
        assertThrows(IllegalStateException.class, session::openLobby);
    }
    @Test void privateMatchCanPauseAndPublicCannot() {
        var privateSession = started(true); privateSession.pause(); assertFalse(privateSession.tick());
        assertEquals(MatchPhase.PAUSED, privateSession.snapshot().phase()); privateSession.resume();
        assertEquals(MatchPhase.IN_PROGRESS, privateSession.snapshot().phase());
        assertThrows(IllegalStateException.class, () -> started(false).pause());
    }
    @Test void failureAfterStartCannotBypassRewards() {
        var session = started(false); session.fail("world failure");
        assertThrows(IllegalStateException.class, session::cleanup);
        var result = result(session); session.finish(result); session.fail("database unavailable");
        session.finish(result); session.acknowledge(new ResultReceipt(identity.matchId(), result.fingerprint(), false)); session.cleanup();
        session.ready(false); assertEquals(MatchPhase.FAILED, session.snapshot().phase());
        session.cleanup(); session.ready(true); assertEquals(MatchPhase.READY, session.snapshot().phase());
    }
    @Test void mismatchedResultCannotBeCommitted() {
        var session = started(false); var original = result(session);
        var outsider = new MatchResult(identity, session.startedAt(), clock.instant(), MatchResult.Outcome.WIN,
                List.of(new PlayerResult(UUID.randomUUID(), 1, 0, 0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> session.finish(outsider));
        session.finish(original); session.finish(original);
        var changed = new MatchResult(identity, session.startedAt(), clock.instant(), MatchResult.Outcome.WIN, original.players());
        assertThrows(IllegalArgumentException.class, () -> session.finish(changed));
    }
    @Test void snapshotsAndReservationsAreImmutable() {
        var session = session(false); var mutable = new java.util.HashSet<>(members); session.reserve(mutable); mutable.clear(); session.openLobby();
        members.forEach(session::join); assertEquals(4, session.snapshot().players().size());
        assertThrows(UnsupportedOperationException.class, () -> session.snapshot().players().clear());
        assertThrows(IllegalStateException.class, session::begin); assertThrows(IllegalStateException.class, session::drain);
    }
    @Test void invalidCapacitiesAndPublicPauseRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MatchRules(5, 4, Duration.ZERO, Duration.ofMinutes(1), false, false));
        assertThrows(IllegalArgumentException.class, () -> new MatchRules(1, 4, Duration.ZERO, Duration.ofMinutes(1), false, true));
        var session = session(false); assertThrows(IllegalArgumentException.class, () -> session.reserve(Set.of()));
    }
    @Test void canonicalFingerprintIgnoresRosterOrderingButDetectsRewardChanges() {
        var session = started(false); var result = result(session); var reversed = new ArrayList<>(result.players()); java.util.Collections.reverse(reversed);
        assertEquals(result.fingerprint(), new MatchResult(identity, session.startedAt(), clock.instant(), result.outcome(), reversed).fingerprint());
        var first = reversed.getFirst(); reversed.set(0, new PlayerResult(first.playerId(), 12, 20, 5, 1, 8, 9));
        assertNotEquals(result.fingerprint(), new MatchResult(identity, session.startedAt(), clock.instant(), result.outcome(), reversed).fingerprint());
    }
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
