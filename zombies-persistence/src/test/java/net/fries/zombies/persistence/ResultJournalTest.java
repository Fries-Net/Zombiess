package net.fries.zombies.persistence;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.config.DatabaseSettings;
import net.fries.zombies.common.match.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class ResultJournalTest {
    @TempDir Path directory;

    private MatchResult result(MatchResult.Outcome outcome) {
        var now = Instant.now();
        return new MatchResult(
                new MatchIdentity(
                        UUID.randomUUID(),
                        "test",
                        new NamespacedId("friesnet:map/training"),
                        "v1",
                        "vanilla"),
                now,
                now,
                outcome,
                List.of(new PlayerResult(UUID.randomUUID(), 2, 5, 3, 1, 2, 45)));
    }

    private JdbcAccountRepository database() {
        return new JdbcAccountRepository(
                new DatabaseSettings(
                        DatabaseSettings.Mode.STANDALONE,
                        "jdbc:h2:file:"
                                + directory.resolve("db").toString().replace('\\', '/')
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        4,
                        2,
                        64,
                        5,
                        true));
    }

    @Test
    void finalAndActiveRecordsReplayAfterRestartExactlyOnce() throws Exception {
        var finalResult = result(MatchResult.Outcome.WIN);
        var abandoned = result(MatchResult.Outcome.ABANDONED);
        try (var disk = new DurableFiles()) {
            var journal = new ResultJournal(directory.resolve("journal"), disk);
            journal.save(finalResult, true).join();
            journal.save(abandoned, false).join();
        }
        try (var db = database();
                var disk = new DurableFiles()) {
            db.initialized().join();
            var journal = new ResultJournal(directory.resolve("journal"), disk);
            assertEquals(2, journal.recover(db).join());
            assertEquals(
                    1,
                    db.account(finalResult.players().getFirst().playerId()).join().matchesPlayed());
            assertEquals(0, journal.recover(db).join());
            // Crash after database commit but before journal removal: replay verifies fingerprint
            // and grants nothing twice.
            journal.save(finalResult, true).join();
            assertEquals(1, journal.recover(db).join());
            assertEquals(
                    2, db.account(finalResult.players().getFirst().playerId()).join().tokens());
            assertEquals(
                    1,
                    db.account(finalResult.players().getFirst().playerId()).join().matchesPlayed());
        }
    }

    @Test
    void unavailableDatabaseLeavesDurableSubmissionForRecovery() throws Exception {
        var r = result(MatchResult.Outcome.LOSS);
        try (var db = database();
                var disk = new DurableFiles()) {
            db.initialized().join();
            db.close();
            var journal = new ResultJournal(directory.resolve("journal"), disk);
            assertThrows(Exception.class, () -> journal.commit(r, db).join());
            assertTrue(
                    Files.isRegularFile(
                            directory
                                    .resolve("journal")
                                    .resolve(r.identity().matchId() + ".result")));
        }
        try (var db = database();
                var disk = new DurableFiles()) {
            db.initialized().join();
            assertEquals(
                    1, new ResultJournal(directory.resolve("journal"), disk).recover(db).join());
            assertEquals(1, db.account(r.players().getFirst().playerId()).join().matchesPlayed());
        }
    }

    @Test
    void corruptionBlocksRecoveryRatherThanSilentlyDroppingRewards() throws Exception {
        var r = result(MatchResult.Outcome.WIN);
        byte[] bytes = ResultJournal.encode(new ResultJournal.Entry(true, r));
        bytes[20] ^= 1;
        assertThrows(java.io.IOException.class, () -> ResultJournal.decode(bytes));
        try (var db = database();
                var disk = new DurableFiles()) {
            db.initialized().join();
            var folder = directory.resolve("journal");
            Files.createDirectories(folder);
            Files.write(folder.resolve(r.identity().matchId() + ".result"), bytes);
            assertThrows(Exception.class, () -> new ResultJournal(folder, disk).recover(db).join());
            assertEquals(0, db.account(r.players().getFirst().playerId()).join().matchesPlayed());
        }
    }

    @Test
    void finalRecordCannotBeOverwrittenByLateCheckpoint() {
        var r = result(MatchResult.Outcome.WIN);
        try (var disk = new DurableFiles()) {
            var j = new ResultJournal(directory.resolve("journal"), disk);
            j.save(r, true).join();
            var active =
                    new MatchResult(
                            r.identity(),
                            r.startedAt(),
                            r.endedAt(),
                            MatchResult.Outcome.ABANDONED,
                            r.players());
            assertThrows(Exception.class, () -> j.save(active, false).join());
            assertThrows(Exception.class, () -> j.save(active, true).join());
        }
    }

    @Test
    void incompleteTemporaryFileDoesNotReplaceLastForcedCheckpoint() throws Exception {
        var r = result(MatchResult.Outcome.ABANDONED);
        try (var db = database();
                var disk = new DurableFiles()) {
            db.initialized().join();
            var folder = directory.resolve("journal");
            var j = new ResultJournal(folder, disk);
            j.save(r, false).join();
            Files.write(
                    folder.resolve(r.identity().matchId() + ".result.tmp"), new byte[] {1, 2, 3});
            assertEquals(1, j.recover(db).join());
            assertEquals(5, db.account(r.players().getFirst().playerId()).join().kills());
        }
    }
}
