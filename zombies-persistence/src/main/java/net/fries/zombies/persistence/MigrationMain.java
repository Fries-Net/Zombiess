package net.fries.zombies.persistence;

import org.flywaydb.core.Flyway;

/** Run offline with a dedicated schema-owner identity; no Paper API or Redis is required. */
public final class MigrationMain {
    private MigrationMain() { }
    public static void main(String[] args) {
        String url = required("ZOMBIES_DATABASE_URL");
        if (!url.startsWith("jdbc:postgresql://") || url.contains("@") || url.matches("(?i).*[?&](user|password)=.*")) {
            throw new IllegalArgumentException("Expected PostgreSQL JDBC URL without embedded credentials");
        }
        Flyway.configure().dataSource(url, required("ZOMBIES_DATABASE_USER"), required("ZOMBIES_DATABASE_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }
}
