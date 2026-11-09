package net.fries.zombies.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.fries.zombies.common.config.DatabaseSettings;
import net.fries.zombies.common.match.MatchResult;
import net.fries.zombies.common.match.PlayerResult;
import net.fries.zombies.common.persistence.AccountRepository;
import net.fries.zombies.common.persistence.AccountSnapshot;
import net.fries.zombies.common.persistence.ResultReceipt;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** All connection, migration and SQL work runs on a bounded executor, including initialization. */
public final class JdbcAccountRepository implements AccountRepository, AutoCloseable {
    private final DatabaseSettings settings;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lastMicros = new AtomicLong();
    private final CompletableFuture<Void> initialized;
    private volatile HikariDataSource pool;
    private CompletableFuture<Void> closing;

    public JdbcAccountRepository(DatabaseSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        AtomicLong number = new AtomicLong();
        executor = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), task -> {
                    Thread thread = new Thread(task, "zombies-database-" + number.incrementAndGet());
                    thread.setContextClassLoader(JdbcAccountRepository.class.getClassLoader());
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        initialized = submit(() -> {
            var config = new HikariConfig();
            config.setJdbcUrl(settings.url()); config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setDriverClassName(settings.mode() == DatabaseSettings.Mode.POSTGRESQL ? "org.postgresql.Driver" : "org.h2.Driver");
            config.setMaximumPoolSize(settings.poolSize()); config.setMinimumIdle(0);
            config.setConnectionTimeout(settings.timeoutSeconds() * 1000L);
            config.setValidationTimeout(Math.min(5000, settings.timeoutSeconds() * 1000L));
            config.setInitializationFailTimeout(-1);
            config.setPoolName("zombies-storage");
            if (settings.mode() == DatabaseSettings.Mode.POSTGRESQL) {
                config.addDataSourceProperty("connectTimeout", settings.timeoutSeconds());
                config.addDataSourceProperty("socketTimeout", settings.timeoutSeconds());
                config.addDataSourceProperty("tcpKeepAlive", true);
            }
            pool = new HikariDataSource(config);
            try {
                var flyway = Flyway.configure(JdbcAccountRepository.class.getClassLoader()).dataSource(pool)
                        .locations("classpath:db/migration").cleanDisabled(true).load();
                if (settings.migrateOnStart()) flyway.migrate();
                else {
                    flyway.validate();
                    if (flyway.info().pending().length != 0) throw new IllegalStateException("Database migrations required");
                }
                ready.set(true);
            } catch (Exception e) { pool.close(); throw e; }
            return null;
        });
    }

    public CompletableFuture<Void> initialized() { return initialized; }

    @Override public CompletableFuture<AccountSnapshot> account(UUID playerId) {
        Objects.requireNonNull(playerId);
        return submitReady(() -> {
            try (var connection = pool.getConnection(); var statement = statement(connection,
                    "SELECT tokens, experience, matches_played, kills, highest_round FROM accounts WHERE player_id = ?")) {
                statement.setObject(1, playerId);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? new AccountSnapshot(playerId, rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getInt(5))
                            : new AccountSnapshot(playerId, 0, 0, 0, 0, 0);
                }
            }
        });
    }

    @Override public CompletableFuture<Boolean> healthy() {
        return submitReady(() -> {
            try (var connection = pool.getConnection()) { return connection.isValid(settings.timeoutSeconds()); }
        });
    }

    @Override public CompletableFuture<ResultReceipt> commit(MatchResult result) {
        Objects.requireNonNull(result);
        return submitReady(() -> {
            for (int attempt = 0; ; attempt++) {
                try { return transaction(result); }
                catch (SQLException e) {
                    if (attempt >= 2 || !("40001".equals(e.getSQLState()) || "40P01".equals(e.getSQLState()))) throw e;
                }
            }
        });
    }

    private ResultReceipt transaction(MatchResult result) throws SQLException {
        String fingerprint = result.fingerprint();
        try (var connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var insert = statement(connection, "INSERT INTO match_results (match_id, fingerprint, server_id, map_id, map_version, pack_version, outcome, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    var identity = result.identity();
                    insert.setObject(1, identity.matchId()); insert.setString(2, fingerprint); insert.setString(3, identity.serverId());
                    insert.setString(4, identity.mapId().value()); insert.setString(5, identity.mapVersion()); insert.setString(6, identity.packVersion());
                    insert.setString(7, result.outcome().name());
                    insert.setObject(8, OffsetDateTime.ofInstant(result.startedAt(), ZoneOffset.UTC));
                    insert.setObject(9, OffsetDateTime.ofInstant(result.endedAt(), ZoneOffset.UTC));
                    insert.executeUpdate();
                }
                // Canonical UUID order keeps overlapping multi-player reward transactions from reversing lock order.
                for (var player : result.players()) grant(connection, result, player);
                connection.commit();
                return new ResultReceipt(result.identity().matchId(), fingerprint, true);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    try (var existing = statement(connection, "SELECT fingerprint FROM match_results WHERE match_id = ?")) {
                        existing.setObject(1, result.identity().matchId());
                        try (var rows = existing.executeQuery()) {
                            if (rows.next()) {
                                if (!fingerprint.equals(rows.getString(1))) throw new IllegalArgumentException("Match result conflicts with an already committed result");
                                return new ResultReceipt(result.identity().matchId(), fingerprint, false);
                            }
                        }
                    }
                }
                throw e;
            }
        }
    }

    private void grant(Connection connection, MatchResult result, PlayerResult player) throws SQLException {
        try (var create = statement(connection, "INSERT INTO accounts (player_id) VALUES (?) ON CONFLICT DO NOTHING")) {
            create.setObject(1, player.playerId()); create.executeUpdate();
        }
        try (var update = statement(connection, "UPDATE accounts SET tokens = tokens + ?, experience = experience + ?, matches_played = matches_played + 1, kills = kills + ?, highest_round = GREATEST(highest_round, ?) WHERE player_id = ?")) {
            update.setLong(1, player.tokens()); update.setLong(2, player.experience()); update.setInt(3, player.kills());
            update.setInt(4, player.rounds()); update.setObject(5, player.playerId());
            if (update.executeUpdate() != 1) throw new SQLException("Account update failed");
        }
        try (var details = statement(connection, "INSERT INTO match_players (match_id, player_id, rounds, kills, headshots, revives, tokens, experience) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            details.setObject(1, result.identity().matchId()); details.setObject(2, player.playerId()); details.setInt(3, player.rounds());
            details.setInt(4, player.kills()); details.setInt(5, player.headshots()); details.setInt(6, player.revives());
            details.setLong(7, player.tokens()); details.setLong(8, player.experience()); details.executeUpdate();
        }
        for (String currency : new String[]{"TOKENS", "EXPERIENCE"}) {
            try (var ledger = statement(connection, "INSERT INTO reward_ledger (match_id, player_id, currency, amount) VALUES (?, ?, ?, ?)")) {
                ledger.setObject(1, result.identity().matchId()); ledger.setObject(2, player.playerId()); ledger.setString(3, currency);
                ledger.setLong(4, currency.equals("TOKENS") ? player.tokens() : player.experience()); ledger.executeUpdate();
            }
        }
    }

    private PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql); statement.setQueryTimeout(settings.timeoutSeconds()); return statement;
    }

    private <T> CompletableFuture<T> submitReady(SqlTask<T> task) {
        if (!ready.get()) return CompletableFuture.failedFuture(new IllegalStateException("Database is unavailable or still initializing"));
        return submit(task);
    }
    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        var future = new CompletableFuture<T>();
        try {
            executor.execute(() -> {
                long started = System.nanoTime();
                try { future.complete(task.run()); }
                catch (Exception e) { failures.incrementAndGet(); future.completeExceptionally(e); }
                finally { lastMicros.set((System.nanoTime() - started) / 1000); }
            });
        } catch (RejectedExecutionException e) { failures.incrementAndGet(); future.completeExceptionally(e); }
        return future;
    }
    public DatabaseMetrics metrics() {
        return new DatabaseMetrics(ready.get(), executor.getQueue().size(), executor.getActiveCount(),
                executor.getCompletedTaskCount(), failures.get(), lastMicros.get());
    }

    /** Initiates a bounded drain without blocking the Paper thread. Accepted work finishes before the pool closes. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing;
        ready.set(false); executor.shutdown();
        closing = new CompletableFuture<>();
        Thread closer = new Thread(() -> {
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    // Do not discard queued work or claim it committed. Surface a failed shutdown to the owner.
                    throw new IllegalStateException("Database drain exceeded 30 seconds; queued operations may be unfinished");
                }
                if (pool != null) pool.close();
                closing.complete(null);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); closing.completeExceptionally(e); }
            catch (Exception e) { closing.completeExceptionally(e); }
            finally { ready.set(false); if (pool != null) pool.close(); }
        }, "zombies-database-close");
        closer.setDaemon(false); closer.start();
        return closing;
    }
    @Override public void close() { closeAsync().join(); }
    @FunctionalInterface private interface SqlTask<T> { T run() throws Exception; }
}
