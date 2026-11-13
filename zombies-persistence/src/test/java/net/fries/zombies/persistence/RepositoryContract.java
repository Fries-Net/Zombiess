package net.fries.zombies.persistence;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.config.DatabaseSettings;
import net.fries.zombies.common.match.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

abstract class RepositoryContract {
    JdbcAccountRepository repository;
    abstract DatabaseSettings settings();
    @BeforeEach void open() { repository = new JdbcAccountRepository(settings()); repository.initialized().join(); }
    @AfterEach void close() { if (repository != null) repository.close(); }
    MatchResult result(UUID match, UUID player, long tokens) {
        return new MatchResult(new MatchIdentity(match, "test-01", new NamespacedId("friesnet:test"), "1", "1"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"), MatchResult.Outcome.WIN,
                List.of(new PlayerResult(player, 20, 250, 100, 5, tokens, 1000)));
    }
    @Test void accountDefaultsAndDatabaseHealth() {
        var account = repository.account(UUID.randomUUID()).join();
        assertEquals(0, account.tokens()); assertEquals(0, account.matchesPlayed()); assertTrue(repository.healthy().join());
    }
    @Test void resultAndRewardsCommitExactlyOnce() {
        UUID player = UUID.randomUUID(); var result = result(UUID.randomUUID(), player, 50);
        assertTrue(repository.commit(result).join().newlyCommitted()); assertFalse(repository.commit(result).join().newlyCommitted());
        var account = repository.account(player).join();
        assertEquals(50, account.tokens()); assertEquals(1000, account.experience()); assertEquals(1, account.matchesPlayed());
        assertEquals(250, account.kills()); assertEquals(20, account.highestRound());
    }
    @Test void conflictingReplayCannotChangeRewards() {
        UUID match = UUID.randomUUID(), player = UUID.randomUUID(); repository.commit(result(match, player, 50)).join();
        assertThrows(CompletionException.class, () -> repository.commit(result(match, player, 999)).join());
        assertEquals(50, repository.account(player).join().tokens());
    }
    @Test void concurrentDuplicateDeliveryHasOneWinner() {
        UUID player = UUID.randomUUID(); var result = result(UUID.randomUUID(), player, 25);
        var calls = new ArrayList<CompletableFuture<net.fries.zombies.common.persistence.ResultReceipt>>();
        for (int i = 0; i < 24; i++) calls.add(repository.commit(result));
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, calls.stream().filter(call -> call.join().newlyCommitted()).count());
        assertEquals(25, repository.account(player).join().tokens());
    }
    @Test void concurrentDifferentMatchesDoNotLoseUpdates() {
        UUID player = UUID.randomUUID(); var calls = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < 24; i++) calls.add(repository.commit(result(UUID.randomUUID(), player, 10)));
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        var account = repository.account(player).join(); assertEquals(240, account.tokens()); assertEquals(24, account.matchesPlayed());
    }
    @Test void failedTransactionRollsBackMarkerLedgerAndEveryPlayer() {
        UUID overflowing = new UUID(-1, UUID.randomUUID().getLeastSignificantBits());
        UUID other = new UUID(0, UUID.randomUUID().getLeastSignificantBits()), match = UUID.randomUUID();
        repository.commit(result(UUID.randomUUID(), overflowing, Long.MAX_VALUE)).join();
        var failed = result(match, overflowing, 1);
        var twoPlayers = new MatchResult(failed.identity(), failed.startedAt(), failed.endedAt(), failed.outcome(),
                List.of(failed.players().getFirst(), new PlayerResult(other, 1, 1, 0, 0, 50, 0)));
        assertThrows(CompletionException.class, () -> repository.commit(twoPlayers).join());
        assertEquals(Long.MAX_VALUE, repository.account(overflowing).join().tokens());
        assertEquals(0, repository.account(other).join().tokens());
        assertTrue(repository.commit(result(match, other, 25)).join().newlyCommitted());
        assertEquals(25, repository.account(other).join().tokens());
    }
    @Test void dataAndIdempotencySurviveConnectionPoolRestart() {
        UUID player = UUID.randomUUID(); var result = result(UUID.randomUUID(), player, 30);
        repository.commit(result).join(); repository.close();
        repository = new JdbcAccountRepository(settings()); repository.initialized().join();
        assertFalse(repository.commit(result).join().newlyCommitted()); assertEquals(30, repository.account(player).join().tokens());
    }
    @Test void completionServiceGatesCleanupOnRealTransaction() {
        UUID player = UUID.randomUUID(); var result = result(UUID.randomUUID(), player, 30);
        var session = new MatchSession(result.identity(), new MatchRules(1, 4, Duration.ZERO, Duration.ofMinutes(1), false, false),
                Clock.fixed(result.startedAt(), ZoneOffset.UTC));
        session.reserve(Set.of(player)); session.openLobby(); session.join(player); session.countdown(); session.tick(); session.begin();
        new MatchCompletionService(repository).complete(session, result).join(); session.cleanup(); session.ready(true);
        assertEquals(MatchPhase.READY, session.snapshot().phase()); assertEquals(30, repository.account(player).join().tokens());
    }
    @Test void operationsAfterShutdownAreFailedFutures() {
        repository.close(); assertThrows(CompletionException.class, () -> repository.account(UUID.randomUUID()).join());
    }
    @Test void shutdownDrainsAlreadyAcceptedRewards() {
        UUID player = UUID.randomUUID(); var futures = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < 16; i++) futures.add(repository.commit(result(UUID.randomUUID(), player, 10)));
        repository.closeAsync().join(); CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        repository = new JdbcAccountRepository(settings()); repository.initialized().join(); assertEquals(160, repository.account(player).join().tokens());
    }
}
