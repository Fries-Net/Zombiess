# Mystery presentation and Foundry Training Lab

The mystery reward is selected once before the charge, retained by `MysteryRoll`, and collected or
refunded using the existing ownership rules. `MysteryVisuals` only renders. Removing a display or
changing cycle timing cannot change the chosen reward or inventory state. Visual cycling uses a
separate random source.

`mystery-boxes.yml` optionally contains `presentation`. Older files receive the same bounded defaults
without rewriting customized pools: 8 activation ticks, 2-to-12-tick slowing preview cycles, a
1.25-block preview height, 0.5 item scale and 10 closing ticks. Native chests open/close when the
station region identifies a chest; other stations activate with sound/particles. The display rises,
cycles with progressively slower clicks, reveals the authoritative weapon, highlights rare rewards,
then shrinks on collection/refund. No physical reward item is dropped.

`use-item-models` defaults to false. Enable it only with a validated pack providing every configured
weapon asset ID; preview items then use Paper's item-model component. Native materials remain the
default fallback while original weapon artwork is being verified. Per-station `preview-yaw` is an
integer from -180 to 180. Global height and scale remain configurable. These defaults need graphical
acceptance; parsing and protocol inspection do not establish visual quality.

Select a mystery object in the editor and choose **Preview mystery animation**, or use
`/zombies editor boxpreview`. This runs an unpaid demonstration using the same renderer, with a
three-second reveal and automatic closing. One preview per author replaces the previous preview;
exiting the session removes it. It grants no weapon and charges no gold.

## Updated demonstration

`/zombies join foundry_lab` opens a separately named Foundry Training Lab. New installations and
upgrades receive it only when `maps/foundry_lab.yml` is absent. Existing training maps, imported
worlds and administrator-customized lab files are never replaced. The original training template
retains its identity and geometry for compatibility.

Eight colour-coded perk machines stand in two spaced rows in the spawn room. Pack-a-Punch is in
the workshop, behind the existing unlockable door. Mystery box, both wall weapons and all three
zombie windows are retained. Machine hitboxes have gaps between them; central routes and window
entry lanes remain clear. Prices come from perk/upgrade definitions, preserving ownership caps.

## Graphical acceptance

1. In the editor, view the animation from each side and at different GUI scales, with the resource
   pack disabled. Adjust scale, height and `preview-yaw`; confirm the item stays compact and readable.
2. Purchase a box with another player present. Check opening, fast-to-slow cycling, stable reveal,
   rare effects, claim countdown, refund, closing and relocation. A non-owner must not collect it.
3. Enable a verified pack and repeat each weapon preview, including left/right viewing angles.
4. Join `foundry_lab`, buy each perk across separate lives as required by the four-perk cap, unlock
   the workshop and upgrade a weapon. Try wall ammo and the mystery box, repair windows, finish a
   match and confirm original inventory/world restoration.

Automated checks cover bounded timing, legacy pool compatibility, station separation, native display
transforms/scale/reveal, unpaid editor previews and functional lab Juggernog, door and upgrade
purchases. Normal-client presentation approval remains a manual step.
