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

Next work is the independent targeting/window branch, followed by archetypes/power-ups, mystery-box
presentation/training content and the optional Discord adapter. Original Blockbench ZIP and prior
packs are absent; V3 requires those sources and visual approval rather than replacement artwork.
