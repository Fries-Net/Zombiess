# Phase 3 verification

## A. Weapon timing and trigger behavior

The Phase 2 runtime enforced per-instance delays, but shipped 4/2-tick defaults and fired only on right-click events. It had no explicit held-trigger contract, used the same sound for all weapons, and allowed another weapon to fire immediately after a switch. The new `WeaponTiming` and per-survivor `FireControl` use match/server ticks for both individual deadlines and the recovery retained across weapon switches. Reloading, rejected clicks and switching do not reset that recovery.

Semi-automatic weapons fire on press; automatic weapons repeat on server ticks while the native item-use state remains active; configured bursts continue their remaining shots after release and cancel on switch/reload/down. Native long-duration consumable components with no animation or consumption effects provide real start/stop-use semantics. No repeating-click timeout or wall-clock timer supplies fire rate. Modifiers change future shot/reload durations without shortening an already pending deadline.

The initial balance is configurable in gameplay schema 2: Glock-18 10 rounds/30 ticks, AK-47 20/20, MAC-10 20/10, revolver 6/40 with a 3× headshot multiplier, and AWP 1/50 with a 100-tick reload and up to four pierced targets. Every weapon has a different vanilla material and firing sound, configurable muzzle particles and a stable namespaced asset ID. Supplied production pack assets are not required. AWP trace candidates are ordered by distance and remain occluded by solid geometry.

Startup migrates schema 1 using an atomic replacement and preserves `gameplay.v1.backup.yml`. Only exact shipped Phase 2 magazine/cooldown values are replaced; customized values survive. Missing new definitions are added. The previous 53 tests retain their original version-1 fixture and remain passing.

Verification: **60 tests pass**, zero failures/skips. Seven added tests cover exact cadences under tick spam, cross-weapon recovery, reload recovery, semi/automatic/burst behavior, modifiers, migration idempotency and special weapon balance/presentation. Real Paper 1.21.11 build 132 with two protocol clients passed the expanded multiplayer suite (`run/playtest-a28c9719/result.json`), including rapid-click cadence, exact ammo, native held input and an actual hotbar switch, followed by the existing victory/defeat/reuse/crash-recovery checks.

New weapons can be referenced by map stations; the default training map still exposes the Glock and AK stations at this checkpoint. Burst timing is covered by domain tests; the supplied default weapons are semi-automatic or automatic. The regression fixture uses low enemy contact damage so timing tests and deterministic survivor-state assertions do not become a balance test of the new slow weapon rates. Checkpoint commits: `055d405`, `7c4152d`; [all CI jobs passed](https://github.com/Fries-Net/Zombiess/actions/runs/43502932112).

## B. Applied-hit gold economy

Historical public guides describe weapon-specific hit gold, headshot bonuses and rewards per repaired plank: [Hypixel community guide](https://hypixel.net/threads/full-guide-statistics-hypixel-zombies.1217776/) and [headshot examples](https://hypixel.net/threads/guide-almost-everything-about-hypixel-zombies.1210823/page-5). These are historical community observations, not a specification of current server internals. The implementation is independent and its values are configurable.

Gameplay schema 3 separates `PointsPolicy` from permanent rewards. Glock body/head hits grant 10/15 gold; AK 7/10; MAC-10 4/6; revolver 12/24; AWP 20/30 per distinct pierced enemy. Fixed kill/round gold is disabled by default. Melee, explosions, periodic damage, optional lethal bonuses, assists and objectives have independent policy values. Defaults grant no periodic-damage, assist or lethal bonus; explosive hits grant 5 per enemy, knife hits 10, and objective completions 100. The knife occupies slot 9.

`MatchDamageService` applies damage before emitting a receipt, validates tracked live enemies, and deduplicates attack/target pairs. `PointsLedger` deduplicates receipts again, rejects unchanged/nonfinite/dead/unknown-target damage, suppresses rewards for repeatedly healing and re-damaging the same health band, and caps total gold per enemy. Piercing and area effects use one action identity across distinct targets. Repair rewards track board identity across the team per round, with per-player/window round caps. Objectives are idempotent per player/objective/round. No gold operation mutates accounts, tokens or experience.

Double-gold pickups are owned expiring items with a configurable drop probability and match-tick duration. Pickup extends the team multiplier, removes the item once, and never inserts gold ingots into player inventories. The sidebar names the match currency Gold; recent gains are aggregated in the action bar. Schema 1/2 migrations retain backups and custom weapon/asset values while adding the new policy. Legacy fixed amounts are dormant unless explicitly enabled.

Verification: **72 tests pass**, zero failures/skips. Twelve scoring tests cover all damage categories, body/head/lethal hits, duplicates, multi-target actions, healing/caps, assistants, multiplier expiry, repairs, objectives and account separation. Real two-client Paper completed **52 live assertion stages**, including native knife events, zero-gold invalid targets, duplicate explosions, zero-gold periodic damage, a three-enemy AWP shot and long reload, and a real double-gold pickup, followed by victory, defeat, restoration, repeated matches and forced-process recovery (`run/playtest-3fcc8d00/result.json`).

The shared damage adapter supports explosion/periodic sources; no explosive weapon is shipped in this checkpoint. Objective issuance is tested through the policy API; editor-managed objective triggers are later work. Proper physical windows, visual authoring, mystery boxes, perks and Pack-a-Punch remain subsequent stages.

## C. Physical zombie windows

Map schema 2 defines oriented windows with individually owned boards, exterior spawn regions, approach/entry points, room requirements, break/repair durations and repair rewards/caps. The training map now has three physical windows; purchasing workshop access activates the third. Only the unchanged shipped Phase 2 training definition migrates automatically, with a backup. Customized legacy maps retain their existing behavior.

`WindowState` serializes damage and repair transitions by match tick. Multiple attackers cannot accelerate a board transition or duplicate rewards. `WindowService` routes exterior zombies to the approach point and then through the two-block opening using native Paper pathfinding. Exterior attackers do not collide with one another; normal collision returns on entry. No runtime teleport fallback is used. Only designated board coordinates with their configured material are transparent to bullets; ordinary walls still block them.

Continuous sneak within range and line of sight restores one missing board at a time. Moving away, downing, reviving, occupied geometry or the configured active-attack restriction interrupts repairs. Board identity and round caps prevent repeated repair farming. Crack stages, wood particles/sounds and action-bar progress expose actual transitions. Match cleanup discards the world; repairs never rebuild an entire region.

Verification: **82 automated tests pass**, including ten new definition/state tests. The final two-client Paper run passed **63 assertion stages**, including intact-window hitscan, progressive breaking, simultaneous attackers entering, occupied-space repair rejection, repair interruption, reward caps, door activation and repeat-match restoration (`run/playtest-350eb9e2/result.json`). The editor integration follows in stage D.

## D. Visual map editor

Recovery on 14 November 2026 confirmed `feature/phase-3-map-editor` at `3c648ac` as the latest
development head. Remote master was `7f8fd9a`; the economy branch's `50ff136` merged windows
there, while this editor branch already reached the original window commits directly. No replay,
reset or duplicate cherry-pick was needed. Nineteen interrupted working-tree files were preserved
in an ignored recovery archive before auditing and committing the implementation. There were no
stashes. The original master integration remains an explicit owner decision.

The editor now exposes commands, identity-checked inventory menus, configurable region tools,
point placement, window board selection, particle previews, property menus, room starting states,
undo/redo and destructive-action confirmation. Authors use isolated Creative leases and durable
equipment backups. The editor creates/duplicates maps, imports unloaded world folders, exports
metadata/worlds, autosaves terrain and drafts, validates physical geometry/walking routes, publishes
immutable versions and checks historical versions out as drafts. Publication during a match changes
only future matches. Editor-created weapon/ammo stations honor weapon choice, prices and room
requirements. Holograms honor text, alignment, audience and room rules.

Verification: **88 unit/H2 tests, zero failures/skips**. The combined Paper 1.21.11 build 132
two-client suite passed **90 stages** (`run/playtest-c06bbd8c/result.json`), including all A–C checks,
victory/defeat, repeated worlds, both match and editor forced-process recovery, native menu/tool
input, real imported terrain, chest inventories/block states, untouched source-world checksums,
publication/version pinning, configured door/weapon/ammo purchases, autosave and duplication.
An additional **31-stage editor suite** passed historical-version checkout and public-pointer
isolation (`run/playtest-7ca1b5ef/result.json`). GitHub Actions for `b002da6` passed all jobs:
[build, PostgreSQL and Paper](https://github.com/Fries-Net/Zombiess/actions/runs/43576651563).
Subsequent commits must verify their own CI result.

One combined local run (`run/playtest-f086235c`) timed out waiting for automated battle victory
after its explicit combat assertions passed. The diagnostic rerun completed victory and the whole
suite. No runtime cause was reproduced or claimed fixed; battle diagnostics remain in the separate
test plugin so recurrence includes enemy/weapon state. Logs from both runs were retained.

Manual rendered-client acceptance remains for particle/menu readability, hologram audience and
alignment, WorldEdit coexistence and representative production-map navigation. Route checks are
bounded and conservative ordinary walking checks. Optional schematic placement currently uses an
installed WorldEdit/FAWE tool in the draft; no bundled schematic parser is claimed. Metadata undo
does not undo arbitrary terrain. See [authoring instructions and limits](MAP_EDITOR.md).

Next gameplay checkpoint: **Phase 3E** — add validated weighted mystery-box pools, then connect
the existing `MYSTERY_BOX` objects to a server-tick purchase/animation/collection service with
refunds on cancellation and inventory-capacity checks. Perks, Pack-a-Punch, power/boss/range/custom
objects remain explicitly inactive editor placeholders until their respective runtime checkpoints.
