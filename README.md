# FriesNet Zombies

A playable, standalone Zombies match for **Java 21 / Paper 1.21.11 build 132**. Phase 2 builds on the modular Phase 1 foundation: versioned maps, gradual scaled waves, Glock-18 and AK-47 combat, purchases, repairs, down/revive, victory/defeat, durable results and reusable instances.

No Redis, Velocity, MythicMobs or external database is required for local play. Standalone accounts use durable H2; PostgreSQL uses the same transactional repository contract.

| Module | Responsibility |
| --- | --- |
| `zombies-common` | Immutable configuration/content, match domain, ammunition, survivor state, results and persistence ports |
| `zombies-persistence` | Bounded asynchronous JDBC and disk workers, migrations, atomic rewards, crash recovery journal |
| `zombies-game` | Paper lifecycle coordinator and separate world, combat, wave, life, interaction, presentation and restoration services |
| `zombies-legacy` | Compiled historical reference, excluded from the deployed plugin |

## Build and play

```sh
./gradlew clean build
./gradlew :zombies-game:runServer
```

On Windows use `gradlew.bat` and set `JAVA_HOME` to JDK 21. Review and accept Minecraft's EULA in `run/eula.txt` before restarting the development server. On another server install only `zombies-game/build/libs/zombies-game-0.1.0.jar`; the `thin` and `paper-integration` JARs are not the release plugin.

Connect a Java 1.21.11 client, then use `/zombies join training`. The lobby gathers teammates for five seconds and freezes its reservation before the configured countdown. Default capacity is 1–4. New arrivals wait for the next match; disconnecting never reduces the current match's difficulty.

- Right-click to fire. Press **F**, left-click, or use `/zombies reloadgun` to reload. Changing weapon cancels its reload.
- Approach the labelled workshop door or service barrier and right-click to purchase access. The workshop contains the AK-47 station; the Glock station supplies reserve ammunition. Owned weapon stations sell ammo instead of duplicate weapons.
- Sneak and right-click beside a damaged barricade to repair it. Keep sneaking and stay nearby until the progress completes.
- Sneak within 2.5 blocks of a downed teammate, with line of sight, for five seconds to revive them. Bleedout takes 25 seconds. Dead teammates spectate and return after a round clear; a team with no standing survivor loses.
- Clear all 30 configured rounds to win. Results award tokens and experience separately from temporary match points. Cleanup restores original equipment, location, experience and game mode, removes the disposable world, and permits another match in the same JVM.

The supplied Foundry Training map is pinned by version and content digest. Each match gets a new world and UUID. Operators can [author maps in game](docs/MAP_EDITOR.md), import unloaded Java world folders, and publish immutable versions while matches retain their original map. Perks, mystery boxes, Pack-a-Punch, production packs and network services remain later milestones.

Phase 3 weapon/economy changes are being delivered in focused checkpoints; see [the verification report](docs/PHASE_3.md). Glock-18 now has 10 rounds and a 30-tick interval; AK-47 20/20; MAC-10 20/10; revolver 6/40; AWP 1/50 with a long reload and piercing. Fire and reload timings, trigger modes, sounds and placeholder visuals are configurable. A knife is available in slot 9.

Match gold now comes from actual damaging hits. Glock body/head hits grant 10/15, AK 7/10, and other guns have their own values. Missing, blocked or invulnerable hits grant nothing. Default fixed kill/round gold is disabled. Double-gold pickups multiply match gold only; permanent tokens and experience retain their separate result transaction. Existing schema 1/2 gameplay files migrate with backups.

## Commands

`/zombies` and `/jz` accept:

| Command | Permission | Behavior |
| --- | --- | --- |
| `join [map]`, `leave`, `reloadgun` | `zombies.use` (default true) | Join the lobby, leave safely, reload the held firearm |
| `status`, `profile` | `zombies.use` | Instance/map status and asynchronous permanent account summary |
| `health`, `validate`, `reload` | `zombies.admin` (op) | Storage diagnostics and core configuration validation/reload |
| `pause`, `resume` | `zombies.admin` | Freeze/resume an eligible private match, including combat, clocks and native mobs |
| `stop` | `zombies.admin` | End play as abandoned, persist results, and clean up |
| `editor enter <map>`, `editor create <map>`, `editor menu` | `zombies.editor` (op), or `zombies.admin` | Open an isolated Creative map editor with selection/placement tools |
| `editor save`, `validate`, `publish`, `undo`, `redo`, `exit` | `zombies.editor`, or `zombies.admin` | Save drafts, validate routes and geometry, publish versions, undo metadata and exit with confirmation |

The plugin creates `config.yml`, `database.yml`, `gameplay.yml`, `maps/training.yml` and `data/` inside `plugins/FriesNetZombies/`. Core rules can reload for future sessions; gameplay and map snapshots load at startup. Existing legacy configs and `players.yml` are preserved without automatic migration.

## Verification

```sh
./gradlew clean build
./gradlew :zombies-persistence:integrationTest  # disposable PostgreSQL credentials required
python tools/paper_smoke.py --java /path/to/java21 --accept-eula
npm ci --prefix tools/paper-client --ignore-scripts --no-audit --no-fund
python tools/paper_playtest.py --java /path/to/java21 --accept-eula
```

The multiplayer harness requires Python 3.10+, Node 22+, and network access on first use. It uses two real protocol clients on an isolated loopback server and a separately packaged console-only assertion plugin. It tests a shortened three-round content fixture, combat/occlusion, purchases, repairs, down/revive/bleedout/respawn, victory, defeat, world restoration, repeated games, durable rewards, and an abrupt JVM kill followed by recovery. CI runs builds, PostgreSQL contracts and this Paper harness, retaining reports and server logs.

See [Phase 2 verification and limits](docs/PHASE_2.md), [architecture](docs/ARCHITECTURE.md), [deployment](docs/DEVELOPMENT.md), [roadmap](docs/REPOSITORY_AUDIT.md), and the historical [Phase 1 report](docs/PHASE_1.md).
