# Phase 2: playable standalone matches

## Delivered behavior

The runtime now drives the guarded Phase 1 lifecycle through reservation, countdown, preparation, play, result persistence, cleanup and readiness. A five-second gathering lobby precedes the immutable reservation. There is one match per instance. A player can join `training`, complete several rounds, purchase access/equipment/ammo, fight, revive or die, receive durable results and join a new match without restarting Paper.

| Requirement | Implementation |
| --- | --- |
| Paper lifecycle | `MatchCoordinator` orchestrates the domain; `MatchRun` holds session-scoped services. Events are drained into structured logs. Capacity loss before preparation cancels and releases the lobby. |
| Maps/versioning/restoration | Strict bounded YAML blueprints with stable namespaced identity, declared version and SHA-256-derived content version. Fresh uniquely named void worlds are materialized and verified before admission. Only ownership-marked worlds beneath the server world container may be deleted. |
| Dynamic waves | Gradual native zombie spawning, alive cap, health/damage/speed progression, unlocked spawn regions, frozen initial population, finite victory and bounded round timeout. Native targets are standing participants. |
| Firearms | Glock-18 and AK-47 registries in `gameplay.yml`; unique server-owned weapon instances; exact ammo conservation; reload/switch cancellation; tick cooldowns; block-occluded hitscan and headshot multipliers. Zombie armor is explicitly normalized so the configured damage is exact. |
| Purchases/repairs | Doors and iron-bar barriers remove configured block volumes once; stations sell weapons or reserve ammo. Repair channels require proximity/sneaking, stop when interrupted, and cap point rewards per barricade/round. |
| Life/defeat | Lethal damage downs, timed bleedout leads to spectator mode, continuous nearby sneaking revives, and dead teammates return on round clear. No standing survivor means defeat. Disconnects retain roster statistics and scaling. |
| Points/rewards | Temporary points fund purchases. Per-round tokens, kill/round experience and a victory bonus become immutable `PlayerResult` values. Leaving retains earned participation rewards and forfeits the victory bonus. |
| Presentation | Owned sidebar with hidden score numbers, ammo/reload and context action bars, purchase labels, sounds, hit feedback, countdown/round/down/revive/end titles and saved-result messages. |
| Durable results | Forced atomic active checkpoints and final submissions precede database submission. Startup replays completed submissions idempotently and recovers interrupted matches as abandoned. Database failure retains the journal and blocks reuse. |
| Cleanup/reuse | Original inventory and status are backed up before equipment changes, restored and saved before deleting the backup. Cleanup removes mobs/displays, returns players, unloads the world, deletes its files, verifies absence and marks ready. Failed cleanup retries without granting rewards again. |

## Architecture and scope

Common remains independent of Paper and JDBC. Persistence has no server API dependency. Paper composition uses explicit constructors, with independent `CombatService`, `WaveService`, `LifeService`, `InteractionService`, `MatchHud`, `MapWorlds`, `ContentCatalog`, `PlayerBackups` and `PlayerRestoration`. The historical `GameManager` is neither referenced nor shipped.

Gameplay definitions and map templates are immutable startup snapshots. Every match pins the map content digest and its own rules. Editing files does not mutate a running match. Core reload changes future rules only. The bounded block-blueprint format is the trusted Phase 2 map adapter; interactive authoring, draft/publish workflows, world-folder/schematic import, perks, boxes, packs, hub/network and full progression remain outside this milestone. Phase 3 has not started.

The server rejects mid-match joining/rejoining. A disconnected starter receives their original equipment on return and may join a later match. This keeps the frozen roster and reward identity unambiguous without adding a reconnect reservation protocol.

## Executed verification

`clean build --no-build-cache --warning-mode all` passes on Java 21 with **53 unit/contract tests**, zero failures and zero skips. Existing 41 Phase 1 tests remain. Seven new common tests cover both firearms, ammunition conservation, cooldowns, interrupted reloads, scaling, life/rewards and content validation/digests. Five journal tests cover real durable H2 recovery, database-unavailable retention, post-commit replay, corruption, final/checkpoint conflicts and interrupted temporary writes. Packaged plugin verification passes; the test helper is excluded from the deployable JAR. The only removal warnings are in the unchanged legacy reference module.

The actual shaded plugin has been run on **Paper 1.21.11 build 132 / Java 21**. The bootstrap harness verifies migrations/readiness, status, validation, reload, health and clean storage shutdown. The multiplayer harness executes **36 assertion stages** with two real 1.21.11 protocol clients, including:

1. Admission to the versioned map with frozen two-player scaling.
2. Body/head damage, same-tick fire rejection, wall occlusion, F-key packet reload, ammunition conservation and native damage normalization.
3. Private pause freezing match ticks, damage and native mob behavior.
4. Insufficient funds, exactly-once door/barrier charges, AK-47 purchase, reserve ammo purchase and full-ammo rejection.
5. Completed and interrupted repairs, downing, five-second revive, bleedout, spectator mode and return on the next round.
6. A three-round victory fought by actual client firing packets against native zombies, with persisted rewards and highest round.
7. Complete equipment/status/world restoration and a new match with fresh UUID/geometry in the same JVM, followed by team defeat and another result.
8. A real client disconnect retaining the frozen wave budget; an abrupt process kill; startup recovery of exactly one abandoned match; original equipment restoration and removal of the stale world; and a fourth completed match after recovery.

Local reports and full logs are retained under ignored `run/playtest-*/`; CI uploads JSON reports and server/client logs. The expanded run `run/playtest-81b4d2a2/result.json` verifies the reviewed lifecycle services and F-key packets. Final branch CI results are reported in the PR. PostgreSQL is verified separately by the CI service-backed repository contract rather than represented by H2 results.

## Recovery semantics and practical limits

Completed submissions are forced to a checksummed journal before SQL. The result UUID/fingerprint, statistics and ledger transaction remain atomic and idempotent. A crash after SQL commit but before journal deletion safely replays without a second grant. Corrupt/conflicting records block startup instead of being skipped. Keep `data/results/`, `data/players/` and the account database together in backups; do not delete pending records to force availability.

Active match checkpoints run every **100 match ticks (five seconds at 20 TPS)**. A process crash abandons the match with its last forced checkpoint; it does not resume entity state or invent a victory. Progress since that checkpoint can be lost. Finished-result retries preserve the original immutable result. File contents are forced and replacements are atomic; Linux also forces the containing directory, while Windows uses NTFS atomic replacement and forced file contents. Process-kill recovery was tested; power-loss/storage-controller guarantees were not simulated.

Original inventories use Paper's current inventory size and serialized item format, including 1.21.11's additional slots. Match inventory is locked against transfers, drops and pickups; join-time recovery locks actions until restoration is complete. Original online scoreboard ownership is restored in the same process; after a crash/reconnect the main scoreboard is used until other plugins reapply theirs. External admin plugins that bypass normal events are trusted operators.

The default game has 30 rounds; automated victory uses three rounds with shorter countdown/spawn/intermission settings and a ten-second bleedout, while retaining the weapon balances and five-second revive. A full 30-round balance soak, graphical client layout review, production load profiling, plugin hot-unload/reload, Bedrock and distributed admission are not claimed by this verification. Use configuration reload for core rules and controlled server restarts for content/binary changes.
