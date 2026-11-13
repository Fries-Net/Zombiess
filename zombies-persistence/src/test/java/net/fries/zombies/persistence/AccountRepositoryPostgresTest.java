package net.fries.zombies.persistence;

import net.fries.zombies.common.config.DatabaseSettings;

/** Uses a disposable PostgreSQL database supplied by CI or Compose. Missing credentials fail the suite. */
class AccountRepositoryPostgresTest extends RepositoryContract {
    @Override DatabaseSettings settings() {
        return new DatabaseSettings(DatabaseSettings.Mode.POSTGRESQL, required("ZOMBIES_TEST_DATABASE_URL"),
                required("ZOMBIES_TEST_DATABASE_USER"), required("ZOMBIES_TEST_DATABASE_PASSWORD"), 4, 2, 256, 5, true);
    }
    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Integration tests require " + name);
        return value;
    }
}
