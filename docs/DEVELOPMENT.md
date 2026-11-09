# Development and deployment

## Local standalone

Use Java 21 for compilation and runtime. Set `JAVA_HOME` to a JDK 21 installation, then run `./gradlew clean build`. Gradle toolchains enforce release 21 even if the wrapper runs under another compatible JVM. All Gradle commands use the checked-in wrapper. Windows equivalents use `./gradlew.bat`.

`./gradlew :zombies-game:runServer` downloads and starts Paper 1.21.11 build 132 with the shaded plugin. Review the EULA and set `eula=true` in `run/eula.txt`. Run again, then use `zombies status`, `zombies validate` and `zombies health` in the console. Stop with `stop`.

The developer server needs no external service. H2 stores durable local accounts under `plugins/FriesNetZombies/data/`. Never open the same H2 file in two processes or deploy this mode across multiple instances. Back up its data only with the server stopped.

For a repeatable bootstrap test, install Python 3.10+ and run:

```sh
python tools/paper_smoke.py --java /path/to/jdk21/bin/java --accept-eula
```

The harness verifies the official Paper SHA-256, creates an isolated localhost server on an ephemeral port, verifies plugin readiness and console commands, and shuts down cleanly. It retains generated logs under `run/`. Network access is required to download Paper/Mojang libraries. This checks bootstrap and command wiring; it does not test client gameplay or rendering.

## Configuration

Examples are the actual defaults in `zombies-game/src/main/resources/`. Every required setting must be present and correctly typed. `schema-version` must equal 1; unknown settings and duplicate keys fail validation. Error messages identify the relevant file/setting without echoing secrets. YAML aliases are disabled.

`config.yml` contains stable `server-id`, named log level and default match rules (capacity, countdown, reservation TTL and private pause eligibility). Match capacity is 1–64. Countdowns are 0–300 seconds and reservations 1–3600 seconds. Default rules support 1–4 players. These are domain defaults for future match creation, not an invitation to join playable matches now.

`database.yml` controls `STANDALONE` or `POSTGRESQL`, migration policy, pool size (1–32; at least 2 when migrations run at startup), workers (at most pool size), bounded queue (1–10000), and SQL/connection timeout (1–60 seconds). Production credentials resolve from the names configured by `url-env`, `user-env` and `password-env`. JDBC URLs must not embed passwords or usernames. Use `sslmode=verify-full` and trusted certificates for remote PostgreSQL connections.

`reload` constructs and validates a new snapshot before applying it. Log level and future match rules can reload. Changes to server ID, database mode, credentials, pool or migration policy require a controlled restart. A rejected reload leaves the current snapshot intact. Future sessions take new rules; existing sessions keep their original rules.

Content-specific `maps/`, `weapons/`, `mobs/`, `perks/`, `waves/`, `lang/`, `quests/`, `cases/` and `progression/` loading will be introduced with those features. The new plugin does not read the legacy monolithic content configuration.

## PostgreSQL development and integration tests

Copy `.env.example` to `.env`, replace its local password, and start:

```sh
docker compose up -d postgres
```

Redis is optional infrastructure for future development: `docker compose --profile network up -d`. The current plugin never connects to it. Compose ports bind only to localhost. Do not expose this local development deployment publicly.

To run the real database test contract, set these environment variables for a **disposable** database:

```text
ZOMBIES_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/zombies
ZOMBIES_TEST_DATABASE_USER=zombies_owner
ZOMBIES_TEST_DATABASE_PASSWORD=<your local .env value>
```

Then `./gradlew :zombies-persistence:integrationTest`. This task fails on missing credentials instead of reporting skipped tests as success. It runs migrations, concurrency, rollback, replay, restart, shutdown and lifecycle integration checks against PostgreSQL. It creates test records and must not target a live player database. GitHub Actions provisions a separate PostgreSQL service for this task. Docker is not needed for ordinary unit/H2 tests.

## Production database provisioning

Create a dedicated schema owner and a separate runtime login through your database administration/secret-management process. Set the schema owner's environment and run the migration entry point from the built, shaded plugin JAR:

```sh
java -cp zombies-game/build/libs/zombies-game-0.1.0.jar net.fries.zombies.persistence.MigrationMain
```

The entry point reads `ZOMBIES_DATABASE_URL`, `ZOMBIES_DATABASE_USER`, `ZOMBIES_DATABASE_PASSWORD`. It has no Paper runtime dependency. Run [database-runtime-grants.sql](../deploy/database-runtime-grants.sql) as the owner after replacing its runtime role name. Give only SELECT/INSERT on immutable history, limited account UPDATE, and Flyway history read access. Future migrations may need additional reviewed grants. The app never needs Redis/Pterodactyl/administrative database credentials.

Configure the Paper process with the **runtime** identity's environment variables. Set `database.yml` to `mode: POSTGRESQL`, `migrate-on-start: false`. Startup validates migration checksums and refuses readiness while migrations are pending. Run migrations as a separate deployment job while instances are stopped or drained. Back up PostgreSQL and test restoration before production use. Never modify an already-applied migration; append a new version.

The runtime role can create/update player economic data for the instance it serves. It is not an anti-cheat trust boundary. Network reservation authorization and authoritative reward policies must be completed before public deployment.

## Paper and Pterodactyl

Install Java 21, Paper 1.21.11 build 132, and only `zombies-game-0.1.0.jar`. Use a stable unique `server-id` for each allocation. Pterodactyl can run these pre-provisioned servers using a Java 21 Paper egg and environment secrets. Keep world/config backups and stop the instance before replacing plugin binaries. Do not place panel keys in plugin configuration or Minecraft messages.

Phase 1 has no privileged instance controller, automatic world restoration, Velocity plugin, hub routing or admission security. Do not expose it as a public Zombies game service. Standalone diagnostic readiness means its foundation/storage are initialized, not that a map is loaded and a game instance is assignable. These deployment integrations are later milestones.

## Builds and releases

`./gradlew clean build` creates the deployable plugin plus library/reference JARs and HTML test reports. `verifyPluginJar` asserts that the deployable artifact contains its descriptor, entry point, migrations, JDBC drivers and Flyway service discovery, and excludes Paper and historical classes. CI uploads only the shaded plugin as a deployable artifact. Generated JAR/ZIP archives, runtime files, credentials and logs stay untracked.

Gradle locks transitive versions; `gradle/verification-metadata.xml` pins dependency artifact checksums. Paper's 1.21.x API uses snapshot coordinates ([upstream format](https://docs.papermc.io/paper/dev/project-setup/)); checksums pin the actual resolved artifacts and reject unexpected replacements. If upstream removes the pinned snapshot, mirror the verified artifacts or deliberately review a dependency update. Do not disable verification to make an unexpected download pass. Wrapper upgrades must include the official distribution checksum. Archive ordering/timestamps are deterministic.

For a reviewed dependency change, run `./gradlew clean build --write-locks --write-verification-metadata sha256`, inspect changed versions/checksums, then run a normal build with verification enabled. Record the actual integration results separately from unit results. A new release must also pass the Paper smoke harness and the PostgreSQL CI job.

## Troubleshooting

- `configuration_rejected`: check the indicated setting, scalar type, missing variable or schema version. Original jZombies YAML cannot be loaded as the new schema.
- `storage_startup_failed`: check environment, connectivity, TLS and schema status. No balances are invented. Correct the underlying issue and restart the plugin/server.
- `storage unavailable or initializing`: inspect server logs and `zombies health`. A temporary query failure is reported as a failed future. Restart is required after initialization failure.
- Queue full: operations fail explicitly. Inspect latency and DB load before increasing workers/pool limits; never use caller-thread execution as a workaround.
- `storage_drain_failed`: accepted operations exceeded the 30-second shutdown budget. Their futures did not provide a successful receipt. Investigate and replay the immutable result from its owning system; never release an unacknowledged session.
- Dependency verification failure: check repository integrity and reviewed lock/checksum changes. Do not blindly regenerate checksums.
- Java class-version error: confirm `JAVA_HOME`, server startup Java and Gradle toolchain point to JDK 21.
- Paper smoke download failure: check HTTPS/network access and the pinned upstream download; the harness verifies its checksum.

## Manual acceptance beyond this milestone

Connect a Java 1.21.11 client; verify command permissions and suggestions, profile feedback, and failed reload feedback. No physical player inventory or world state should change. Gameplay, rendering, map editing, packet behavior, reconnect recovery, load tests and network transfers require their later implementations and must not be reported as tested here.
