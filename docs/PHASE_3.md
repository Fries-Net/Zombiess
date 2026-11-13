# Phase 3 verification

## A. Weapon timing and trigger behavior

The Phase 2 runtime enforced per-instance delays, but shipped 4/2-tick defaults and fired only on right-click events. It had no explicit held-trigger contract, used the same sound for all weapons, and allowed another weapon to fire immediately after a switch. The new `WeaponTiming` and per-survivor `FireControl` use match/server ticks for both individual deadlines and the recovery retained across weapon switches. Reloading, rejected clicks and switching do not reset that recovery.

Semi-automatic weapons fire on press; automatic weapons repeat on server ticks while the native item-use state remains active; configured bursts continue their remaining shots after release and cancel on switch/reload/down. Native long-duration consumable components with no animation or consumption effects provide real start/stop-use semantics. No repeating-click timeout or wall-clock timer supplies fire rate. Modifiers change future shot/reload durations without shortening an already pending deadline.

The initial balance is configurable in gameplay schema 2: Glock-18 10 rounds/30 ticks, AK-47 20/20, MAC-10 20/10, revolver 6/40 with a 3× headshot multiplier, and AWP 1/50 with a 100-tick reload and up to four pierced targets. Every weapon has a different vanilla material and firing sound, configurable muzzle particles and a stable namespaced asset ID. Supplied production pack assets are not required. AWP trace candidates are ordered by distance and remain occluded by solid geometry.

Startup migrates schema 1 using an atomic replacement and preserves `gameplay.v1.backup.yml`. Only exact shipped Phase 2 magazine/cooldown values are replaced; customized values survive. Missing new definitions are added. The previous 53 tests retain their original version-1 fixture and remain passing.

Verification: **60 tests pass**, zero failures/skips. Seven added tests cover exact cadences under tick spam, cross-weapon recovery, reload recovery, semi/automatic/burst behavior, modifiers, migration idempotency and special weapon balance/presentation. Real Paper 1.21.11 build 132 with two protocol clients passed the expanded multiplayer suite (`run/playtest-a28c9719/result.json`), including rapid-click cadence, exact ammo, native held input and an actual hotbar switch, followed by the existing victory/defeat/reuse/crash-recovery checks.

Stage B scoring, proper windows, authoring and station/perk systems are subsequent work. New weapons can be referenced by map stations; the default training map still exposes the Glock and AK stations at this checkpoint. Burst timing is covered by domain tests; the supplied default weapons are semi-automatic or automatic. The regression fixture uses low enemy contact damage so timing tests and deterministic survivor-state assertions do not become a balance test of the new slow weapon rates.
