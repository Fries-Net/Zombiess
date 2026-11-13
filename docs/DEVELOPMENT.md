# Development and deployment

## Local standalone

Use Java 21 for compilation and runtime. Set `JAVA_HOME` to a JDK 21 installation, then run `./gradlew clean build`. Gradle toolchains enforce release 21 even if the wrapper runs under another compatible JVM. All Gradle commands use the checked-in wrapper. Windows equivalents use `./gradlew.bat`.

`./gradlew :zombies-game:runServer` downloads and starts Paper 1.21.11 build 132 with the shaded plugin. Review the EULA and set `eula=true` in `run/eula.txt`. Run again, then use `zombies status`, `zombies validate` and `zombies health` in the console. Stop with `stop`.

The developer server needs no external service. H2 stores durable local accounts under `plugins/FriesNetZombies/data/`. Never open the same H2 file in two processes or deploy this mode across multiple instances. Back up its data only with the server stopped.

For a repeatable bootstrap test, install Python 3.10+ and run:

```sh
python tools/paper_smoke.py --java /path/to/jdk21/bin/java --accept-eula
```

The harness verifies the official Paper SHA-256, creates an isolated localhost server on an ephemeral port, verifies plugin readiness and console commands, and shuts down cleanly. It retains generated logs under `run/`. Network access is required to download Paper/Mojang libraries. For real multiplayer, use Node 22+, `npm ci --prefix tools/paper-client --ignore-scripts --no-audit --no-fund`, then `python tools/paper_playtest.py --java /path/to/java21 --accept-eula`. This separate harness connects two protocol clients, exercises a shortened playable match, reuses the instance and forcibly kills/restarts Paper to verify recovery. It installs a separate console-only assertion JAR only in the isolated test server. Never deploy that JAR on a game server.

## Configuration

Examples are the actual defaults in `zombies-game/src/main/resources/`. Every required setting must be present and correctly typed. `schema-version` must equal 1; unknown settings and duplicate keys fail validation. Error messages identify the relevant file/setting without echoing secrets. YAML aliases are disabled.

`config.yml` contains stable `server-id`, named log level and default match rules (capacity, countdown, reservation TTL and private pause eligibility). Match capacity is 1–64. Countdowns are 0–300 seconds and reservations 1–3600 seconds. Default rules support 1–4 players. Use `/zombies join training` to gather for five seconds, then reserve and begin the configured countdown. Population loss below minimum during countdown cancels/releases the lobby; join again for a new reservation.

`database.yml` controls `STANDALONE` or `POSTGRESQL`, migration policy, pool size (1–32; at least 2 when migrations run at startup), workers (at most pool size), bounded queue (1–10000), and SQL/connection timeout (1–60 seconds). Production credentials resolve from the names configured by `url-env`, `user-env` and `password-env`. JDBC URLs must not embed passwords or usernames. Use `sslmode=verify-full` and trusted certificates for remote PostgreSQL connections.

`reload` constructs and validates a new snapshot before applying it. Log level and future match rules can reload. Changes to server ID, database mode, credentials, pool or migration policy require a controlled restart. A rejected reload leaves the current snapshot intact. Future sessions take new rules; existing sessions keep their original rules.

`gameplay.yml` contains namespaced weapon definitions, the starting weapon, gradual wave formulas/caps/timeout, points/rewards and life/channel timings. `maps/*.yml` contain bounded versioned block templates, safe player/zombie spawns, purchase gates, barricades and weapon stations. Both formats reject missing/unknown/duplicate keys and invalid references. The defaults are working examples; map IDs must be unique. These content snapshots load at startup and are not changed by `/zombies reload`. Do not remove pending journal/player files when updating content. The new runtime does not load the legacy monolithic configuration.

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

Phase 2 runs one local match at a time with owned disposable world restoration. Readiness includes storage, content parsing, abandoned-world cleanup and pending-result replay. This is a standalone game adapter; a privileged instance controller, signed network admission, Velocity and hub routing remain later milestones. Controlled restarts are required for binary/content changes; plugin hot-reload is not supported.

## Builds and releases

`./gradlew clean build` creates the deployable plugin plus library/reference JARs and HTML test reports. `verifyPluginJar` asserts that the deployable artifact contains its descriptor, entry point, migrations, JDBC drivers and Flyway service discovery, and excludes Paper and historical classes. CI uploads only the shaded plugin as a deployable artifact. Generated JAR/ZIP archives, runtime files, credentials and logs stay untracked.

Gradle locks transitive versions; `gradle/verification-metadata.xml` pins dependency artifact checksums. Paper's 1.21.x API uses snapshot coordinates ([upstream format](https://docs.papermc.io/paper/dev/project-setup/)); checksums pin the actual resolved artifacts and reject unexpected replacements. If upstream removes the pinned snapshot, mirror the verified artifacts or deliberately review a dependency update. Do not disable verification to make an unexpected download pass. Wrapper upgrades must include the official distribution checksum. Archive ordering/timestamps are deterministic.

For a reviewed dependency change, run `./gradlew clean build --write-locks --write-verification-metadata sha256`, inspect changed versions/checksums, then run a normal build with verification enabled. Record integration results separately from unit results. A release must pass the Paper multiplayer harness and PostgreSQL CI job. The multiplayer client has a committed npm lockfile and is never bundled in the Minecraft plugin.

## Troubleshooting

- `configuration_rejected`: check the indicated setting, scalar type, missing variable or schema version. Original jZombies YAML cannot be loaded as the new schema.
- `storage_startup_failed`: check environment, connectivity, TLS and schema status. No balances are invented. Correct the underlying issue and restart the plugin/server.
- `storage unavailable or initializing`: inspect server logs and `zombies health`. A temporary query failure is reported as a failed future. Restart is required after initialization failure.
- Queue full: operations fail explicitly. Inspect latency and DB load before increasing workers/pool limits; never use caller-thread execution as a workaround.
- `storage_drain_failed`: accepted operations exceeded the 30-second shutdown budget. Their futures did not provide a successful receipt. Investigate and replay the immutable result from its owning system; never release an unacknowledged session.
- Dependency verification failure: check repository integrity and reviewed lock/checksum changes. Do not blindly regenerate checksums.
- Java class-version error: confirm `JAVA_HOME`, server startup Java and Gradle toolchain point to JDK 21.
- Paper smoke download failure: check HTTPS/network access and the pinned upstream download; the harness verifies its checksum.

## Playable acceptance and recovery

Connect a Java 1.21.11 client and follow [the play instructions](../README.md). Joining temporarily installs a match loadout only after the original inventory/status backup is forced. Leaving or finishing returns the original state. A disposable `fnz_<uuid>` world exists only for that match; hub/operator worlds are not edited. A clean result receipt and verified world removal gate the next match.

`match_result_pending` retains the final journal and retries every five seconds. `world_cleanup_failed` or `cleanup_player_failed` leaves the instance unavailable and retries cleanup. Fix storage/permissions/third-party unload interference; do not delete ownership markers or pending results to bypass these gates. `player_restore_failed` keeps the backup; `/zombies leave` can retry a player's restoration after the underlying problem is corrected. Invalid/corrupt startup journals block readiness and need investigation and restoration from backups.

Active checkpoints are written every five seconds of match ticks. After a JVM crash the match is recorded as abandoned at the last forced checkpoint, rewards replay idempotently, and original player state is restored on connection. No live entity simulation is resumed. See [Phase 2 verification and filesystem limits](PHASE_2.md). Graphical client polish, full 30-round balance, production load and network transfers require separate acceptance.
