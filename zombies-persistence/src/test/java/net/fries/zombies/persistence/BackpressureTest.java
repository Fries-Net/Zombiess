package net.fries.zombies.persistence;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.config.DatabaseSettings;
import net.fries.zombies.common.match.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class BackpressureTest {
    @TempDir Path directory;
    @Test void fullQueueRejectsWithoutExecutingSqlOnCaller() throws Exception {
        var settings = new DatabaseSettings(DatabaseSettings.Mode.STANDALONE,
                "jdbc:h2:file:" + directory.resolve("backpressure").toAbsolutePath().toString().replace('\\', '/')
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000", "sa", "", 2, 1, 1, 10, true);
        UUID player = UUID.randomUUID();
        try (var repository = new JdbcAccountRepository(settings)) {
            repository.initialized().join(); repository.commit(result(player)).join();
            try (var blocker = DriverManager.getConnection(settings.url(), "sa", "")) {
                blocker.setAutoCommit(false);
                try (var lock = blocker.prepareStatement("UPDATE accounts SET tokens = tokens WHERE player_id = ?")) {
                    lock.setObject(1, player); lock.executeUpdate();
                }
                var blocked = repository.commit(result(player));
                long deadline = System.nanoTime() + 2_000_000_000L;
                while ((repository.metrics().active() != 1 || repository.metrics().queued() != 0) && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(1, repository.metrics().active()); assertEquals(0, repository.metrics().queued());
                var queued = repository.account(player);
                assertEquals(1, repository.metrics().queued());
                var rejected = repository.account(player);
                assertTrue(rejected.isDone(), "Rejected work must return a completed failed future immediately");
                var failure = assertThrows(CompletionException.class, rejected::join);
                assertInstanceOf(RejectedExecutionException.class, failure.getCause());
                blocker.rollback(); blocked.join(); queued.join();
            }
            assertEquals(2, repository.account(player).join().tokens());
        }
    }
    private MatchResult result(UUID player) {
        return new MatchResult(new MatchIdentity(UUID.randomUUID(), "test-01", new NamespacedId("friesnet:test"), "1", "1"),
                Instant.EPOCH, Instant.EPOCH, MatchResult.Outcome.LOSS,
                List.of(new PlayerResult(player, 1, 1, 0, 0, 1, 0)));
    }
}
