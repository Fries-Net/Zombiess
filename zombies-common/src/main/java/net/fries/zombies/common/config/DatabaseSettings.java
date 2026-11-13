package net.fries.zombies.common.config;

import java.util.Objects;

public record DatabaseSettings(Mode mode, String url, String username, String password,
                               int poolSize, int workers, int queueCapacity, int timeoutSeconds, boolean migrateOnStart) {
    public enum Mode { STANDALONE, POSTGRESQL }
    public DatabaseSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(url);
        Objects.requireNonNull(username); Objects.requireNonNull(password);
        if (poolSize < 1 || poolSize > 32 || workers < 1 || workers > poolSize
                || queueCapacity < 1 || queueCapacity > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("Invalid database pool, worker, queue or timeout limits");
        }
        if (migrateOnStart && poolSize < 2) throw new IllegalArgumentException("Migrations require pool-size of at least 2");
        if (mode == Mode.POSTGRESQL && (!url.startsWith("jdbc:postgresql://")
                || url.matches("(?i).*[?&](user|password)=.*") || url.contains("@"))) {
            throw new IllegalArgumentException("Use a PostgreSQL JDBC URL without embedded credentials");
        }
        if (mode == Mode.STANDALONE && (!url.startsWith("jdbc:h2:") || !url.contains("MODE=PostgreSQL"))) {
            throw new IllegalArgumentException("Standalone mode requires H2 PostgreSQL compatibility mode");
        }
    }
    @Override public String toString() {
        return "DatabaseSettings[mode=" + mode + ", poolSize=" + poolSize + ", workers=" + workers + ", credentials=<redacted>]";
    }
}
