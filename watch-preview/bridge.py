"""
BLRando watch simulation bridge (PC side, debug builds only).

- Serves C:\\Android\\BLRando over http://localhost:8765 (open /watch-preview/preview.html).
- Walks the phone along its reference track: one fake GPS fix every WALK_PERIOD seconds (adb broadcast).
- Fetches the phone's last watch frame (files/watch/frame.json) and serves it at /frame.
- Serves the phone's last heartbeat (files/watch/beat.json) at /beat.
- /msg?b=<base64 JSON> : any watch message (hello, zoom, screen, cmd...) -> phone (DEBUG_MSG).
- /zoom?d=1 or -1 : simulated bezel -> zoom on the phone.

Run:  python -I watch-preview\\bridge.py [--no-walk] [--start N] [--step K] [--bezel SECONDS]
"""
import argparse
import http.server
import json
import math
import os
import re
import subprocess
import threading
import time
import urllib.parse

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "fr.amandio.blrando"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = 8765
WALK_PERIOD = 2.0

latest = {"frame": None, "beat": None}
lock = threading.Lock()


def adb(*args, timeout=20):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def broadcast(action, *extras):
    adb("shell", "am", "broadcast", "-a", f"{PKG}.{action}", "-p", PKG, *extras)


def track_points():
    out = adb("shell", f"run-as {PKG} cat files/reference.gpx").stdout.decode("utf-8", "replace")
    pts = re.findall(r'<(?:trkpt|rtept) lat="([-\d.]+)" lon="([-\d.]+)"', out)
    return [(float(a), float(b)) for a, b in pts]


def dist_m(a, b):
    r = 6371000
    dl = math.radians(b[0] - a[0])
    dn = math.radians(b[1] - a[1])
    x = math.sin(dl / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dn / 2) ** 2
    return 2 * r * math.asin(math.sqrt(x))


def walker(start, step_m):
    pts = track_points()
    if len(pts) < 2:
        print("No reference track on the phone: load one in BLRando first.")
        return
    print(f"Walking along {len(pts)} track points, ~{step_m:.0f} m per step, from point {start}")
    i = start % len(pts)
    pos = pts[i]
    while True:
        # Advance about step_m along the track
        walked = 0.0
        while walked < step_m:
            nxt = pts[(i + 1) % len(pts)]
            walked += dist_m(pos, nxt)
            pos = nxt
            i = (i + 1) % len(pts)
        broadcast("DEBUG_FIX", "--ef", "lat", f"{pos[0]:.6f}", "--ef", "lon", f"{pos[1]:.6f}")
        time.sleep(WALK_PERIOD)


def bezel(period):
    """Turns the simulated bezel one detent every `period` s: zoom in 2, out 2, in 2..."""
    pattern = [1, 1, -1, -1, -1, -1, 1, 1]  # around the current zoom
    i = 0
    while True:
        time.sleep(period)
        d = pattern[i % len(pattern)]
        print(f"bezel {'+' if d > 0 else '-'}1")
        broadcast("DEBUG_ZOOM", "--ei", "delta", str(d))
        i += 1


def poller():
    while True:
        for kind in ("frame", "beat"):
            try:
                raw = adb("shell", f"run-as {PKG} cat files/watch/{kind}.json").stdout
                if raw and raw.startswith(b"{"):
                    with lock:
                        latest[kind] = raw
            except Exception as e:  # adb hiccup: keep going
                print("poll:", e)
        time.sleep(1.0)


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, *a):
        pass

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path in ("/w", "/w/"):
            # Short address to type on the watch browser
            self.send_response(302)
            self.send_header("Location", "/watch/index.html")
            self.end_headers()
        elif url.path in ("/frame", "/beat"):
            with lock:
                body = latest[url.path[1:]] or b"{}"
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        elif url.path == "/msg":
            # Watch -> phone message, base64 JSON, delivered to WatchLink by the debug receiver
            b = urllib.parse.parse_qs(url.query).get("b", [""])[0]
            if b:
                print("watch ->", __import__("base64").b64decode(b).decode("utf-8", "replace"))
                threading.Thread(target=broadcast, args=("DEBUG_MSG", "--es", "b64", b), daemon=True).start()
            self.send_response(204)
            self.end_headers()
        elif url.path == "/zoom":
            d = urllib.parse.parse_qs(url.query).get("d", ["0"])[0]
            threading.Thread(target=broadcast, args=("DEBUG_ZOOM", "--ei", "delta", str(int(d))), daemon=True).start()
            self.send_response(204)
            self.end_headers()
        else:
            super().do_GET()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-walk", action="store_true", help="do not move the phone")
    ap.add_argument("--start", type=int, default=0, help="start at this track point")
    ap.add_argument("--step", type=float, default=12.0, help="meters per fake fix")
    ap.add_argument("--lan", action="store_true", help="listen on the local network (watch browser), not only this PC")
    ap.add_argument("--bezel", type=float, default=0, help="simulate a bezel detent every N seconds (0 = off)")
    args = ap.parse_args()
    threading.Thread(target=poller, daemon=True).start()
    if args.bezel > 0:
        threading.Thread(target=bezel, args=(args.bezel,), daemon=True).start()
    if not args.no_walk:
        threading.Thread(target=walker, args=(args.start, args.step), daemon=True).start()
    host = "0.0.0.0" if args.lan else "127.0.0.1"
    print(f"Preview: http://localhost:{PORT}/watch-preview/preview.html")
    if args.lan:
        print(f"Watch browser: http://<this PC's IP>:{PORT}/w")
    http.server.ThreadingHTTPServer((host, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
