# Resource pack V3 review

The original `FriesnetGuns(notall).zip` contains 66 Blockbench Java models. Its
SHA-256 is `bf98b18a649ca65e949e39babc9abe5ce58a5c2db90c02082cc87beee8555627`.
The checked-in source bundle preserves geometry, pivots, indexed embedded PNGs,
face UVs and authored display data; private source paths were removed. The original
archive and imported Minecraft worlds are never modified. No replacement artwork
is generated.

## Build and inspect

```sh
python -m unittest discover -s tools -p test_resource_pack.py
python tools/resource_pack.py build
npm ci --prefix tools/model-preview --ignore-scripts --no-audit --no-fund
python tools/model_preview.py
```

Open `http://127.0.0.1:8765`. Compare original/exported geometry, select a display
context, and adjust rotation, translation or scale. **Save candidate** writes only
the chosen asset/context override in `resource-pack/transforms.json` and rebuilds
the pack. It clears that asset's acceptance entry. No plugin rebuild is required.
The server binds only to loopback, rejects unauthenticated pose writes, and loads
its pinned renderer locally rather than from a CDN.

For browser regression checks, install Chromium once with
`cd tools/model-preview && npx playwright install chromium`, then run
`node tools/model-preview/check.mjs` from the repository root. `PREVIEW_CHROME`
may name an existing Chrome executable. Checks render five priority guns in eight
contexts, reject invalid writes, and save 15 review screenshots under
`resource-pack/build/review/`. These fixtures approximate held-item placement;
they do not reproduce Minecraft arm poses, lighting, FOV options or animations.

The output ZIP, build report and asset manifest are in `resource-pack/build/`.
CI builds on Windows and Linux, checks byte reproducibility, verifies every
reference, and uploads both packs plus browser screenshots. Source JSON uses LF
on all platforms. ZIP headers, timestamps, ordering and computed float precision
are fixed. Pack format is 75.0 for Minecraft Java 1.21.11.

## Source correctness and stable identities

Face texture numbers address the texture **array**, not the texture's `id` field.
For Java block projects UVs use project resolution, which can differ from PNG or
per-texture dimensions. PNG bytes are unchanged and CRC-checked. Rotated cubes
retain their pivots and 1.21.11 multi-axis rotations. Unsupported meshes/group
transforms fail conversion rather than silently losing geometry. Uniform
normalization places element coordinates inside Minecraft's legal bounds and
preserves proportions. Candidate hand poses use the original grip anchor and
cancel the native third-person item rotation; their visual approval is pending.

`source/ids.json` pins every original filename to a unique variant ID. All 66
variants are available as `friesnet:weapon/variants/<id>`. Existing runtime IDs
for AK-47, Glock-18, AWP, MAC-10 and Ray Gun are aliases. The AWP alias uses the
original AWM source, and Glock-18 uses the original Glock-17 source; those are
the supplied models, not renamed historical source files. MAC-10 defaults to
the neutral `mac.png` source, with coloured variants retained separately.
No Revolver source has been identified; use its vanilla fallback. Prior V1/V2
pack ZIPs were not found, so their unknown variant mappings have not been claimed
as compatible. Supply those manifests before replacing any deployed legacy IDs.

## Required Minecraft acceptance

This is `v3-preview.1`, not an approved production pack. Copy the ZIP or loose
`build/pack` folder to the client's resourcepacks folder and enable it. Reload
with F3+T after saving a pose. Give one preview item outside a live match:

```mcfunction
/give @s minecraft:iron_hoe[minecraft:item_model="friesnet:weapon/ak47"]
```

Repeat with `glock18`, `awp`, `mac10` and `raygun`. For each:

- Capture first-person right/left hand at normal and wide FOV while walking,
  sprinting, firing and reloading. Check sightline, barrel direction and clipping.
- Capture F5 front/back while idle, moving and crouching. Check the grip sits in
  the hand, and the barrel does not point vertically or cut through the body.
- Check inventory/GUI, dropped ground item, item frame/fixed presentation and
  mystery-box ItemDisplay previews at normal game scale.
- Compare every skin with its original source texture. Dark gun textures are
  intentional in some sources; missing black/purple checkerboards are failures.
- Check the client log for model/atlas warnings and test with the pack declined.

Record screenshots, client version, FOV and the tested pack SHA-256 before adding
an acceptance entry. A parsing test or browser screenshot alone cannot approve a
Minecraft pose. Keep automatic custom-model activation disabled until approved.
