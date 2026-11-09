# Phase 1 completion and verification

## Delivered

- Four Gradle projects with explicit dependencies: common domain, persistence, Paper runtime and isolated historical reference.
- Java 21 toolchains/release, Paper 1.21.11 API and development build 132, Gradle 8.14.3 wrapper, dependency locks/checksums, deterministic archive metadata and packaging checks.
- Immutable validated core/database configuration, environment-only PostgreSQL credentials, atomic compatible reload and controlled-restart rules.
- A guarded domain lifecycle with reservations, admission, minimum/capacity checks, countdown, starting, frozen player-count scaling, private pause/resume, completion, failure recovery, cleanup, readiness and draining.
- Immutable match identities/results/events and a completion service that prevents cleanup before the exact result has committed.
- Bounded asynchronous storage, Hikari pool, Flyway migrations, durable standalone H2 adapter, PostgreSQL adapter, account reads, append-only runtime reward ledger and idempotent atomic result processing.
- Paper startup/shutdown wiring, Adventure diagnostics, permission-checked commands, health/backpressure/latency metrics and explicit unavailability behavior.
- Compose examples, production runtime grants, migration entry point, architecture/audit/configuration/deployment documentation and GitHub Actions artifact publishing/integration jobs.

## Executed locally

The original clean Gradle build passed with no tests. The rebuilt Java 21 build passes with **41 tests**, zero failures and zero skips:

| Suite | Tests | Coverage |
| --- | ---: | --- |
| Configuration loader | 15 | strict typing, schema, duplicate/unknown keys, capacity, environment, redaction, reload immutability, bounds and migration pool minimum |
| Match session | 12 | guarded lifecycle, immutable reservations/snapshots, countdown cancellation/expiry, frozen scaling, pause policy, failure/retry, restoration and receipt gate |
| Namespaced identifiers | 1 | stable edition-neutral identity and invalid/traversal input |
| Standalone repository | 10 | durable migration/account/reward behavior, concurrent duplicate/different matches, conflicting replay, all-player rollback, restart and shutdown |
| Database failure/class loading | 2 | nonblocking unavailable startup, failed readiness, plugin-independent context loader |
| Backpressure | 1 | real row-lock saturation, bounded queue, immediate rejection, no caller-thread SQL and accepted-work completion |

`verifyPluginJar` passed. It checks entry point, descriptor expansion, migrations, drivers and Flyway discovery while excluding Paper/legacy classes. A normal build with dependency verification enabled passed after explicitly pinning Paper API snapshot `20260511.115010-91` (Gradle does not automatically record changing-module checksums).

The actual packaged JAR was booted on **Paper 1.21.11 build 132 / Java 21**, using the checksum-verified server binary. The smoke harness verified:

1. Plugin discovery and enablement.
2. Fresh standalone migration and readiness.
3. Console `zombies status`, `validate`, `reload` and `health` responses.
4. Clean Paper stop and asynchronous storage shutdown completion.

An initial real-server run caught JDBC driver discovery under Paper's context loader. The fix explicitly selects the driver and assigns the plugin class loader to database workers. A regression test and subsequent real-server smoke test pass. A single-connection migration configuration was also found to exhaust Flyway's connection needs; configuration now rejects it before startup.

The smoke server emitted the expected upstream warnings that 1.21.11 is behind the current Minecraft line and that Flyway's declared verified H2 version predates H2 2.4.240. This repository deliberately pins the requested 1.21.x platform. H2 2.4.240 passed the repository contract and real startup tests.

## Separate verification

The local Docker daemon is unavailable, so the real PostgreSQL suite was not run locally. `:zombies-persistence:integrationTest` runs the same ten repository contract tests against a supplied disposable PostgreSQL database. CI provisions that database and must pass before merge. CI status is reported with the pull request; the presence of a workflow is not evidence that its run succeeded.

Client permissions/tab completion/profile rendering, multiple plugin reload cycles, process-crash fault injection and production load testing were not performed. No Redis or Velocity integration is implemented or represented as tested in this milestone.

## Remaining work and stopping point

The new plugin is a usable diagnostic/persistence foundation, **not a playable match implementation**. World preparation/restoration, map loading, scheduled lifecycle orchestration, combat, spawning, player equipment, reconnect/spectator behavior and match-end screens are Phase 2 or later. Domain pause flags do not freeze world entities until those adapters exist.

Before real matches are enabled, add a durable pending-result journal for crashes before database submission; already committed results are safely replayable now. Existing `players.yml` is preserved outside the new data directory and has no automatic migration yet. Full progression, cases, quests, packs, hub/network services, store fulfilment and Pterodactyl control remain later milestones.

Phase 2 must begin with trusted map/world lifecycle integration and deliver a complete playable match. No Phase 2 gameplay implementation is included in this milestone.
