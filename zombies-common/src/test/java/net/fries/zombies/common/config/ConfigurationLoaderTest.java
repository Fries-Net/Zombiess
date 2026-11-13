package net.fries.zombies.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationLoaderTest {
    @TempDir Path directory;
    private final ConfigurationLoader loader = new ConfigurationLoader();
    private final String config = """
            schema-version: 1
            server-id: test-01
            log-level: INFO
            match:
              min-players: 1
              max-players: 4
              countdown-seconds: 10
              reservation-seconds: 120
              private: false
              pause-allowed: false
            """;
    private final String database = """
            schema-version: 1
            mode: STANDALONE
            migrate-on-start: true
            url-env: ZOMBIES_DATABASE_URL
            user-env: ZOMBIES_DATABASE_USER
            password-env: ZOMBIES_DATABASE_PASSWORD
            pool-size: 4
            workers: 2
            queue-capacity: 256
            timeout-seconds: 5
            """;
    @BeforeEach void setup() throws Exception { writeConfig(config); Files.writeString(directory.resolve("database.yml"), database); }
    private void writeConfig(String content) throws Exception { Files.writeString(directory.resolve("config.yml"), content); }
    private AppConfiguration load() throws Exception { return loader.load(directory, key -> null); }
    @Test void standaloneNeedsNoExternalService() throws Exception {
        var loaded = load(); assertEquals("test-01", loaded.serverId());
        assertTrue(loaded.database().url().startsWith("jdbc:h2:file:")); assertEquals(4, loaded.matchRules().maxPlayers());
    }
    @Test void duplicateKeysAreRejectedWithoutLeakingValues() throws Exception {
        writeConfig(config + "server-id: secret-value\n");
        var error = assertThrows(ConfigurationException.class, this::load);
        assertTrue(error.getMessage().contains("config.yml")); assertFalse(error.getMessage().contains("secret-value"));
    }
    @Test void malformedYamlRejected() throws Exception { writeConfig("match: [\n"); assertThrows(ConfigurationException.class, this::load); }
    @Test void unknownKeysRejected() throws Exception { writeConfig(config + "typo: 1\n"); assertThrows(ConfigurationException.class, this::load); }
    @Test void wrongTypeRejected() throws Exception { writeConfig(config.replace("max-players: 4", "max-players: '4'")); assertThrows(ConfigurationException.class, this::load); }
    @Test void invalidCapacityRejected() throws Exception { writeConfig(config.replace("max-players: 4", "max-players: 0")); assertThrows(ConfigurationException.class, this::load); }
    @Test void oldSchemaCannotSilentlyLoad() throws Exception { writeConfig("language: en_US\n"); assertThrows(ConfigurationException.class, this::load); }
    @Test void badLogLevelRejected() throws Exception { writeConfig(config.replace("INFO", "ALL")); assertThrows(ConfigurationException.class, this::load); }
    @Test void missingCredentialsFailClosed() throws Exception {
        Files.writeString(directory.resolve("database.yml"), database.replace("STANDALONE", "POSTGRESQL"));
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("ZOMBIES_DATABASE_URL"));
    }
    @Test void postgresCredentialsResolvedAndRedacted() throws Exception {
        Files.writeString(directory.resolve("database.yml"), database.replace("STANDALONE", "POSTGRESQL"));
        var env = Map.of("ZOMBIES_DATABASE_URL", "jdbc:postgresql://localhost/zombies", "ZOMBIES_DATABASE_USER", "runtime", "ZOMBIES_DATABASE_PASSWORD", "secret-value");
        var loaded = loader.load(directory, env::get);
        assertEquals("secret-value", loaded.database().password()); assertFalse(loaded.toString().contains("secret-value"));
    }
    @Test void failedReloadDoesNotMutateExistingSnapshot() throws Exception {
        var initial = load(); writeConfig(config.replace("max-players: 4", "max-players: -1")); assertThrows(ConfigurationException.class, this::load);
        assertEquals(4, initial.matchRules().maxPlayers());
    }
    @Test void identityAndDatabaseChangesRequireRestart() throws Exception {
        var initial = load(); writeConfig(config.replace("test-01", "test-02")); assertFalse(initial.canReloadFrom(load()));
        writeConfig(config.replace("max-players: 4", "max-players: 8")); assertTrue(initial.canReloadFrom(load()));
    }
    @Test void oversizedFilesRejected() throws Exception { writeConfig("#".repeat(65537)); assertThrows(ConfigurationException.class, this::load); }
    @Test void invalidWorkerLimitsRejected() throws Exception {
        Files.writeString(directory.resolve("database.yml"), database.replace("workers: 2", "workers: 5"));
        assertThrows(ConfigurationException.class, this::load);
    }
    @Test void migrationCannotDeadlockASingleConnectionPool() throws Exception {
        Files.writeString(directory.resolve("database.yml"), database.replace("pool-size: 4", "pool-size: 1").replace("workers: 2", "workers: 1"));
        assertTrue(assertThrows(ConfigurationException.class, this::load).getMessage().contains("at least 2"));
    }
}
