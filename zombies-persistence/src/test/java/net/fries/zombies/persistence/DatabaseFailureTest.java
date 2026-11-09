package net.fries.zombies.persistence;

import net.fries.zombies.common.config.DatabaseSettings;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseFailureTest {
    @Test void initializationDoesNotDependOnCallingThreadsContextClassLoader() {
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
        try {
            var settings = new DatabaseSettings(DatabaseSettings.Mode.STANDALONE,
                    "jdbc:h2:mem:loader-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", 2, 1, 8, 5, true);
            try (var repository = new JdbcAccountRepository(settings)) {
                repository.initialized().join(); assertTrue(repository.healthy().join());
            }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    @Test void unavailableDatabaseDoesNotBlockCallerAndReadinessFailsClosed() {
        var settings = new DatabaseSettings(DatabaseSettings.Mode.POSTGRESQL,
                "jdbc:postgresql://127.0.0.1:1/unavailable", "unused", "unused", 1, 1, 1, 1, false);
        long before = System.nanoTime();
        var repository = new JdbcAccountRepository(settings);
        assertTrue((System.nanoTime() - before) < 500_000_000L, "Constructor must not wait for network IO");
        try {
            assertThrows(CompletionException.class, () -> repository.initialized().join());
            assertFalse(repository.metrics().ready());
            assertThrows(CompletionException.class, () -> repository.account(UUID.randomUUID()).join());
        } finally { repository.close(); }
    }
}
