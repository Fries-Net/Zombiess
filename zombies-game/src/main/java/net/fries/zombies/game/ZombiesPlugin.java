package net.fries.zombies.game;

import net.fries.zombies.common.config.AppConfiguration;
import net.fries.zombies.common.config.ConfigurationLoader;
import net.fries.zombies.persistence.JdbcAccountRepository;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paper composition root. Storage callbacks re-enter the server scheduler before touching Paper.
 */
public final class ZombiesPlugin extends JavaPlugin {
    private final AtomicBoolean stopping = new AtomicBoolean();
    private AppConfiguration configuration;
    private JdbcAccountRepository accounts;
    private boolean operational;
    private MatchCoordinator matches;

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            for (String resource :
                    new String[] {
                        "config.yml", "database.yml", "gameplay.yml", "maps/training.yml"
                    }) {
                if (!Files.exists(getDataFolder().toPath().resolve(resource)))
                    saveResource(resource, false);
            }
            configuration = readConfiguration();
            getLogger().setLevel(configuration.logLevel());
            accounts = new JdbcAccountRepository(configuration.database());
            matches = new MatchCoordinator(this);
            getServer().getPluginManager().registerEvents(new MatchListener(matches), this);
            registerCommand("zombies", java.util.List.of("jz"), new ZombiesCommand(this));
            matches.initialize()
                    .whenComplete(
                            (recovered, failure) ->
                                    onServer(
                                            () -> {
                                                operational = failure == null;
                                                if (failure != null) {
                                                    getLogger()
                                                            .severe(
                                                                    "event=storage_startup_failed"
                                                                            + " server="
                                                                            + configuration
                                                                                    .serverId()
                                                                            + " cause="
                                                                            + failureTypes(failure)
                                                                            + " action=check_database_connectivity_credentials_and_schema;"
                                                                            + " restart after"
                                                                            + " correcting the"
                                                                            + " problem");
                                                } else {
                                                    try {
                                                        matches.ready();
                                                    } catch (IllegalArgumentException e) {
                                                        operational = false;
                                                        getLogger()
                                                                .severe(
                                                                        "event=content_rejected"
                                                                                + " reason="
                                                                                + e.getMessage());
                                                        return;
                                                    }
                                                    getServer()
                                                            .getScheduler()
                                                            .runTaskTimer(
                                                                    this, matches::tick, 1, 1);
                                                    getLogger()
                                                            .info(
                                                                    "event=instance_ready server="
                                                                            + configuration
                                                                                    .serverId()
                                                                            + " mode="
                                                                            + configuration
                                                                                    .database()
                                                                                    .mode()
                                                                            + " milestone=playable"
                                                                            + " recovered="
                                                                            + recovered);
                                                }
                                            }));
        } catch (IOException | IllegalArgumentException e) {
            getLogger().severe("event=configuration_rejected reason=" + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException e) {
            getLogger().severe("event=startup_failed action=check_plugin_version_and_dependencies");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
        stopping.set(true);
        operational = false;
        getServer().getScheduler().cancelTasks(this);
        if (accounts != null) {
            var logger = getLogger();
            accounts.closeAsync()
                    .whenComplete(
                            (ignored, error) -> {
                                if (error != null)
                                    logger.severe(
                                            "event=storage_drain_failed"
                                                + " action=inspect_database_and_retry_uncommitted_results");
                                else logger.info("event=storage_closed");
                            });
        }
    }

    AppConfiguration readConfiguration() throws IOException {
        return new ConfigurationLoader().load(getDataFolder().toPath(), System::getenv);
    }

    void reloadConfiguration() throws IOException {
        var candidate = readConfiguration();
        if (!configuration.canReloadFrom(candidate)) {
            throw new IllegalArgumentException(
                    "server-id and database changes require a controlled restart");
        }
        configuration = candidate;
        getLogger().setLevel(candidate.logLevel());
    }

    public void onServer(Runnable callback) {
        if (stopping.get()) return;
        try {
            getServer()
                    .getScheduler()
                    .runTask(
                            this,
                            () -> {
                                if (!stopping.get()) callback.run();
                            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // A callback racing plugin disable owns no world resources and can be discarded safely.
        }
    }

    AppConfiguration configuration() {
        return configuration;
    }

    public JdbcAccountRepository accounts() {
        return accounts;
    }

    boolean operational() {
        return operational && accounts.metrics().ready();
    }

    public MatchCoordinator matches() {
        return matches;
    }

    private String failureTypes(Throwable failure) {
        var types = new java.util.ArrayList<String>();
        for (int depth = 0; failure != null && depth < 6; depth++, failure = failure.getCause()) {
            types.add(failure.getClass().getSimpleName());
        }
        return String.join("/", types);
    }
}
