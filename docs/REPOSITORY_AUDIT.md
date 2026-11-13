# Repository audit and rebuild roadmap

Local baseline: commit `cdaea09` (`init -wip`), clean `master`. All 31 Java source files, both YAML resources and language data, Gradle files, wrapper, README and ignore rules were inspected before implementation. No tests or CI were present. The checkout had no configured remote. Remote inspection later identified GitHub `master` at `0f23129` (`init - wip`) with an unrelated initial history and an earlier, smaller implementation. The feature branch is based on that remote commit; the richer local baseline is preserved in the legacy reference module rather than discarded.

## Baseline build and platform

Both `gradlew.bat build` and `gradlew.bat clean build --no-build-cache` passed. The clean build reported two removal warnings for `Sound.valueOf`, other deprecated APIs, and Gradle 9 incompatibility notices. `test NO-SOURCE` confirmed there was no test coverage. Build success did not establish that the documented gameplay worked in a live server.

Compilation used Paper API `1.21.11-R0.1-SNAPSHOT` and Java release 21, but `runServer` requested Minecraft 1.21. The wrapper was Gradle 8.8. The new baseline aligns compilation, development and smoke testing on Paper **1.21.11 build 132** and Java 21. Paper documents Java 21 for this release line: [runtime requirements](https://docs.papermc.io/paper/getting-started/). Build 132 and its checksum were verified against the official Paper download API. This pins the project target; it does not imply that older Paper releases receive upstream security maintenance indefinitely.

## Existing implementation inventory

| Files | Existing behavior and assessment | Decision |
| --- | --- | --- |
| `JZombies` | Constructor wiring, autosave, reload, shutdown. Reload forcibly stops play; YAML autosave runs on main thread. | Replace composition/lifecycle root. |
| `GameManager` (1,279 lines), `GameState` | Waves, participants, down/revive, hitscan, reloads, shops, gold, range calculations, repair and menus mixed together. Four loosely enforced states. Starts every online player and clears inventories. | Replace with domain aggregate and focused adapters across Phases 1–3. |
| `WeaponService`, `WeaponDefinition`, `WeaponSkin` | Real PDC ammo, item construction, sounds and upgrade helpers, not just decorative items. No stable item-instance identity. Mutable registries, numerical model identity. Pack-a-Punch capacity calculations conflict with ammo clamping. | Preserve source/balance reference; rebuild authoritative combat in Phase 2. |
| `SkillService`, `SkillTreeGuiService`, `SkillDefinition`, `SkillTree`, `SkillType` | Purchasable capped levels, tree selection, modifiers and inventory menu. Mutable profile/maps and title-based menu identity. Reset discards skill points. No transactional permanent progression. | Preserve content concepts; replace persistence and GUI ownership before production use. |
| `ConfigService` (752 lines) | Loads all gameplay/map/economy/content into one YAML; resolves Bukkit worlds during parsing. Silently falls back for several invalid values/references. Exposes mutable collections. | Replace loader and split future content by ownership/directory. |
| `PlayerDataStore`, `PlayerProfile` | UUID profiles in `players.yml`, mutable balances/ownership, synchronous whole-file saves. Match coins and permanent progression coexist. No ledger, crash-safe transaction or concurrency protection. Negative deductions can increase balances. | Replace with immutable DTOs, separate match points and durable repositories. No unsafe automatic import. |
| `MythicMobsBridge`, `MobSpawnDefinition`, `WaveDefinition` | Optional reflective integration; absence permits native mobs. Broad `Throwable` catches and assumed spawn signatures lack version tests. Spawn errors can skip content. Configured waves spawn all mobs at once, with no frozen player scaling. | Preserve optional-integration concept; implement native registry first, then version-tested adapter. |
| `JZCommand`, `JZBasicCommand` | Functional Paper BasicCommand wrapper, permission checks, tab completion, stats/shops/skills/skins/admin commands. Relies directly on global services. | Preserve Paper command API choice; replace command groups incrementally. |
| `GameListener` | Routes damage, downing, interactions, inventory restrictions and joins/quits. Some checks prevent unrelated player actions, but reload and menu state rely on mutable global state and delayed closures. | Replace with session-scoped listeners in Phase 2. |
| `AreaHologramService`, `BuyableArea` | Native text displays and purchasable block removal. No full draft/published map schema; removed door blocks have no durable restoration plan. | Reuse native display approach; rebuild lifecycle/editor ownership. |
| `ScoreboardService`, `TextService`, `LangService` | Sidebar updates, translated strings, legacy/hex colours. Not Adventure-first; scoreboard ownership/restoration needs work. | Preserve message/content reference; centralize presentation in later phases. |
| `StationDefinition`, `StationType`, `VendingUpgrade`, `VendingUpgradeType`, `ArmorTier` | Defined and used by basic box/perk/armor/enchant/upgrade interactions; box is delayed random selection, not a complete collect/timeout/relocation system. | Retain reference definitions; replace with validated registries and transactional interactions. |
| `RangeTarget` | Geometric target scoring exists, but normal shooting is gated to active participants; no independent hub range lifecycle or persistent personal bests. | Replace in Phase 4. |
| `config.yml`, `lang/en_US.yml`, `paper-plugin.yml` | Thirty fixed waves, seven weapons, skins, skills, example world coordinates and stations. No editor, asset pack or independent maps. Descriptor has optional MythicMobs dependency. | Keep intact in the isolated historical module; new plugin uses a distinct data directory. |

The legacy cleanup clears tracked mob UUIDs without reliably removing world entities and does not restore opened door blocks. It does not persist an idempotent result before reuse. These are reasons to isolate it rather than expose its play commands through the new runtime.

The reference module is compiled, has no deployable descriptor in its JAR, and is excluded from the new plugin dependency graph. Original source remains reviewable; it is not silently advertised as completed production functionality. Existing `players.yml` requires a reviewed migration once the permanent economy schema is complete. Keep it untouched and backed up.

## Milestones

1. **Foundation (this branch):** real Gradle modules; aligned platform; immutable strict configuration; guarded match domain/events; asynchronous standalone/PostgreSQL storage; Flyway; transactional result/reward ledger; diagnostics; tests, CI and local run instructions.
2. **Playable matches (implemented; see [verification](PHASE_2.md)):** trusted map templates and world restoration; map loader; gradual waves with frozen scaling; native zombies; firearms/reload identities; points; down/revive; doors/barricades; victory/loss; durable pending-result recovery; end-to-end playable match tests.
3. **Map/content authoring:** per-map drafts and immutable published versions; visual tools, undo/redo, validation/import/export; wall purchases, perks, mystery boxes, upgrades, holograms and custom mob registry.
4. **Visuals:** edition-neutral asset registry; validated deterministic Java pack pipeline; delivery/version pinning; owned-model imports; skins/UI/sounds; shooting range.
5. **Network:** Velocity and hub plugins; signed/versioned reservations; Redis coordination; parties; pre-provisioned Pterodactyl controller; verified routing/draining.
6. **Progression/economy:** full statistics, skills, challenges, consumables, keys/cases/skins, auditable claims and store fulfilment.
7. **Hardening:** load profiling, failure injection, security/integration tests, deployment verification and balance work.

Phase 1 deliberately stops before playable world/combat work. Bedrock rendering and Geyser/Floodgate adapters remain future extensions.
