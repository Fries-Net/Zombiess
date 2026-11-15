#!/usr/bin/env python3
"""Deterministic Blockbench 4.x Java-model importer and Minecraft 1.21.11 pack exporter."""
import argparse
import base64
import copy
import hashlib
import itertools
import json
import math
import re
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "resource-pack"
CONTEXTS = ("firstperson_righthand", "firstperson_lefthand", "thirdperson_righthand",
            "thirdperson_lefthand", "gui", "ground", "fixed")
FACES = {"north", "south", "east", "west", "up", "down"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n").encode()


def stable(value):
    """Bound computed-float serialization so Windows/Linux trigonometry does not change pack hashes."""
    if isinstance(value, float):
        rounded = round(value, 8)
        return int(rounded) if rounded.is_integer() else rounded
    if isinstance(value, dict):
        return {k: stable(v) for k, v in value.items()}
    if isinstance(value, list):
        return [stable(v) for v in value]
    return value


def vector(value, count=3):
    if not isinstance(value, list) or len(value) != count or any(
            not isinstance(n, (int, float)) or isinstance(n, bool) or not math.isfinite(n) for n in value):
        raise ValueError("Expected a finite vector")
    return value


def rotate(point, angles, inverse=False, display=False):
    p = list(point)
    order = [2, 1, 0] if display else [0, 1, 2]
    for axis in (reversed(order) if inverse else order):
        radians = math.radians(angles[axis]) * (-1 if inverse else 1)
        c, s = math.cos(radians), math.sin(radians)
        a, b = ((1, 2), (2, 0), (0, 1))[axis]
        p[a], p[b] = c * p[a] - s * p[b], s * p[a] + c * p[b]
    return p


def png(texture):
    source = texture.get("source", "")
    if not source.startswith("data:image/png;base64,"):
        raise ValueError("Only original embedded PNGs are accepted; no external texture downloads")
    data = base64.b64decode(source.split(",", 1)[1], validate=True)
    if len(data) > 4 * 1024 * 1024 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("Invalid or oversized PNG")
    width, height = struct.unpack(">II", data[16:24])
    if not 1 <= width <= 4096 or not 1 <= height <= 4096:
        raise ValueError("Invalid PNG dimensions")
    return data


def validate_source(model):
    if model["meta"]["model_format"] != "java_block" or not str(model["meta"]["format_version"]).startswith("4."):
        raise ValueError("Only audited Blockbench 4.x Java block models are supported")
    width, height = model["resolution"]["width"], model["resolution"]["height"]
    if not 1 <= width <= 4096 or not 1 <= height <= 4096:
        raise ValueError("Invalid project UV dimensions")
    if not 1 <= len(model["textures"]) <= 64 or not 1 <= len(model["elements"]) <= 2048:
        raise ValueError("Model resource bounds exceeded")
    for t in model["textures"]:
        png(t)
    def group(node):
        if isinstance(node, dict):
            if any(node.get("rotation", [0, 0, 0])) or node.get("export") is False:
                raise ValueError("Transformed/excluded groups require explicit baking; refusing silent geometry changes")
            for child in node.get("children", []):
                group(child)
    for node in model.get("outliner", []):
        group(node)
    for e in model["elements"]:
        if e.get("type", "cube") != "cube" or e.get("inflate", 0) or e.get("rescale", False):
            raise ValueError("Unsupported element feature; refusing a lossy conversion")
        for key in ("from", "to", "origin"):
            vector(e.get(key, [0, 0, 0]))
        if any(a > b for a, b in zip(e["from"], e["to"])):
            raise ValueError("Inverted cube")
        vector(e.get("rotation", [0, 0, 0]))
        for side, face in e["faces"].items():
            if side not in FACES:
                raise ValueError("Unknown cube face")
            index = face.get("texture")
            if index is None or face.get("enabled") is False:
                continue
            if type(index) is not int or not 0 <= index < len(model["textures"]):
                raise ValueError("Face texture must resolve by array index, never texture.id")
            uv = vector(face["uv"], 4)
            if any(v < -1e-6 or v > (width if i % 2 == 0 else height) + 1e-6 for i, v in enumerate(uv)):
                raise ValueError("UV outside the original Java project UV space")
            if face.get("rotation", 0) not in (0, 90, 180, 270):
                raise ValueError("Invalid face UV rotation")


def import_archive(source, destination):
    """Read source without extraction or mutation; strip private paths from the checked-in bundle."""
    if source.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Source archive is too large")
    destination.mkdir(parents=True, exist_ok=True)
    ids_path = destination / "ids.json"
    ids = json.loads(ids_path.read_text()) if ids_path.exists() else {}
    used = set(ids.values())
    manifest = {"source_archive": source.name, "archive_sha256": digest(source.read_bytes()), "models": []}
    with zipfile.ZipFile(source) as archive:
        files = sorted(n for n in archive.namelist() if n.lower().endswith(".bbmodel"))
        if not 1 <= len(files) <= 256 or sum(archive.getinfo(n).file_size for n in files) > 32 * 1024 * 1024:
            raise ValueError("Source archive resource limits exceeded")
        filenames = [Path(n).name for n in files]
        if len(set(filenames)) != len(files):
            raise ValueError("Ambiguous source filenames")
        for name, filename in zip(files, filenames):
            original = archive.read(name)
            model = json.loads(original.decode("utf-8-sig"))
            validate_source(model)
            if filename not in ids:
                slug = re.sub(r"[^a-z0-9]+", "_", Path(filename).stem.lower()).strip("_")
                if slug in used:
                    slug += "_" + digest(filename.encode())[:8]
                ids[filename] = slug
                used.add(slug)
            slug = ids[filename]
            if not re.fullmatch(r"[a-z0-9_]{1,80}", slug):
                raise ValueError("Invalid stable variant ID")
            # Keep geometry, pivots, faces, indexed textures and authored poses unchanged.
            clean = {k: model[k] for k in ("meta", "name", "resolution", "elements", "outliner", "textures", "display")}
            clean["source_filename"] = filename
            clean["source_sha256"] = digest(original)
            clean["textures"] = [{k: t[k] for k in ("name", "id", "uuid", "width", "height", "uv_width", "uv_height", "source") if k in t} for t in model["textures"]]
            (destination / (slug + ".json")).write_bytes(encoded(clean))
            manifest["models"].append({"id": slug, "source": filename, "source_sha256": digest(original),
                                       "bundle_sha256": digest(encoded(clean)), "elements": len(clean["elements"])})
    ids_path.write_bytes(encoded(ids))
    (destination / "provenance.json").write_bytes(encoded(manifest))
    return manifest


def corners(element):
    origin = element.get("origin", [0, 0, 0])
    angles = element.get("rotation", [0, 0, 0])
    for p in itertools.product(*zip(element["from"], element["to"])):
        rotated = rotate([p[i] - origin[i] for i in range(3)], angles)
        yield [rotated[i] + origin[i] for i in range(3)]


def normalization(model, override):
    authored = model.get("display", {}).get("thirdperson_righthand", {})
    rotation = vector(authored.get("rotation", [0, 0, 0]))
    translation = vector(authored.get("translation", [0, 0, 0]))
    scale = vector(authored.get("scale", [1, 1, 1]))
    if any(abs(s) < 1e-6 for s in scale):
        raise ValueError("Cannot infer hand anchor from a zero authored scale")
    inverse = rotate(translation, rotation, inverse=True, display=True)
    anchor = vector(override.get("anchor", [8 - inverse[i] / scale[i] for i in range(3)]))
    coordinates = [p for e in model["elements"] for p in (e["from"], e["to"], e.get("origin", [0, 0, 0]))]
    distance = max(abs(p[i] - anchor[i]) for p in coordinates for i in range(3))
    factor = min(1, 23.999 / max(distance, 1))
    return anchor, factor, rotation, scale


def frame(points, rotation, span, lift=0):
    rotated = [rotate([p[i] - 8 for i in range(3)], rotation, display=True) for p in points]
    lo = [min(p[i] for p in rotated) for i in range(3)]
    hi = [max(p[i] for p in rotated) for i in range(3)]
    factor = min(4, span / max(max(hi[i] - lo[i] for i in range(3)), 1e-6))
    return {"rotation": rotation, "translation": [-(lo[i] + hi[i]) / 2 * factor + (lift if i == 1 else 0) for i in range(3)], "scale": [factor] * 3}


def export_model(source, override=None):
    validate_source(source)
    override = override or {}
    anchor, factor, hand_rotation, hand_scale = normalization(source, override)
    normalize = lambda p: [8 + (p[i] - anchor[i]) * factor for i in range(3)]
    result = {"ambientocclusion": False, "gui_light": "front", "textures": {}, "elements": []}
    pngs = {}
    for index, texture in enumerate(source["textures"]):
        data = png(texture)
        key = "friesnet:item/guns/" + digest(data)
        result["textures"]["t" + str(index)] = key
        pngs[key] = data
    result["textures"]["particle"] = "#t0"
    points = []
    for element in source["elements"]:
        if element.get("export") is False:
            continue
        e = {"from": normalize(element["from"]), "to": normalize(element["to"]), "faces": {}}
        rotation = element.get("rotation", [0, 0, 0])
        if any(rotation):
            e["rotation"] = dict(zip(("x", "y", "z"), rotation), origin=normalize(element.get("origin", [0, 0, 0])))
        if "shade" in element:
            e["shade"] = bool(element["shade"])
        for side, face in element["faces"].items():
            index = face.get("texture")
            if index is None or face.get("enabled") is False:
                continue
            # Java block format uses Project resolution, not PNG size or per-texture UV fields.
            f = {"uv": [value * 16 / source["resolution"]["width" if i % 2 == 0 else "height"] for i, value in enumerate(face["uv"])], "texture": "#t" + str(index)}
            for key in ("rotation", "tintindex"):
                if key in face:
                    f[key] = face[key]
            # Cullface is intentionally omitted for held items; world-neighbour culling is invalid.
            e["faces"][side] = f
        result["elements"].append(e)
        points.extend(normalize(p) for p in corners(element))
    extent = max(max(p[i] for p in points) - min(p[i] for p in points) for i in range(3)) / factor
    display = {"gui": frame(points, [15, -60, 0], 11), "ground": frame(points, [0, 0, 90], 7, 2),
               "fixed": frame(points, [0, -90, 0], 12)}
    for prefix, span in (("firstperson", 16), ("thirdperson", 24)):
        fit = min(1, span / max(extent, 1))
        pose = {"rotation": hand_rotation, "translation": [0, 0, 0], "scale": [s * fit / factor for s in hand_scale]}
        display[prefix + "_righthand"] = copy.deepcopy(pose)
        display[prefix + "_lefthand"] = copy.deepcopy(pose)
    for context, pose in override.get("display", {}).items():
        if context not in CONTEXTS:
            raise ValueError("Unknown display context")
        display[context].update(pose)
    for context, pose in display.items():
        for key in ("rotation", "translation", "scale"):
            vector(pose[key])
        if any(abs(s) > 4 for s in pose["scale"]) or any(abs(t) > 80 for t in pose["translation"]):
            raise ValueError("Display transform exceeds Minecraft bounds")
    result["display"] = display
    return result, pngs, {"anchor": anchor, "scale": factor}


def build(source=DATA / "source", output=DATA / "build", config_path=DATA / "transforms.json"):
    config = json.loads(config_path.read_text())
    provenance = json.loads((source / "provenance.json").read_text())
    entries = {}
    assets = []
    ids = {m["id"] for m in provenance["models"]}
    aliases = config["aliases"]
    if any(slug not in ids for slug in aliases.values()):
        raise ValueError("Alias references a missing source variant")
    for meta in provenance["models"]:
        slug = meta["id"]
        data = (source / (slug + ".json")).read_bytes()
        if digest(data) != meta["bundle_sha256"]:
            raise ValueError("Imported source bundle changed; re-import intentionally")
        model = json.loads(data)
        asset_ids = ["friesnet:weapon/variants/" + slug] + sorted(k for k, v in aliases.items() if v == slug)
        for asset in asset_ids:
            if not re.fullmatch(r"friesnet:[a-z0-9_/.-]+", asset):
                raise ValueError("Invalid asset identifier")
            exported, textures, normalized = export_model(model, config.get("overrides", {}).get(asset, {}))
            exported, normalized = stable(exported), stable(normalized)
            path = asset.split(":", 1)[1]
            mesh = "item/" + path
            entries["assets/friesnet/models/" + mesh + ".json"] = encoded(exported)
            entries["assets/friesnet/items/" + path + ".json"] = encoded({"model": {"type": "minecraft:model", "model": "friesnet:" + mesh}, "swap_animation_scale": 1})
            for key, png_data in textures.items():
                namespace, texture_path = key.split(":", 1)
                entries["assets/" + namespace + "/textures/" + texture_path + ".png"] = png_data
            assets.append({"id": asset, "source": model["source_filename"], "source_sha256": model["source_sha256"],
                           "normalization": normalized, "model_sha256": digest(encoded(exported)),
                           "texture_slots": [{"slot": i, "original_id": t["id"], "name": t["name"], "sha256": digest(png(t))} for i, t in enumerate(model["textures"])],
                           "visual_acceptance": config.get("acceptance", {}).get(asset, "pending-normal-client")})
    entries["pack.mcmeta"] = encoded({"pack": {"description": "FriesNet Zombies V3 preview • original indexed textures", "min_format": [75, 0], "max_format": [75, 0]}})
    manifest = {"version": config["version"], "minecraft": "1.21.11", "source_archive_sha256": provenance["archive_sha256"],
                "assets": assets, "files": {p: digest(data) for p, data in sorted(entries.items())}}
    entries["asset-manifest.json"] = encoded(manifest)
    validate_pack(entries)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / ("friesnet-zombies-" + config["version"] + ".zip")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as pack:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            pack.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    report = {"pack": archive.name, "sha1": hashlib.sha1(archive.read_bytes()).hexdigest(), "sha256": digest(archive.read_bytes()),
              "assets": len(assets), "models": len(provenance["models"]), "version": config["version"]}
    (output / "build-report.json").write_bytes(encoded(report))
    (output / "asset-manifest.json").write_bytes(encoded(manifest))
    # Loose tree for immediate F3+T reload and a standalone graphical viewer.
    for name, data in entries.items():
        target = output / "pack" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return report


def validate_pack(entries):
    for path, data in entries.items():
        if "/models/" not in path or not path.endswith(".json"):
            continue
        m = json.loads(data)
        for e in m["elements"]:
            if any(v < -16 - 1e-6 or v > 32 + 1e-6 for key in ("from", "to") for v in e[key]):
                raise ValueError("Illegal Minecraft element bounds")
            for face in e["faces"].values():
                key = face["texture"][1:]
                if key not in m["textures"]:
                    raise ValueError("Missing texture slot")
        for key, reference in m["textures"].items():
            if reference.startswith("#"):
                if reference[1:] not in m["textures"]:
                    raise ValueError("Missing indirect texture slot")
                continue
            namespace, name = reference.split(":", 1)
            if "assets/" + namespace + "/textures/" + name + ".png" not in entries or not name.startswith("item/"):
                raise ValueError("Missing texture or mixed item/block atlas")
    for path, data in entries.items():
        if "/items/" in path and path.endswith(".json"):
            reference = json.loads(data)["model"]["model"]
            namespace, name = reference.split(":", 1)
            if "assets/" + namespace + "/models/" + name + ".json" not in entries:
                raise ValueError("Missing item model")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    imp = actions.add_parser("import")
    imp.add_argument("archive", type=Path)
    imp.add_argument("--output", type=Path, default=DATA / "source")
    export = actions.add_parser("build")
    export.add_argument("--source", type=Path, default=DATA / "source")
    export.add_argument("--output", type=Path, default=DATA / "build")
    export.add_argument("--transforms", type=Path, default=DATA / "transforms.json")
    args = parser.parse_args()
    result = import_archive(args.archive, args.output) if args.action == "import" else build(args.source, args.output, args.transforms)
    print(json.dumps(result if args.action == "build" else {"models": len(result["models"]), "source_sha256": result["archive_sha256"]}, indent=2))


if __name__ == "__main__":
    main()
