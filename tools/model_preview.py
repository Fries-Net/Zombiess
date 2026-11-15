#!/usr/bin/env python3
"""Local-only source/export comparison and pose editing; never rebuilds the minigame."""
import argparse
import hashlib
import http.server
import json
import mimetypes
import secrets
from pathlib import Path
import resource_pack as pack


def preview_data(source, config_path):
    config = json.loads(config_path.read_text())
    provenance = json.loads((source / "provenance.json").read_text())
    assets = []
    for entry in provenance["models"]:
        model = json.loads((source / (entry["id"] + ".json")).read_text())
        ids = ["friesnet:weapon/variants/" + entry["id"]] + [k for k, v in config["aliases"].items() if v == entry["id"]]
        for asset in ids:
            exported, _, normalized = pack.export_model(model, config.get("overrides", {}).get(asset, {}))
            assets.append({"id": asset, "source": model, "exported": pack.stable(exported), "normalization": pack.stable(normalized),
                           "texture_paths": ["assets/friesnet/textures/item/guns/" + pack.digest(pack.png(t)) + ".png" for t in model["textures"]],
                           "visual_acceptance": config.get("acceptance", {}).get(asset, "pending-normal-client")})
    assets.sort(key=lambda a: ("/variants/" in a["id"], a["id"]))
    return {"assets": assets}


def serve(port=8765):
    source, output, config_path = pack.DATA / "source", pack.DATA / "build", pack.DATA / "transforms.json"
    report = pack.build(source, output, config_path)
    data = preview_data(source, config_path)
    token = secrets.token_hex(24)
    root = pack.ROOT / "tools/model-preview"
    vendor = root / "node_modules/three"
    if not (vendor / "build/three.module.js").is_file():
        raise SystemExit("Run npm ci --prefix tools/model-preview --ignore-scripts first")

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, payload, mime):
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/data.json":
                self.reply(200, pack.encoded(dict(data, token=token)), "application/json")
                return
            choices = {"/": root / "index.html", "/viewer.js": root / "viewer.js",
                       "/vendor/three.module.js": vendor / "build/three.module.js",
                       "/vendor/three.core.js": vendor / "build/three.core.js",
                       "/vendor/OrbitControls.js": vendor / "examples/jsm/controls/OrbitControls.js",
                       "/download": output / report["pack"]}
            file = choices.get(path)
            if path.startswith("/pack/assets/"):
                file = (output / path[1:]).resolve()
                if (output / "pack/assets").resolve() not in file.parents:
                    file = None
            if file is None or not file.is_file():
                self.reply(404, b"Not found", "text/plain")
                return
            self.reply(200, file.read_bytes(), mimetypes.guess_type(file.name)[0] or "application/octet-stream")

        def do_POST(self):
            nonlocal data, report
            if self.path != "/pose" or self.headers.get("X-Preview-Token") != token:
                self.reply(403, b'{"error":"Invalid local preview request"}', "application/json")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= 4096:
                    raise ValueError("Pose request is too large")
                request = json.loads(self.rfile.read(length))
                if set(request) != {"asset", "context", "pose"} or request["context"] not in pack.CONTEXTS:
                    raise ValueError("Choose a display context")
                selected = next((a for a in data["assets"] if a["id"] == request["asset"]), None)
                if selected is None or set(request["pose"]) != {"rotation", "translation", "scale"}:
                    raise ValueError("Unknown asset or incomplete pose")
                config = json.loads(config_path.read_text())
                overrides = config.setdefault("overrides", {}).setdefault(request["asset"], {})
                overrides.setdefault("display", {})[request["context"]] = request["pose"]
                pack.export_model(selected["source"], overrides)  # validate before durable replacement
                config.setdefault("acceptance", {}).pop(request["asset"], None)
                temporary = config_path.with_suffix(".json.tmp")
                temporary.write_bytes(pack.encoded(config))
                temporary.replace(config_path)
                report = pack.build(source, output, config_path)
                data = preview_data(source, config_path)
                self.reply(200, pack.encoded(report), "application/json")
            except (ValueError, KeyError, TypeError) as error:
                self.reply(400, pack.encoded({"error": str(error)}), "application/json")

    with http.server.HTTPServer(("127.0.0.1", port), Handler) as server:
        print("Model preview: http://127.0.0.1:" + str(server.server_port), flush=True)
        server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    serve(parser.parse_args().port)
