import copy
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("pack", Path(__file__).with_name("resource_pack.py"))
pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack)


class ResourcePackTest(unittest.TestCase):
    def setUp(self):
        self.source = json.loads((pack.DATA / "source/glock_17.json").read_text())

    def test_all_66_sources_retain_original_texture_and_uv_assignments(self):
        manifest = json.loads((pack.DATA / "source/provenance.json").read_text())
        self.assertEqual(66, len(manifest["models"]))
        for entry in manifest["models"]:
            source = json.loads((pack.DATA / "source" / (entry["id"] + ".json")).read_text())
            model, textures, _ = pack.export_model(source)
            for original, exported in zip((e for e in source["elements"] if e.get("export") is not False), model["elements"]):
                for side, face in original["faces"].items():
                    slot = face.get("texture")
                    if slot is None or face.get("enabled") is False:
                        self.assertNotIn(side, exported["faces"])
                        continue
                    exported_face = exported["faces"][side]
                    self.assertEqual("#t" + str(slot), exported_face["texture"])
                    self.assertEqual(pack.png(source["textures"][slot]), textures[model["textures"]["t" + str(slot)]])
                    for i, value in enumerate(face["uv"]):
                        dimension = source["resolution"]["width" if i % 2 == 0 else "height"]
                        self.assertAlmostEqual(value / dimension, exported_face["uv"][i] / 16)
                    self.assertEqual(face.get("rotation", 0), exported_face.get("rotation", 0))

    def test_nonsequential_texture_ids_and_java_project_uv_space(self):
        self.assertEqual("6", self.source["textures"][4]["id"])
        model, _, _ = pack.export_model(self.source)
        self.assertEqual("#t4", model["elements"][0]["faces"]["north"]["texture"])
        # This face uses a 16px PNG, while the Java project UV space is 32x32.
        for original, exported in zip(self.source["elements"], model["elements"]):
            for side, face in original["faces"].items():
                if face.get("texture") == 3:
                    self.assertEqual([n / 2 for n in face["uv"]], exported["faces"][side]["uv"])
                    return
        self.fail("Expected a non-default texture fixture")

    def test_multi_axis_pivots_survive_uniform_normalization(self):
        source = copy.deepcopy(self.source)
        source["elements"] = source["elements"][:1]
        source["elements"][0]["rotation"] = [17, 65, -112]
        source["elements"][0]["origin"] = [1.5, 3.25, 8.5]
        model, _, transform = pack.export_model(source)
        cube = model["elements"][0]
        normalized = dict(cube, origin=cube["rotation"]["origin"], rotation=[cube["rotation"][axis] for axis in "xyz"])
        for a, b in zip(pack.corners(source["elements"][0]), pack.corners(normalized)):
            expected = [8 + (a[i] - transform["anchor"][i]) * transform["scale"] for i in range(3)]
            for left, right in zip(expected, b):
                self.assertAlmostEqual(left, right, places=8)

    def test_build_is_reproducible_and_all_asset_references_resolve(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a = pack.build(output=root / "a")
            b = pack.build(output=root / "b")
            self.assertEqual(a["sha256"], b["sha256"])
            self.assertEqual(71, a["assets"])
            with zipfile.ZipFile(root / "a" / a["pack"]) as archive:
                contents = {name: archive.read(name) for name in archive.namelist()}
                pack.validate_pack(contents)
                for name in list(contents):
                    if name.endswith(".png"):
                        del contents[name]
                        break
                self.assertRaises(ValueError, pack.validate_pack, contents)

    def test_missing_textures_and_lossy_geometry_fail_closed(self):
        source = copy.deepcopy(self.source)
        source["elements"][0]["faces"]["north"]["texture"] = 99
        self.assertRaises(ValueError, pack.export_model, source)
        source = copy.deepcopy(self.source)
        source["elements"][0]["type"] = "mesh"
        self.assertRaises(ValueError, pack.export_model, source)

    def test_all_display_contexts_and_variant_ids_remain_explicit(self):
        ids = json.loads((pack.DATA / "source/ids.json").read_text())
        self.assertEqual(66, len(set(ids.values())))
        self.assertNotEqual(ids["spas 12.bbmodel"], ids["spas_12.bbmodel"])
        model, _, _ = pack.export_model(self.source)
        self.assertEqual(set(pack.CONTEXTS), set(model["display"]))
        self.assertEqual(model["display"]["firstperson_righthand"], model["display"]["firstperson_lefthand"])


if __name__ == "__main__":
    unittest.main()
