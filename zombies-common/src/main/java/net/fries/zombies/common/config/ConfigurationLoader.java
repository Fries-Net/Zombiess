package net.fries.zombies.common.config;

import net.fries.zombies.common.match.MatchRules;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;

/** Constructs a complete candidate before the adapter swaps configuration snapshots. */
public final class ConfigurationLoader {
    public AppConfiguration load(Path directory, Function<String, String> environment) throws IOException {
        var root = read(directory.resolve("config.yml"));
        keys(root, "config.yml", "schema-version", "server-id", "log-level", "match");
        version(root, "config.yml");
        var match = section(root, "match", "config.yml.match");
        keys(match, "config.yml.match", "min-players", "max-players", "countdown-seconds", "reservation-seconds", "private", "pause-allowed");
        MatchRules rules;
        try {
            rules = new MatchRules(integer(match, "min-players", "match"), integer(match, "max-players", "match"),
                    Duration.ofSeconds(integer(match, "countdown-seconds", "match")),
                    Duration.ofSeconds(integer(match, "reservation-seconds", "match")),
                    bool(match, "private", "match"), bool(match, "pause-allowed", "match"));
        } catch (IllegalArgumentException e) { throw new ConfigurationException("config.yml.match", e.getMessage()); }
        var database = read(directory.resolve("database.yml"));
        keys(database, "database.yml", "schema-version", "mode", "url-env", "user-env", "password-env",
                "pool-size", "workers", "queue-capacity", "timeout-seconds", "migrate-on-start");
        version(database, "database.yml");
        DatabaseSettings.Mode mode;
        try { mode = DatabaseSettings.Mode.valueOf(string(database, "mode", "database.yml")); }
        catch (IllegalArgumentException e) { throw new ConfigurationException("database.yml.mode", "expected STANDALONE or POSTGRESQL"); }
        String url, username, password;
        if (mode == DatabaseSettings.Mode.STANDALONE) {
            // The path comes from the trusted plugin directory, not operator-supplied JDBC options.
            String file = directory.toAbsolutePath().normalize().resolve("data/zombies").toString().replace('\\', '/');
            if (file.contains(";")) throw new ConfigurationException("database.yml", "data directory cannot contain a semicolon");
            url = "jdbc:h2:file:" + file + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
            username = "sa"; password = "";
        } else {
            url = env(database, "url-env", environment);
            username = env(database, "user-env", environment);
            password = env(database, "password-env", environment);
        }
        try {
            var settings = new DatabaseSettings(mode, url, username, password,
                    integer(database, "pool-size", "database.yml"), integer(database, "workers", "database.yml"),
                    integer(database, "queue-capacity", "database.yml"), integer(database, "timeout-seconds", "database.yml"),
                    bool(database, "migrate-on-start", "database.yml"));
            String level = string(root, "log-level", "config.yml");
            if (!Set.of("SEVERE", "WARNING", "INFO", "FINE", "FINER", "FINEST").contains(level)) {
                throw new ConfigurationException("config.yml.log-level", "expected a named Java logging level");
            }
            return new AppConfiguration(string(root, "server-id", "config.yml"), Level.parse(level), rules, settings);
        } catch (ConfigurationException e) { throw e; }
        catch (IllegalArgumentException e) { throw new ConfigurationException("configuration", e.getMessage()); }
    }

    private Map<?, ?> read(Path file) throws IOException {
        if (Files.size(file) > 65536) throw new ConfigurationException(file.getFileName().toString(), "file exceeds 64 KiB");
        try {
            var settings = LoadSettings.builder().setLabel(file.getFileName().toString())
                    .setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).setCodePointLimit(65536).build();
            Object loaded = new Load(settings).loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> map)) throw new ConfigurationException(file.getFileName().toString(), "expected YAML mapping");
            return map;
        } catch (ConfigurationException e) { throw e; }
        catch (RuntimeException e) {
            // YAML exceptions can quote credentials or other sensitive input; expose the filename only.
            throw new ConfigurationException(file.getFileName().toString(), "invalid YAML (check syntax, duplicate keys and aliases)");
        }
    }
    private void keys(Map<?, ?> map, String path, String... allowed) {
        var names = Set.of(allowed);
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !names.contains(name)) throw new ConfigurationException(path, "contains an unknown setting");
        }
    }
    private void version(Map<?, ?> map, String path) {
        if (integer(map, "schema-version", path) != 1) throw new ConfigurationException(path + ".schema-version", "expected 1; legacy configs require manual migration");
    }
    private Map<?, ?> section(Map<?, ?> map, String key, String path) {
        if (map.get(key) instanceof Map<?, ?> child) return child;
        throw new ConfigurationException(path, "required mapping is missing");
    }
    private String string(Map<?, ?> map, String key, String path) {
        if (map.get(key) instanceof String text && !text.isBlank()) return text;
        throw new ConfigurationException(path + "." + key, "required nonempty string is missing");
    }
    private int integer(Map<?, ?> map, String key, String path) {
        if (map.get(key) instanceof Integer number) return number;
        throw new ConfigurationException(path + "." + key, "expected integer");
    }
    private boolean bool(Map<?, ?> map, String key, String path) {
        if (map.get(key) instanceof Boolean value) return value;
        throw new ConfigurationException(path + "." + key, "expected true or false");
    }
    private String env(Map<?, ?> map, String key, Function<String, String> environment) {
        String name = string(map, key, "database.yml");
        if (!name.matches("[A-Z][A-Z0-9_]{0,63}")) throw new ConfigurationException("database.yml." + key, "expected environment variable name");
        String value = environment.apply(name);
        if (value == null || value.isBlank()) throw new ConfigurationException("database.yml." + key, "required environment variable is unset: " + name);
        return value;
    }
}
