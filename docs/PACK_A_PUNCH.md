# Pack-a-Punch

Add a `PACK_A_PUNCH` object, place its point at the station, set room requirements and publish.
The `weapons` menu selects all configured weapons or one weapon. A comma-separated list also works
through `/zombies editor set weapons <ids>`. `price` defaults to each weapon's next-tier cost;
the price menu offers common overrides and **Use definition price**. The command equivalent is
`/zombies editor set price default`. Older stations without properties use these same defaults.

`weapon-upgrades.yml` defines one to five contiguous tiers per weapon, each with a price, name,
damage, magazine/reserve capacities, cooldown/reload durations, model asset reference, firing sound
and upgrade sound. Bundled weapons have two tiers costing 5,000 and 10,000 gold. Definitions are
validated against `gameplay.yml`: tiers cannot lower damage/capacity or lengthen timings. Custom
weapon stats require compatible tier definitions. Unknown weapon references and unsafe values fail
validation. Maps with no upgrade stations remain supported; their geometry is never rewritten.

Hold an owned firearm and right-click an unlocked station. The server validates ownership, life,
room access, station restrictions, funds, reload state and upgrade limit before charging. Purchases
within ten match ticks are debounced. There is no delayed inventory escrow or dropped weapon to
lose or duplicate. A failed purchase leaves both points and the authoritative instance unchanged.

An upgrade keeps the weapon UUID, remaining magazine/reserve ammunition and existing shot recovery.
It does not refill ammunition. The configured larger capacities apply on later reloads and ammo
purchases; wall stations refill the upgraded reserve capacity. Perks compose with upgraded weapon
stats. Reloading blocks upgrades; switching cancels a reload using the existing behavior.

Names, native glint, capacity lore, sounds and particles distinguish upgraded weapons without a
resource pack. The item retains its namespaced tier asset ID for later custom models. Actual
Blockbench models and pack delivery are not bundled in this milestone.

Downing/reviving preserves upgrades on retained weapons. Mule Kick loss still removes excess
instances. Death/respawn follows the existing match inventory policy. Disconnects restore original
equipment; new matches create fresh unupgraded instances. Crashed matches are abandoned and durable
player snapshots restore the original inventory instead of resuming transient upgrades.

Manual acceptance remains for station spacing, text readability, glint, upgrade sound balance and
custom models when supplied. The protocol-client harness checks native purchases and authoritative
stats, not rendered artwork.
