# Perk stations

Add a `PERK` object in the map editor, select its `perk` property, set the price, and publish.
The existing `friesnet:perk/extra_health` reference is Juggernog. Removing a station's optional
`price` property makes it follow the definition price. Room references gate purchases.
Map geometry is preserved; existing maps can add vending locations through the editor.

`perks.yml` is a validated startup snapshot. Active matches retain their own registry. Each
definition has a namespaced asset ID, enabled flag, price, minimum round, solo/team restrictions,
per-match purchase limit, Wunderfizz weight and type-specific effect settings. Unknown fields,
unsafe values, duplicate effect types, and invalid map references fail validation. Changes require
a controlled restart; no in-flight purchase reads mutable configuration.

| Perk | Default behavior |
| --- | --- |
| Juggernog | Maximum health 40; purchase grants the added health once |
| Speed | Walk speed multiplied by 1.3 |
| Frozen Bullets | 35% of accepted firearm hits apply Slowness II for 60 ticks |
| Quick Revive | Teammate revive duration divided by 2; solo self-revive after 100 ticks |
| Speed Cola | Reload duration divided by 2 |
| Mule Kick | Four weapons instead of the default three |
| Double Tap | Fire rate multiplied by 1.25, firearm damage multiplied by 1.2 |
| Der Wunderfizz | Weighted instant purchase of an eligible, unowned perk |

All perks are lost on downing. Losing Mule Kick removes the newest excess weapon instances and
their inventory items without drops. Ammo, instance IDs and existing cooldowns on retained weapons
are preserved. Reloads cancel on downing. Rate changes affect future shots; existing deadlines are
never reset by purchasing or switching. Weapon capacity is configurable (maximum eight with Mule
Kick); the knife keeps hotbar slot nine. Existing weapon definitions and map formats are unchanged.

Solo means the frozen match roster contained exactly one player, never the last connected player
of a multiplayer match. Solo Quick Revive is consumed on downing; the downed player automatically
returns with 10 health and brief protection. The match waits for that scheduled rescue instead of
declaring defeat. The delay is bounded below bleedout. The default purchase limit permits three
Quick Revive purchases per match, including Wunderfizz rewards. Teammates still require proximity,
line of sight and uninterrupted sneaking. Multiplayer Quick Revive never self-revives its owner.

The default ownership cap is four. Wunderfizz excludes disabled, owned, capped, purchase-limited
and ineligible effects; an empty reward pool never charges. Purchases and ownership changes happen
atomically on the server thread, with a ten-tick purchase debounce across stations. Match gold
remains separate from persistent account balances. Perks grant effects, not transferable items.

Downing, leaving and cleanup clear ownership. Original player snapshots restore movement, inventory,
health and effects; only the owned perk health modifier is removed, including on crash recovery.
Interrupted matches are abandoned, so perks and purchases are not resumed after a crash.
Slowing effects live only on tracked enemies in the disposable match world.

Manual acceptance: inspect station labels, prices, purchase sounds/particles and HUD readability
with a normal Java client; check custom resource-pack assets when supplied. Automated protocol
clients validate gameplay and native input but do not replace rendered visual review.
