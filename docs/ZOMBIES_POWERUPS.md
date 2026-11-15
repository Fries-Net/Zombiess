# Zombie populations and classic power-ups

`zombie-archetypes.yml` defines native zombie archetypes, weighted pools and optional map-ID to
pool bindings. Wave count, round curves, spawn pacing and the global live limit still come from
`gameplay.yml`. Each pool entry has minimum/maximum rounds and independent live/round caps. A
positive-weight, unlimited round-one fallback prevents exhausted rare pools from stalling a wave.
If live caps are full, spawning waits without consuming the remaining wave budget.

The supplied pool introduces runners at round 3, babies at 5, tanks at 7, armoured zombies at 9
and rare elites at 15. Native attributes are clamped after wave scaling and controlled variation:
240 health, 20 damage and 0.38 movement speed by default. Armour is an explicit total; decorative
equipment has no extra attribute modifiers and cannot drop. Babies use native baby geometry.
`headshot-scale` controls the bonus above body damage (zero also disables headshot gold). Elite
names/glow provide a vanilla visual fallback. Asset IDs and the `ZombieFactory.Provider` boundary
allow future adapters; the shipped catalog accepts only the available `minecraft:zombie` provider.

`power-ups.yml` owns drop chance, weighted rarity, round thresholds/limits, concurrent drop bounds,
despawn times and timed-effect stacking. It supersedes the legacy `double-gold-drop-chance` for
automatic drops; old gameplay files still parse. Double Points uses the existing points ledger,
including its deduplication and multiplier, rather than introducing another economy layer.

* Insta-Kill lasts 400 ticks by default. Only valid bullet/melee damage against eligible tracked
  enemies triggers it; cancellation, invulnerability and duplicate-receipt checks remain active.
* Max Ammo refills existing weapon instances at their current upgrade capacities. Magazine refills
  cancel reloading while preserving shot recovery. Downed/dead inclusion is separately configurable.
* Double Points lasts 600 ticks. REFRESH, EXTEND and IGNORE policies use match ticks and a maximum
  duration; pauses freeze gameplay timing.
* Nuke damages a snapshot of active tracked enemies. Eligible enemies receive lethal damage;
  excluded elites receive 40 damage by default. It cannot damage future spawns or generate drop
  chains. One configured team reward replaces hit, assist and lethal bonuses for this action.
* Carpenter restores only windows whose entire missing geometry is unoccupied and unobstructed.
  It pays no repair gold; an optional explicit team reward defaults to zero. Existing anti-farming
  accounting is retained for subsequent manual repairs.

Drops have a visible native item, floating countdown label, particles and activation sound. Native
items never enter inventories: ownership is removed before applying a pickup, making repeated events
inert. The service bounds active drops, expires both item and label, and removes all state on match
cleanup. Crash recovery removes the disposable world, including uncollected items and labels.

The editor's `POWERUP_ZONE` region object restricts allowed drop types (`allowed`, comma-separated
enum names or `NONE`) and supports `enabled: false` exclusion zones. Overlapping zones intersect
their allowances. Maps without zones allow all enabled types.

## Verification and visual acceptance

Unit checks cover pool thresholds/caps, bounded variation, invalid references, stacking deadlines,
ammo identity/recovery and Nuke reward deduplication. Paper fixtures create all six real archetypes,
inspect effective native attributes/collision, collect all five drops with two clients, reject
duplicate/expired events and verify upgraded team ammo, Carpenter geometry and Nuke rewards.

Run `python tools/paper_playtest.py --accept-eula --profile` after building to measure mixed native
populations under two-client sustained two-tick fire. The log reports actual population, shot counts
and sampled tick-time percentiles; it is a reproducible workload, not a hardware-independent latency
guarantee. The ordinary full harness also verifies match/editor crash recovery.

In a graphical client, inspect baby/adult passage, armour, elite readability, pickup labels at two
GUI scales and simultaneous pickup announcements. Try exclusions around spawn rooms and safe/occupied
Carpenter windows. Confirm normal inventory restoration after defeat, disconnect and server restart.
