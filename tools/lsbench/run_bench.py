#!/usr/bin/env python3
"""Runs the vanilla / Legacy Silicon / OptiFine comparison through Prism (dev tool).

Each run: set renderDistance in the instance's options.txt, launch it via Prism (it auto-joins the local test
server), put the joining player in spectator at a fixed spot via RCON, and wait for the LsBench agent to append
its result and quit the game.

usage: run_bench.py <testserver dir> <rd,rd,...> <instance,instance,...>
"""
import os, re, socket, struct, subprocess, sys, time

PRISM_APP = os.environ.get("PRISM_APP", "/Applications/Prism Launcher.app")
PRISM = PRISM_APP + "/Contents/MacOS/prismlauncher"
INSTANCES = os.path.expanduser("~/Library/Application Support/PrismLauncher/instances")
RESULTS = "/tmp/lsbench/results.txt"
SPOT = "-54.5 92 -2.5 0 20"


def rcon(cmd, port, password):
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    def send(rid, kind, body):
        data = struct.pack("<ii", rid, kind) + body.encode() + b"\x00\x00"
        s.sendall(struct.pack("<i", len(data)) + data)
        n = struct.unpack("<i", s.recv(4))[0]
        return s.recv(n)
    send(1, 3, password)
    reply = send(2, 2, cmd)
    s.close()
    return reply[8:-2].decode(errors="replace")


def set_rd(instance, rd):
    p = os.path.join(INSTANCES, instance, ".minecraft", "options.txt")
    lines = [f"renderDistance:{rd}" if l.startswith("renderDistance:") else l for l in open(p).read().splitlines()]
    open(p, "w").write("\n".join(lines) + "\n")


def result_count():
    return len(open(RESULTS).read().splitlines()) if os.path.exists(RESULTS) else 0


def game_running(instance):
    out = subprocess.run(["pgrep", "-f", f"instances/{instance}/"], capture_output=True, text=True).stdout
    return bool(out.strip())


def main():
    server_dir, rds, names = sys.argv[1], sys.argv[2].split(","), sys.argv[3].split(",")
    props = dict(l.split("=", 1) for l in open(os.path.join(server_dir, "server.properties")).read().splitlines() if "=" in l)
    port, pw = int(props["rcon.port"]), props["rcon.password"]
    log = os.path.join(server_dir, "server.log")
    for rd in rds:
        for inst in names:
            set_rd(inst, rd)
            log_pos = os.path.getsize(log)
            before = result_count()
            print(f"== {inst} rd{rd}: launching", flush=True)
            subprocess.Popen([PRISM, "--launch", inst], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            player, deadline = None, time.time() + 600
            while player is None and time.time() < deadline:
                time.sleep(2)
                with open(log, errors="replace") as f:
                    f.seek(log_pos)
                    m = re.search(r"\]: (\w+) joined the game", f.read())
                    if m:
                        player = m.group(1)
            if player is None:
                print("   never joined; skipping", flush=True)
                continue
            time.sleep(1)
            print("  ", rcon(f"gamemode 3 {player}", port, pw), "|", rcon(f"tp {player} {SPOT}", port, pw), flush=True)
            deadline = time.time() + 420
            while result_count() == before and time.time() < deadline:
                time.sleep(3)
            print("  ", open(RESULTS).read().splitlines()[-1] if result_count() > before else "NO RESULT (timeout)", flush=True)
            for _ in range(60):
                if not game_running(inst):
                    break
                time.sleep(2)
            time.sleep(5)


if __name__ == "__main__":
    main()
