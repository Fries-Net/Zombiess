# Architecture

## Current boundaries

`zombies-game -> zombies-persistence -> zombies-common`, with `zombies-game` also depending on `zombies-common`. Common has no Paper, JDBC, Redis or network dependency; its only library is the safe YAML parser. Persistence has no Minecraft API dependency. Legacy has no dependency edge into the new runtime. Gradle compiles it to preserve reviewable source, but strips its plugin descriptor and never bundles it into the new plugin.

Four modules are sufficient for this milestone. Hub, proxy, network and pack modules will be introduced when their implementations arrive. Empty placeholder modules would obscure the dependency boundaries without delivering functionality.

`ZombiesPlugin` wires explicit constructors. It loads configuration, starts storage/content/recovery asynchronously, registers commands and receives startup readiness through a server-thread callback. No singleton service locator is used. Shutdown stops callbacks and scheduled tasks, preserves the latest result and returns online players, then drains accepted database operations on a separate closer thread. Connection acquisition, migrations, queries, transactions, journal writes and world-directory deletion never run on the server thread. Core configuration reads are bounded to 64 KiB; gameplay/map documents to 256 KiB.

## Domain lifecycle

```text
IDLE -> RESERVED -> WAITING -> COUNTDOWN -> STARTING -> IN_PROGRESS
                         ^        |                       |   ^
                         +--------+                       v   |
                                                        PAUSED
IN_PROGRESS / PAUSED -> ENDING -> CLEANUP -> READY -> DRAINING
```

Loss of minimum population cancels the countdown. Reservation expiry fails the session. Operational states can enter `FAILED`; failures before play may clean up, while failures after play still require a durable result. Failed restoration stays unavailable until verified cleanup succeeds. `IDLE` may also drain. A ready instance creates a **new** `MatchSession` and UUID for its next assignment; it never reuses an old match aggregate.

`MatchSession` is a synchronized, API-independent aggregate with explicit guarded operations. It does not expose an unrestricted state setter. Admission requires membership in the immutable reservation. Countdown and reservation clocks use an injected `Clock`. `begin()` freezes the initial roster and scaling count. Leaving afterward changes connected membership without changing difficulty scaling or removing a player from the result roster. Phase 1 rejects mid-game joining; reconnect and spectator policies belong to the playable adapter.

The aggregate emits immutable `MatchEvent` values; its owner drains them after mutations. The Paper coordinator freezes a gathering lobby into reservations, advances the configured countdown, materializes/verifies a fresh world, and forces original-player backups before `begin()`. It then forces the initial active checkpoint before installing equipment or allowing combat. Capacity changes before begin roll preparation back. Private pause stops match clocks, firearms, life/repair/revive channels, spawning and native mob behavior. The domain itself manipulates no Minecraft entities.

`finish()` validates the exact identity, start timestamp and frozen roster. The Phase 2 coordinator forces the immutable final result through `ResultJournal`, commits it through the repository, and acknowledges the matching receipt. Until then, `cleanup()` fails. The legacy-free Paper adapter supplies `ready(true)` only after players have returned and the owned world is unloaded and absent on disk. A failed cleanup retries without re-committing rewards.

## Persistence contract

`AccountRepository` exposes futures for account reads, result commits and health. DTOs are immutable and use UUID identity. Temporary match points never enter account balances. The schema currently persists tokens, experience, matches played, kills and highest round, plus per-match rounds/headshots/revives and full reward history. Additional progression fields belong to later migrations.

A result transaction inserts its UUID and canonical SHA-256 fingerprint, creates/updates accounts, inserts per-player statistics and appends two currency ledger entries per player. Every step commits or rolls back together. A duplicate UUID causes rollback followed by verification against the committed fingerprint. Equal results return an existing receipt. Different results fail explicitly. Canonical roster ordering reduces deadlocks; serialization/deadlock errors receive at most three attempts. Bigint overflow or constraint failure rolls back **all** players and the result marker.

SQL uses bound parameters and query timeouts. Hikari limits connections, the worker executor limits concurrency and a bounded queue limits pending operations. Rejection returns a failed future; it never runs SQL on the caller. Metrics expose readiness, active work, queued work, completions, failures and last-operation duration. Database failure never switches production to H2 or grants optimistic rewards.

Standalone mode uses a local durable H2 file with PostgreSQL compatibility mode. It is a development adapter, not a distributed database or proof of PostgreSQL compatibility. The same repository contract is run separately against PostgreSQL. Use a separate schema owner to run migrations in production and the supplied least-privilege grants for runtime access. A compromised runtime can still falsify the matches it is authorized to submit; signed network reservations and authoritative reward policy are later work.

`ResultJournal` now supplies the durable submission spool required before playable matches. A serial bounded disk worker writes checksummed, versioned records, forces contents, and replaces files atomically. A final result cannot regress to an active checkpoint. SQL failure leaves the record intact and blocks reuse. Startup replays finals and abandons active checkpoints using the same UUID/fingerprint contract. Active progress is checkpointed every five seconds of match time; interrupted gameplay is not resumed. See [recovery limits](PHASE_2.md).

## Configuration and content identity

`ConfigurationLoader` rejects duplicate/unknown keys, invalid scalar types, aliases, oversized YAML, unsupported schemas, invalid capacities and missing required environment variables. It builds immutable snapshots. Compatible reloads swap only after complete validation; existing sessions retain their original `MatchRules`. Server identity and database configuration require restart. Errors do not echo YAML values or credentials.

`NamespacedId` is the logical content identity, e.g. `friesnet:weapon/ak47`. Numeric model data is not a domain identifier. `MatchIdentity` freezes server, map version and pack version. A map's declared version includes a digest of the exact bytes parsed for geometry, spawns and interactions. `ContentCatalog` retains immutable startup snapshots; changes require a controlled restart. Edition-specific packs remain future work.

## Paper gameplay ownership

`MatchCoordinator` owns lifecycle orchestration, with a new `MatchRun` for each reservation. `CombatService`, `WaveService`, `LifeService`, `InteractionService` and `MatchHud` own separate behavior. `WeaponState` and `Survivor` keep ammunition/life/points/statistics in common, independent of Paper. Items contain an owned instance UUID; client item lore never supplies damage, cooldowns or ammunition. Hitscan checks block occlusion and only tracked zombies can be damaged by authorized firearm calls.

`MapWorlds` builds bounded trusted block templates into unique void worlds. It validates safe spawn floors/headroom and gate/barricade geometry before admission. Cleanup and startup recovery inspect the ownership marker, exact UUID-derived directory name, normalized parent and symbolic links before deletion. Other server worlds are never reset. Blocks, text displays and mobs belong to the disposable lease; no mutable world template is reused.

`PlayerBackups` serializes original inventory/status before play; `PlayerRestoration` owns action locks, asynchronous storage, disconnect restoration, idempotent returns and startup recovery. Paper item serialization and player/world API calls run on the server thread. Snapshots are forced before admission, and original player data is saved before deleting its return snapshot. Disconnects leave the immutable result roster and scaling intact; reconnecting restores the player and requires a future match reservation.

## Extension points

- Implement `AccountRepository` only with equivalent atomic/idempotent semantics; use the repository contract suite.
- Attach Paper lifecycle/world adapters to explicit `MatchSession` operations and drain typed events.
- Add immutable rules through configuration schema changes and tests; do not mutate active match rules.
- Add schema changes as new Flyway migrations. Never edit a migration already deployed.
- Add gameplay registries, asset manifests, Velocity and Redis protocols in their owning milestones. No distributed dependency is needed for standalone play.
