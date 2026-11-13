# FriesNet Zombies

Java 21 / Paper **1.21.11, build 132**. Phase 1 of the production rebuild establishes a tested foundation. It is **not yet a playable Zombies release**. Phase 2 adds world management, waves and combat.

The deployable plugin boots without Redis, Velocity, PostgreSQL or MythicMobs. It uses a durable local H2 database in standalone development mode. Production uses PostgreSQL with the same repository contract and Flyway migrations.

| Module | Responsibility |
| --- | --- |
| `zombies-common` | Validated immutable configuration, identifiers, match domain, typed events, persistence ports |
| `zombies-persistence` | Bounded asynchronous JDBC, HikariCP, Flyway, account snapshots, atomic match result processing |
| `zombies-game` | Paper composition root, lifecycle-safe callbacks, diagnostics and administrative commands |
| `zombies-legacy` | Isolated historical implementation, compiled for reference and excluded from deployment |

## Build and run

Install JDK 21 and set `JAVA_HOME`. The checked-in Gradle 8.14.3 wrapper includes distribution and dependency checksums. On Windows use `gradlew.bat` in place of `./gradlew`.

```sh
./gradlew clean build
./gradlew :zombies-game:runServer
```

Review and accept the Minecraft EULA in the generated `run/eula.txt` before restarting the development server. The run task uses Java 21 and pinned Paper build 132. Install only `zombies-game/build/libs/zombies-game-0.1.0.jar` on other servers. The `thin` and legacy JARs are not deployable plugins.

The plugin creates `plugins/FriesNetZombies/config.yml`, `database.yml`, and its local `data/` directory. No map, player inventory, or world blocks are modified in this milestone. Existing jZombies configurations and `players.yml` are not automatically migrated; keep backups and see [the audit](docs/REPOSITORY_AUDIT.md).

## Available commands

`/zombies` and `/jz` accept:

| Command | Permission | Behavior |
| --- | --- | --- |
| `status` | `zombies.use` (default true) | Reports foundation/storage readiness |
| `profile` | `zombies.use` | Asynchronously reads the player's persisted account summary |
| `health` | `zombies.admin` (op) | Probes the database; reports queue, worker, failure and latency metrics |
| `validate` | `zombies.admin` | Validates disk configuration without applying it |
| `reload` | `zombies.admin` | Atomically applies compatible settings; identity/storage changes require restart |

## Verification

```sh
./gradlew clean build
# Real PostgreSQL suite; provide disposable database credentials first:
./gradlew :zombies-persistence:integrationTest
# Actual Paper bootstrap/commands/shutdown, after reviewing its EULA:
python tools/paper_smoke.py --java /path/to/jdk21/bin/java --accept-eula
```

Tests cover domain transitions, frozen player scaling, configuration rejection, durable rewards, concurrency, rollback, restart and graceful shutdown. `check` also inspects the packaged plugin. PostgreSQL tests intentionally fail if their environment is absent; H2 tests are not presented as PostgreSQL verification. GitHub Actions builds the plugin, runs tests, provisions PostgreSQL for integration tests and uploads artifacts.

Read [architecture and extension contracts](docs/ARCHITECTURE.md), [configuration/deployment](docs/DEVELOPMENT.md), [repository audit and roadmap](docs/REPOSITORY_AUDIT.md), and [Phase 1 verification](docs/PHASE_1.md).
