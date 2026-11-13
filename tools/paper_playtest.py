"""Run real Paper + two protocol clients, including reuse and forced-process crash recovery.

Build first; npm ci --prefix tools/paper-client; Python 3.10+, Node 22+, Java 21.
The optional test plugin is console-only and is never part of the production JAR.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from paper_smoke import ROOT, PAPER_SHA256, PAPER_URL


class Process:
    def __init__(self, arguments, cwd, log):
        self.process = subprocess.Popen(arguments, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.queue = queue.Queue()
        self.lines = []
        self.cursor = 0
        self.log = log
        def read():
            for line in self.process.stdout:
                self.queue.put(line)
            self.queue.put(None)
        threading.Thread(target=read, daemon=True).start()

    def send(self, value):
        self.process.stdin.write(value + "\n")
        self.process.stdin.flush()

    def wait(self, marker, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            while self.cursor < len(self.lines):
                line = self.lines[self.cursor]
                self.cursor += 1
                if "PLAYTEST_FAIL" in line or "event=match_tick_failed" in line or "event=map_preparation_failed" in line or "event=startup_failed" in line or "event=storage_startup_failed" in line or "event=player_restore_failed" in line:
                    raise RuntimeError(line.strip())
                if marker in line:
                    return line
            try:
                line = self.queue.get(timeout=min(1, max(.01, deadline-time.monotonic())))
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("Process exited before " + marker)
            self.lines.append(line)
            # Keep full output on disk and concise progress in the calling terminal.
            with self.log.open("a", encoding="utf-8") as stream:
                stream.write(line)
            if any(token in line for token in ("PLAYTEST_", "BOT_", "event=", "ERROR", "Exception")):
                print(line.strip(), flush=True)
        raise TimeoutError("Did not observe " + marker + "; logs: " + str(self.log))

    def close(self, graceful=None):
        if self.process.poll() is None:
            try:
                if graceful:
                    self.send(graceful)
                    self.process.wait(timeout=30)
                else:
                    self.process.kill()
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
            self.process.wait(timeout=15)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--node", default="node")
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--client-dir", type=Path, default=ROOT / "tools/paper-client")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Review https://aka.ms/MinecraftEULA and supply --accept-eula")
    plugin = ROOT / "zombies-game/build/libs/zombies-game-0.1.0.jar"
    helper = ROOT / "zombies-game/build/libs/zombies-game-0.1.0-paper-integration.jar"
    if not plugin.is_file() or not helper.is_file():
        parser.error("Run the Gradle build first")
    cache = ROOT / "run/cache"
    cache.mkdir(parents=True, exist_ok=True)
    paper = cache / "paper-1.21.11-132.jar"
    if not paper.is_file():
        with urllib.request.urlopen(urllib.request.Request(PAPER_URL, headers={"User-Agent": "FriesNetZombies-playtest"}), timeout=60) as response:
            paper.write_bytes(response.read())
    if hashlib.sha256(paper.read_bytes()).hexdigest() != PAPER_SHA256:
        raise RuntimeError("Paper checksum mismatch")
    work = ROOT / "run" / ("playtest-" + uuid.uuid4().hex[:8])
    artifacts = work / "artifacts"
    artifacts.mkdir(parents=True)
    shutil.copy2(plugin, artifacts / plugin.name)
    shutil.copy2(helper, artifacts / helper.name)
    plugin = artifacts / plugin.name
    helper = artifacts / helper.name
    data = work / "plugins/FriesNetZombies"
    (data / "maps").mkdir(parents=True)
    resources = ROOT / "zombies-game/src/main/resources"
    config = (resources / "config.yml").read_text().replace("countdown-seconds: 10", "countdown-seconds: 1").replace("private: false", "private: true").replace("pause-allowed: false", "pause-allowed: true")
    (data / "config.yml").write_text(config, encoding="utf-8")
    gameplay = (resources / "gameplay.yml").read_text().replace("final-round: 30", "final-round: 3").replace("base-count: 5", "base-count: 3").replace("spawn-ticks: 30", "spawn-ticks: 10").replace("intermission-ticks: 160", "intermission-ticks: 40").replace("bleedout-ticks: 500", "bleedout-ticks: 200")
    gameplay = gameplay.replace("base-damage: 3", "base-damage: 0.1").replace("damage-per-round: 0.15", "damage-per-round: 0")
    (data / "gameplay.yml").write_text(gameplay, encoding="utf-8")
    shutil.copy2(resources / "maps/training.yml", data / "maps/training.yml")
    (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    (work / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=" + str(port) + "\nonline-mode=false\nenforce-secure-profile=false\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
        "spawn-protection=0\nenable-rcon=false\nenable-query=false\nmax-players=4\npause-when-empty-seconds=-1\n", encoding="utf-8")
    server_args = [args.java, "-Xms512M", "-Xmx1536M", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-jar", str(paper), "--nogui", "--add-plugin=" + str(plugin), "--add-plugin=" + str(helper)]
    server = clients = None
    checks = []
    def check(name, delay=0):
        if delay:
            time.sleep(delay)
        server.send("fnztest " + name)
        server.wait("PLAYTEST_PASS " + (name.replace("accounts ", "accounts-")))
        checks.append(name)
    def bot(name, op, **extra):
        clients.send(json.dumps(dict(name=name, op=op, **extra)))
    def connect_clients(log):
        env_modules = args.client_dir / "node_modules"
        if not env_modules.is_dir():
            raise RuntimeError("Install clients using npm ci --prefix tools/paper-client")
        # Node resolves dependencies relative to the script, so the harness path is in that package.
        script = ROOT / "tools/paper-client/clients.cjs"
        if args.client_dir != script.parent:
            script = args.client_dir / "clients.cjs"
            shutil.copy2(ROOT / "tools/paper-client/clients.cjs", script)
        result = Process([args.node, str(script), str(port)], ROOT, work / log)
        result.wait("BOT_READY SurvivorOne", 60)
        result.cursor = 0
        result.wait("BOT_READY SurvivorTwo", 60)
        time.sleep(1)
        return result
    def join():
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name, "chat", text="/zombies join training")
        server.wait("event=match_playable", 120)
        time.sleep(2)
    try:
        server = Process(server_args, work, work / "paper-first.log")
        server.wait("event=instance_ready", 240)
        clients = connect_clients("clients-first.log")
        check("setup")
        join()
        check("playing")
        check("timing-start")
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name,"spam",enabled=True)
        check("timing-rates",5.5)
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name,"spam",enabled=False)
        check("timing-hold-start",2)
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name,"hold",enabled=True)
        check("timing-held",2.3)
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name,"hold",enabled=False)
        check("timing-reset",.3)
        check("timing-switch-start",2)
        bot("SurvivorOne","slot",slot=1)
        bot("SurvivorOne","spam",enabled=True)
        check("timing-switched",1.9)
        bot("SurvivorOne","spam",enabled=False)
        check("timing-reset",.3)
        check("body",2)
        check("head", 1.6)
        check("occlusion", 1.6)
        bot("SurvivorOne", "swap")
        check("reload", .2)
        check("reloaded", 2)
        check("pause")
        check("resume", 1)
        check("purchases", .3)
        check("ammo", 3)
        check("repair")
        check("repaired", 2.5)
        check("repair-cancel")
        check("repair-cancelled", 2.5)
        check("down")
        check("revived", 5.5)
        check("bleed")
        check("bled", 10.5)
        check("hud")
        check("battle")
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name, "battle", enabled=True)
        server.wait("event=match_finished outcome=WIN", 240)
        server.wait("event=match_ready", 240)
        for name in ("SurvivorOne", "SurvivorTwo"):
            bot(name, "battle", enabled=False)
        check("restored")
        check("accounts 1")
        # A second match in this same JVM must start with fresh identity, gates and barricades.
        join()
        check("fresh")
        check("defeat")
        server.wait("event=match_ready", 60)
        check("restored")
        check("accounts 2")
        # Third match: frozen scaling after a real client disconnect, then an abrupt JVM kill.
        join()
        check("fresh")
        bot("SurvivorTwo", "quit")
        clients.wait("BOT_ENDED SurvivorTwo")
        check("frozen", .5)
        time.sleep(6)  # force at least one periodic active checkpoint before SIGKILL/TerminateProcess
        server.close()
        clients.close()
        server = Process(server_args, work, work / "paper-recovery.log")
        server.wait("milestone=playable recovered=1", 240)
        clients = connect_clients("clients-recovery.log")
        check("accounts 3")
        # Original inventory and location must survive the crash, and stale worlds must be removed.
        check("restored")
        check("setup")
        join()
        check("playing")
        check("defeat")
        server.wait("event=match_ready", 60)
        check("restored")
        check("accounts 4")
        clients.close(json.dumps(dict(name="SurvivorOne",op="exit")))
        server.send("stop")
        server.wait("event=storage_closed", 60)
        server.process.wait(timeout=45)
        if server.process.returncode != 0:
            raise RuntimeError("Unexpected Paper exit code")
        report = dict(paper="1.21.11-132", checks=checks, passed=len(checks), forced_crash_recovered=True, same_jvm_reuse=True)
        (work / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Paper multiplayer playtest passed: " + str(work / "result.json"))
    finally:
        if clients:
            clients.close()
        if server:
            server.close("stop")
        print("Playtest logs: " + str(work), flush=True)


if __name__ == "__main__":
    main()
