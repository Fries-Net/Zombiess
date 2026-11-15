# Crosshair purchases and complete windows

All purchase services resolve one ray from the player's eye, with a 3.5-block reach and solid-block
occlusion. The frontmost intersected box wins; a smaller nested box wins an equal-distance tie.
Indistinguishable overlaps fail closed. Standing inside a box does not make it selectable in every
direction. Door, wall weapon/ammo, perk, upgrade and mystery stations share the same target and HUD
prompt. Native block interactions use their cursor hit to tolerate rotation packet ordering, while
retaining server-side reach, occlusion and hitbox checks. Repeated held block input requires a quiet
six-tick gap before another purchase, including when an opened door exposes a station behind it.

Existing station objects default to a one-block-wide point hitbox. Choose `hitbox: REGION` in the
editor and apply the wand selection to author a custom block-aligned hitbox. The selected object's
particle outline shows the purchase volume. Keep stations separated and avoid identical overlapping
volumes. A wall purchase's hitbox must reach its visible front face to remain selectable through its
own solid backing block. Map validation bounds explicit interaction regions to 2,048 blocks.

Physical windows default to `entry-policy: ALL_BOARDS`: all designated boards must break before
normal entry, even after the centre becomes passable. `PASSABLE_OPENING` explicitly restores the
special-map partial-entry behaviour. Editors can choose the policy in the selected window menu.
Each board retains its own attack progress, and concurrent attackers advance the window at most once
per tick. Repairing a different board cannot transfer damage. Repaired boards can break again.

Native guidance checks the complete zombie body against remaining boards and collision shapes,
and waits until the body clears the plane before releasing navigation. Attacks use native arm
swings, cracks, wood particles and sounds. Existing occupied-space repair guards, reward caps and
disposable-world restoration remain in force.

## Acceptance

The multiplayer fixture authors adjacent perk stations and overlapping door/mystery areas, sends
native cursor purchases from two clients, verifies the exact charge and rejects looking away,
occluded and out-of-range input. A real native pathfinding fixture exercises adult and baby passage
through NORTH/SOUTH/EAST/WEST windows under full destruction and a partial-entry window. Full-game
checks retain interrupted/occupied repairs, caps, rebreaking, restoration and crash recovery.

Normal-client acceptance remains: inspect crosshair prompts before purchasing every station type,
try holding right-click while opening a door, view editor outlines and check board swing/crack/sound
presentation. Repeat a match and confirm all original boards return. No graphical presentation
quality is implied by protocol-client checks.
