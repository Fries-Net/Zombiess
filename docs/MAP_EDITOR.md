# Visual map authoring

Operators use `/zombies editor` to open the map menu. `/zombies editor open training` creates an isolated draft world and captures the author's original equipment through the same durable return mechanism used by matches. Build in Creative or with installed WorldEdit tools. Closing restores the original equipment; save before closing to retain changes. Only one author can own a given draft at a time.

To import a world, place its **unloaded** folder directly in the server world container, then use:

```text
/zombies editor import foundry my_world training
```

This copies geometry from `my_world` and starts gameplay metadata from `training`. The source folder is preserved. Adjust bounds, spawns and objects for your geometry. Import does not require a cuboid blueprint. Source identity, player data and session locks are excluded. Snapshots reject symbolic links and are bounded to 4 GiB/50,000 entries. Coordinates support the Paper overworld height range, including negative heights. Optional schematic placement can be performed with installed WorldEdit inside the draft; the plugin does not bundle WorldEdit.

The hotbar supplies four tools:

| Tool | Interaction |
| --- | --- |
| Nether star | Select the nearest gameplay object; sneak and use it for the main menu |
| Stick | Right-click a block to place the selected point above it |
| Wooden axe | Left/right-click the two region corners |
| Blaze rod | Right-click an existing block to add/remove its membership in the selected window |

The Add menu includes player/spectator spawns, zombie regions, weapon/ammunition stations, mystery boxes, perks, Pack-a-Punch, power, holograms, boss spawns, range targets and custom interactions. Door creation uses the selected block region. Window creation uses the selected flat plane of existing board blocks; edit its orientation, approach, entry and spawn region afterward. The initial generated orientation is north or west. Use the property menu or commands to set south/east where appropriate. Particle markers and region edges are visible only to the author.

Object menus expose point tools, region application, individual board selection, prices, durations, room requirements and other properties. Numeric properties have increment/decrement buttons. Text properties provide a suggested command. Typical commands are:

```text
/zombies editor add window loading_window
/zombies editor select windows loading_window
/zombies editor set orientation SOUTH
/zombies editor point approach
/zombies editor point entry
/zombies editor region spawn-region
/zombies editor set break-ticks 40
/zombies editor set repair-ticks 40
/zombies editor set repair-gold 10
/zombies editor set round-gold-cap 60
/zombies editor set required-rooms spawn,workshop
/zombies editor set deny-repair-under-attack true
/zombies editor add door workshop
/zombies editor set unlocks-room workshop
/zombies editor set price 750
```

`point` records the author's current position; the point tool instead records the clicked block's top. `region` applies the wand's selection. `bounds` and `playable` apply the selection to overall/exterior-inclusive and player-area bounds. `spawn` sets the default spawn. `room <id>` adds an initially unlocked room. Object station room requirements use `set rooms spawn,workshop`; `none` clears a room list. A door's unlocked room must match the requirements of the windows/stations it enables.

`undo`/`redo` retain up to 64 metadata operations. They do not undo arbitrary terrain changes; use the building tool's own terrain history. Existing legacy wall shops and raw zombie spawns migrate into editable objects without losing weapon or door references. Legacy barricades remain editable for compatibility.

`validate` checks document references and world geometry. `save` retains an incomplete draft. `publish` requires validation, removes the author from the world, saves/unloads it, and forces a new immutable snapshot before replacing the published map document. The author then returns to the draft. Active matches retain their previous content/version; new matches receive the new snapshot without restarting the JVM. Older published documents and geometry remain available on disk. Never modify `maps/worlds/` by hand: matches verify its digest before admission.

`export <new-world-folder>` copies the last saved draft into a new server world folder with a `friesnet-map.yml` metadata document. It refuses an existing destination. Save first to include the latest terrain edits.

Closing or crashing discards unsaved changes. On startup, owned disposable worlds are removed and player backups are restored; the last completed draft snapshot remains. Published snapshots and historical versions are retained deliberately; archival retention/pruning is an operator task in this milestone.

`enter` aliases `open`; `exit` aliases `close`. `create <new-map>` starts from the training layout;
`duplicate <source-map> <new-map>` copies the source draft or published map without modifying it.
Both refuse existing destinations. `preview` toggles spectator geometry inspection. Publish, exit,
then `/zombies join <map>` for a gameplay test. `wand <material>` changes the region tool for the
current session. Commands complete map names, object kinds, selections, properties and weapons.
Editors need `zombies.editor` (operators by default); `zombies.admin` also grants access.

Drafts and terrain autosave every two minutes of server ticks, briefly unloading and reopening the
isolated world. Save explicitly before stopping. Exit and object deletion show confirmation menus;
`exit confirm` explicitly discards changes since the last completed save. Undo covers metadata only.
Menus identify unimplemented gameplay types as **INACTIVE placeholders**.
