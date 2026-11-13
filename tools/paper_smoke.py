"""Boot the packaged plugin on pinned Paper, exercise console commands, and verify shutdown.

Requires Python 3.10+, Java 21, network access on first run, and --accept-eula.
Writes an isolated server directory beneath run/; retains logs for diagnosis.
"""
import argparse
import hashlib
import pathlib
import queue
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
PAPER_SHA256 = "5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"
PAPER_URL = f"https://fill-data.papermc.io/v1/objects/{PAPER_SHA256}/paper-1.21.11-132.jar"


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java", help="Path to a Java 21 executable")
    parser.add_argument("--accept-eula", action="store_true", help="Accept https://aka.ms/MinecraftEULA for this test server")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Review the Minecraft EULA and supply --accept-eula before booting a test server")
    plugin = ROOT / "zombies-game/build/libs/zombies-game-0.1.0.jar"
    if not plugin.is_file():
        parser.error("Build zombies-game first")
    cache = ROOT / "run/cache"
    cache.mkdir(parents=True, exist_ok=True)
    paper = cache / "paper-1.21.11-132.jar"
    if not paper.is_file() or hashlib.sha256(paper.read_bytes()).hexdigest() != PAPER_SHA256:
        request = urllib.request.Request(PAPER_URL, headers={"User-Agent": "FriesNetZombies-development (https://github.com/Fries-Net/Zombiess)"})
        with urllib.request.urlopen(request, timeout=60) as response:
            paper.write_bytes(response.read())
        if hashlib.sha256(paper.read_bytes()).hexdigest() != PAPER_SHA256:
            raise RuntimeError("Paper checksum mismatch")
    work = ROOT / "run" / f"smoke-{uuid.uuid4().hex[:8]}"
    work.mkdir()
    (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (work / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\nlevel-type=minecraft:flat\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
        "generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n"
        "enable-rcon=false\nenable-query=false\nmax-players=4\n", encoding="utf-8")
    process = subprocess.Popen([args.java, "-Xms512M", "-Xmx1536M", "-jar", str(paper),
                                "--nogui", f"--add-plugin={plugin}"], cwd=work,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace", bufsize=1)
    lines = queue.Queue()

    def read_output():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    captured = []
    cursor = 0

    def wait_for(marker, timeout=120):
        nonlocal cursor
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            while cursor < len(captured):
                line = captured[cursor]
                cursor += 1
                if marker in line:
                    return
            try:
                line = lines.get(timeout=min(1, max(0.01, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError(f"Paper exited before: {marker}")
            captured.append(line)
            print(line, end="", flush=True)
            if "event=storage_startup_failed" in line or "event=startup_failed" in line:
                raise RuntimeError("Plugin initialization failed; inspect the cause types in the server log")
        raise TimeoutError(f"Paper did not emit: {marker}")

    def command(value):
        process.stdin.write(value + "\n")
        process.stdin.flush()

    try:
        wait_for("Done (", 240)
        # Storage readiness can be logged before or after Paper's Done line.
        cursor = 0
        wait_for("event=instance_ready", 60)
        cursor = len(captured)
        for value, response in [("zombies status", "foundation ready"), ("zombies validate", "Configuration is valid"),
                                ("zombies reload", "Configuration reloaded"), ("zombies health", "Database: healthy")]:
            command(value)
            wait_for(response, 30)
        command("stop")
        wait_for("event=storage_closed", 45)
        process.wait(timeout=45)
        if process.returncode != 0:
            raise RuntimeError(f"Paper exit code {process.returncode}")
        if any("event=startup_failed" in line or "event=storage_startup_failed" in line for line in captured):
            raise RuntimeError("Plugin startup failed")
        print(f"Paper smoke passed. Logs: {work / 'logs/latest.log'}")
    finally:
        if process.poll() is None:
            try:
                command("stop")
                process.wait(timeout=20)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=10)
        (work / "smoke-output.log").write_text("".join(captured), encoding="utf-8")


if __name__ == "__main__":
    main()
