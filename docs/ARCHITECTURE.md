# Architecture

## Current boundaries

`zombies-game -> zombies-persistence -> zombies-common`, with `zombies-game` also depending on `zombies-common`. Common has no Paper, JDBC, Redis or network dependency; its only library is the safe YAML parser. Persistence has no Minecraft API dependency. Legacy has no dependency edge into the new runtime. Gradle compiles it to preserve reviewable source, but strips its plugin descriptor and never bundles it into the new plugin.

Four modules are sufficient for this milestone. Hub, proxy, network and pack modules will be introduced when their implementations arrive. Empty placeholder modules would obscure the dependency boundaries without delivering functionality.

`ZombiesPlugin` wires explicit constructors. It loads configuration, starts storage asynchronously, registers commands and receives startup readiness through a server-thread callback. No singleton service locator is used. Shutdown stops callbacks and scheduled tasks immediately, then drains accepted database operations on a separate closer thread. Connection acquisition, migrations, queries and transactions never run on the server thread. File-based configuration reads are bounded to 64 KiB per file and occur only on startup/admin requests.

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

The aggregate emits immutable `MatchEvent` values; its owner drains them after mutations and publishes them to interested adapters. World/equipment preparation happens between `STARTING` and `begin()`. Adapters must roll back preparation if capacity changes. Pausing is allowed only for private sessions whose immutable rules permit it. Phase 2 must use this state to stop match clocks, combat, spawning and AI; the domain does not itself manipulate Minecraft entities.

`finish()` validates the exact identity, start timestamp and frozen roster. `MatchCompletionService` asks the repository to commit the result and acknowledges the receipt. Until that receipt matches the result fingerprint, `cleanup()` fails. `ready(true)` is an explicit restoration assertion supplied by the future world adapter. No world restoration or automatic matchmaking is claimed in Phase 1.

## Persistence contract

`AccountRepository` exposes futures for account reads, result commits and health. DTOs are immutable and use UUID identity. Temporary match points never enter account balances. The schema currently persists tokens, experience, matches played, kills and highest round, plus per-match rounds/headshots/revives and full reward history. Additional progression fields belong to later migrations.

A result transaction inserts its UUID and canonical SHA-256 fingerprint, creates/updates accounts, inserts per-player statistics and appends two currency ledger entries per player. Every step commits or rolls back together. A duplicate UUID causes rollback followed by verification against the committed fingerprint. Equal results return an existing receipt. Different results fail explicitly. Canonical roster ordering reduces deadlocks; serialization/deadlock errors receive at most three attempts. Bigint overflow or constraint failure rolls back **all** players and the result marker.

SQL uses bound parameters and query timeouts. Hikari limits connections, the worker executor limits concurrency and a bounded queue limits pending operations. Rejection returns a failed future; it never runs SQL on the caller. Metrics expose readiness, active work, queued work, completions, failures and last-operation duration. Database failure never switches production to H2 or grants optimistic rewards.

Standalone mode uses a local durable H2 file with PostgreSQL compatibility mode. It is a development adapter, not a distributed database or proof of PostgreSQL compatibility. The same repository contract is run separately against PostgreSQL. Use a separate schema owner to run migrations in production and the supplied least-privilege grants for runtime access. A compromised runtime can still falsify the matches it is authorized to submit; signed network reservations and authoritative reward policy are later work.

The completion service preserves pending results in memory for retry but does not implement a durable submission spool. Before enabling actual matches, Phase 2 must journal completed-but-unsubmitted results and replay them after process crashes. A database-committed result already survives crashes and is safe to replay; an unsubmitted in-memory result does not. There is no active gameplay in this milestone that could generate such an unsubmitted result.

## Configuration and content identity

`ConfigurationLoader` rejects duplicate/unknown keys, invalid scalar types, aliases, oversized YAML, unsupported schemas, invalid capacities and missing required environment variables. It builds immutable snapshots. Compatible reloads swap only after complete validation; existing sessions retain their original `MatchRules`. Server identity and database configuration require restart. Errors do not echo YAML values or credentials.

`NamespacedId` is the logical content identity, e.g. `friesnet:weapon/ak47`. Numeric model data is not a domain identifier. `MatchIdentity` freezes server, map version and pack version. These types prepare future asset and network work without claiming Java/Bedrock render format compatibility.

## Extension points

- Implement `AccountRepository` only with equivalent atomic/idempotent semantics; use the repository contract suite.
- Attach Paper lifecycle/world adapters to explicit `MatchSession` operations and drain typed events.
- Add immutable rules through configuration schema changes and tests; do not mutate active match rules.
- Add schema changes as new Flyway migrations. Never edit a migration already deployed.
- Add gameplay registries, asset manifests, Velocity and Redis protocols in their owning milestones. No distributed dependency is needed for standalone play.
