# Phase 3.5 playtest corrections

Base: merged master `6e9369f` (perks and Pack-a-Punch included). Work is split into independent
feature branches and PRs targeting master; no automatic merges.

## Gun handling and HUD — PR #10

`feature/phase-3-5-gun-handling` uses the 1.21.11 `USE_EFFECTS` component with speed multiplier 1,
sprinting enabled and interaction vibrations disabled. Native use and swing animations are NONE.
The native held-use input remains authoritative and retains real release events. There is no walk
speed compensation that could stack with Speed. Cooldown/switch/reload rules remain unchanged.

| Weapon | Shot interval | Magazine/reserve | Body/head gold |
| --- | --- | --- | --- |
| Glock-18 | 8 ticks | 12 / 180 | 3 / 5 |
| AK-47 | 4 ticks | 30 / 300 | 1 / 2 |
| MAC-10 | 2 ticks | 32 / 320 | 1 / 1 |
| Revolver | 15 ticks | 6 / 84 | 6 / 10 |
| AWP | 30 ticks | 3 / 48 | 10 / 15 |

All values remain configurable in `gameplay.yml`; compatible tier changes live in
`weapon-upgrades.yml`. The migration recognizes exact shipped weapon/upgrade pairs, creates
pre-3-5 backups and preserves customized pairs. Upgrades are written first; interruption recovery
recognizes the completed half-write. Custom pairs require an explicit balance review.

`hud.yml` controls notice duration, update interval, hit/headshot text and separator. The bounded
scheduler combines transient feedback with ammo/reload information, aggregates repeated headshots,
and prioritizes critical life/revive state. Combat and station services no longer race action-bar
writes. Historical scoring-contract fixtures remain fixed; separate tests cover the faster balance.

The Paper harness checks configured cadences, native sprint/held-fire with Speed, component delivery,
movement displacement and readable headshot/reload overlap. Mineflayer needs explicit modern
player-input flags and mapped sprint action names for the pinned protocol. Its physics does not
implement vanilla item-use effects, so normal graphical-client acceptance remains required.

In Minecraft: sprint and strafe while holding each automatic weapon; tap and hold semi-automatic
weapons; switch/reload during cooldown; repeat with Speed and upgraded weapons. Check first- and
third-person hands for eating/blocking/swing artifacts and confirm headshots remain readable beside
ammo. No graphical acceptance has been claimed. Final automated results are recorded on PR #10.

## Review checkpoints

All branches start from master `6e9369f`; no feature has been merged into master during this work.

| PR | Branch suffix (`feature/phase-3-5-…`) | Checkpoint | Scope |
| --- | --- | --- | --- |
| [10](https://github.com/Fries-Net/Zombiess/pull/10) | gun-handling | `09df356` | Native use effects, balance, coordinated HUD |
| [11](https://github.com/Fries-Net/Zombiess/pull/11) | targeting-windows | `c28f141` | Crosshair purchases, full boards, framed adult/baby traversal |
| [12](https://github.com/Fries-Net/Zombiess/pull/12) | enemies-powerups | `97c7700` | Six bounded archetypes, five classic power-ups |
| [13](https://github.com/Fries-Net/Zombiess/pull/13) | presentation-training | `eb6a9c2` | Staged mystery rendering, unpaid preview, separate training lab |
| [14](https://github.com/Fries-Net/Zombiess/pull/14) | discord | `d40f44f` | Optional bounded staff logging and two-way chat |
| [15](https://github.com/Fries-Net/Zombiess/pull/15) | `feature/resource-pack-v3` | `aa83834` | Original models, standalone pose review, optional per-player delivery |

The `verify/phase-3-5` branch combines those histories for regression verification only. It retains
the shared constructor/content fields, every test dispatcher and the coordinated HUD during conflict
resolution. It is not an additional aggregate feature PR. Use its resolutions when reviewing the
independent PRs together. Check each exact head's Actions results before merging; earlier green
results do not establish that a newer commit passed.

Combined validation runs the full two-client Paper suite, mixed-population/two-tick-fire profile and
unavailable-Discord match, plus unit/H2 and PostgreSQL checks. The native suite covers editor
publication/version pinning, physical gameplay objects, upgrades/perks, pickups, repeated matches,
restoration and forced match/editor process recovery. The separate configuration documents contain
rules and graphical/live acceptance steps:

* [Interactions and windows](INTERACTIONS_WINDOWS.md)
* [Zombie populations and power-ups](ZOMBIES_POWERUPS.md)
* [Mystery presentation and training lab](MYSTERY_PRESENTATION.md)
* [Discord setup, security and acceptance](DISCORD.md)

Next external acceptance is a normal graphical Minecraft session using `foundry_lab`: sprint/strafe
while holding automatic fire, inspect hands and headshot HUD, target adjacent stations, destroy and
rebuild every board, inspect mystery animation and complete a match. Discord additionally needs a
live guild/channel permissions and webhook appearance/reconnect test with protected credentials.

The original 66-model Blockbench archive was recovered from the local Downloads directory. PR #15
preserves its indexed textures and UVs, exports 71 stable item-model IDs, and provides a standalone
source/export pose viewer with pack-only rebuilds. Seven exporter tests and 40 browser contexts pass;
Windows/Linux CI produces identical pack hashes. See [V3 review and delivery](RESOURCE_PACK_V3.md).

V3 remains a preview awaiting normal Minecraft first/third-person, GUI and display acceptance.
The optional delivery service defaults to disabled and activates only explicitly approved assets
after each client reports a successful load. Decline/failure retains vanilla visuals. Prior V1/V2
variant manifests and an identifiable Revolver source remain unavailable. Next exact external step:
review the five priority guns using the documented give commands and record screenshots plus pack
hash before enabling model mappings. No production visual approval has been claimed.
