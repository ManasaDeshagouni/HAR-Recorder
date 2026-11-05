#!/usr/bin/env python3
"""
HAR Recorder dashboard.

Does everything live_receiver.py does (receive, rebuild and verify the watch's
files) and also runs a small web server on THIS computer for the dashboard page:

    http://localhost:8765/                 the dashboard page (dashboard.html)
    /api/status                            every watch: live?, delay, battery, verified chunks...
    /api/live/<id>?seconds=30              the last seconds of sensor data, for live plots
    /api/report?day=YYYY-MM-DD             summary, gaps (with reasons), off-wrist for one day
    /api/jobs                              state of "process data" / "make figures"
    POST /api/jobs/process                 run process_data.py
    POST /api/jobs/figures                 run visualize_data.py for one day
    POST /api/open-folder                  open the data folder in Finder / Explorer
    /figures/<file>.png                    the figures made by visualize_data.py
    /api/settings, POST /api/setup         first-time setup: broker address and login

The first time (no live_config.json yet) the page shows a setup form instead.

Run it INSTEAD of live_receiver.py (not both at once: they would use the same
broker login name and keep kicking each other off).

Usage:
    python3 dashboard.py               (or double-click "HAR Recorder.command" / "HAR Recorder.bat")
"""
import argparse
import json
import logging
import os
import re
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import webbrowser
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
import paho.mqtt.client as mqtt
from flask import Flask, abort, jsonify, request, send_file

from live_receiver import CHUNK_HEAD, RECORD_SIZE, Receiver, newest_reading_wall_ms

HERE = Path(__file__).resolve().parent
CONFIG = HERE / "live_config.json"
DEFAULT_DATA_DIR = "~/HAR_Recorder/data"
PORT = 8765
LIVE_TIMEOUT_S = 10            # no data for 10 s → the watch counts as offline
RECORD = np.dtype([("code", ">i1"), ("t_ns", ">i8"), ("x", ">f4"), ("y", ">f4"), ("z", ">f4")])
PLOT_SENSORS = {1: "accelerometer", 2: "gyroscope"}
READINGS_PER_SECOND = 260      # all 7 sensors together ≈ 255 per second, plus a margin
DEVICE_ID = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
FIGURE = re.compile(r"^\d{4}-\d{2}-\d{2}_(overview|zoom|timing)\.png$")


# ---------- the receiver, extended to remember what the page needs ----------

class DashboardReceiver(Receiver):
    """Same as live_receiver.Receiver, but keeps its numbers for the web page
    instead of printing a status line."""

    def __init__(self, cfg):
        super().__init__(cfg)
        self.lock = threading.Lock()      # the MQTT thread writes, web requests read
        self.broker_connected = False
        self.last_seen = {}               # device -> time of the last message

    def on_connect(self, client, userdata, flags, reason_code, properties):
        super().on_connect(client, userdata, flags, reason_code, properties)
        self.broker_connected = not reason_code.is_failure

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        self.broker_connected = False
        super().on_disconnect(client, userdata, flags, reason_code, properties)

    def on_message(self, client, userdata, msg):
        device = msg.topic.split("/")[1]
        with self.lock:
            self.last_seen[device] = time.time()
        super().on_message(client, userdata, msg)

    def track(self, device, seq, nbytes, path):
        wall = newest_reading_wall_ms(path)
        now = time.time()
        with self.lock:
            s = self.status.setdefault(device, {"bytes": 0, "since": now, "rate": 0.0})
            s["bytes"] += nbytes
            s["seq"] = seq
            if wall is not None:
                s["delay"] = max(0.0, now - wall / 1000)
            if now - s["since"] >= 2:                  # data rate, averaged over 2 s
                s["rate"] = s["bytes"] / (now - s["since"]) / 1000
                s["bytes"], s["since"] = 0, now

    def start(self):
        """Connect in the background; paho reconnects by itself if the network drops."""
        self.client.connect_async(self.cfg["host"], int(self.cfg.get("port", 8883)), keepalive=30)
        self.client.loop_start()


# ---------- background jobs: the buttons run our existing scripts ----------

class Jobs:
    """Runs process_data.py / visualize_data.py in the background, one at a time each."""

    def __init__(self):
        self.lock = threading.Lock()
        self.state = {}                   # name -> running, ok, output, started, finished

    def start(self, name, args):
        with self.lock:
            if self.state.get(name, {}).get("running"):
                return False              # already running: don't start it twice
            self.state[name] = {"running": True, "ok": None, "output": "",
                                "started": time.time(), "finished": None}
        threading.Thread(target=self._run, args=(name, args), daemon=True).start()
        return True

    def _run(self, name, args):
        try:
            # sys.executable = the same Python that runs this dashboard
            r = subprocess.run([sys.executable, *args], cwd=HERE, capture_output=True,
                               text=True, timeout=1800)
            ok, output = r.returncode == 0, (r.stdout + r.stderr)[-6000:]
        except Exception as e:
            ok, output = False, str(e)
        with self.lock:
            self.state[name].update(running=False, ok=ok, output=output, finished=time.time())

    def snapshot(self):
        with self.lock:
            return {k: dict(v) for k, v in self.state.items()}


# ---------- reading the files the receiver and scripts write ----------

def diary_facts(path):
    """Latest battery, recording state and wrist state from a watch's diary."""
    facts = {"battery": None, "battery_at": None, "recording": None,
             "on_wrist": None, "last_event": None, "last_event_at": None}
    if not path.exists():
        return facts
    with open(path, "rb") as f:
        f.seek(max(0, path.stat().st_size - 50_000))   # the last ~50 KB is plenty
        text = f.read().decode(errors="replace")
    for line in text.splitlines():
        parts = line.split(",", 4)
        if len(parts) < 5 or not parts[0].isdigit():
            continue                                     # partial or empty line
        wall, _, _, event, detail = parts
        m = re.search(r"battery=(\d+)", detail)
        if m:
            facts["battery"], facts["battery_at"] = int(m.group(1)), int(wall)
        if event == "SERVICE_START":
            facts["recording"] = True
        elif event == "SERVICE_STOP":
            facts["recording"] = False
        elif event == "OFFBODY":
            facts["on_wrist"] = detail.startswith("on")
        facts["last_event"], facts["last_event_at"] = f"{event} {detail}".strip(), int(wall)
    return facts


def chunk_number(path):
    return int(path.name.split("_")[2].split(".")[0])     # chunk_<id>_<number>.bin


def read_tail(path, max_records):
    """(boot count, last max_records readings) of one chunk file, or None."""
    try:
        with open(path, "rb") as f:
            head = f.read(CHUNK_HEAD.size + 2)
            if len(head) < CHUNK_HEAD.size + 2:
                return None
            magic, _, _, boot, _, _, _ = CHUNK_HEAD.unpack_from(head)
            if magic != b"HARC":
                return None                              # beginning not received yet
            (id_len,) = struct.unpack_from(">H", head, CHUNK_HEAD.size)
            body_start = CHUNK_HEAD.size + 2 + id_len
            n = (path.stat().st_size - body_start) // RECORD_SIZE
            take = min(n, max_records)
            if take <= 0:
                return None
            f.seek(body_start + (n - take) * RECORD_SIZE)
            return boot, np.frombuffer(f.read(take * RECORD_SIZE), dtype=RECORD)
    except OSError:
        return None                                      # e.g. renamed .part → .bin just now


def recent_samples(raw_dir, seconds):
    """The last `seconds` of accelerometer + gyroscope data, from the newest files."""
    files = list(raw_dir.glob("chunk_*.part")) + list(raw_dir.glob("chunk_*.bin"))
    files = sorted(files, key=chunk_number)[-2:]          # newest file + the one before
    parts = [r for r in (read_tail(p, int(seconds * READINGS_PER_SECOND)) for p in files) if r]
    if not parts:
        return None
    newest_boot = parts[-1][0]
    rec = np.concatenate([r for boot, r in parts if boot == newest_boot])  # never mix reboots
    rec = rec[rec["code"] > 0]                                            # skip empty bytes
    if len(rec) == 0:
        return None
    newest = int(rec["t_ns"].max())
    out = {}
    for code, name in PLOT_SENSORS.items():
        r = rec[(rec["code"] == code) & (rec["t_ns"] >= newest - seconds * 1e9)]
        r = np.sort(r, order="t_ns")
        out[name] = {
            "t": np.round((r["t_ns"] - newest) / 1e9, 3).tolist(),    # seconds before "now"
            "x": np.round(r["x"].astype(float), 3).tolist(),
            "y": np.round(r["y"].astype(float), 3).tolist(),
            "z": np.round(r["z"].astype(float), 3).tolist(),
        }
    return out


def verified_today(raw_dir):
    today = date.today()
    return sum(1 for p in raw_dir.glob("chunk_*.bin")
               if datetime.fromtimestamp(p.stat().st_mtime).date() == today)


def records(df):
    """DataFrame → list of dicts for JSON (empty cells become null)."""
    return df.astype(object).where(df.notna(), None).to_dict("records")


def day_report(data_dir, day):
    """What process_data.py wrote, for one day: summary, gaps with reasons, off-wrist."""
    reports = data_dir / "reports"
    summary_csv = reports / "daily_summary.csv"
    if not summary_csv.exists():
        return {"days": [], "day": None, "processed_at": None}
    summary = pd.read_csv(summary_csv)
    days = sorted(summary["day"].astype(str).unique(), reverse=True)
    if day not in days:
        day = days[0] if days else None

    gaps = pd.read_csv(reports / "gaps.csv") if (reports / "gaps.csv").exists() else pd.DataFrame()
    if len(gaps):
        gaps = gaps[(gaps["sensor"] == "accelerometer") &
                    (gaps["gap_start"].astype(str).str[:10] == day)]
        for col, default in (("reason", "unknown"), ("planned", False)):
            if col not in gaps:
                gaps[col] = default                   # gaps.csv from an older process_data.py
        gaps = gaps[["gap_start", "gap_end", "gap_seconds", "reason", "planned"]]

    off = pd.read_csv(reports / "off_wrist.csv") if (reports / "off_wrist.csv").exists() else pd.DataFrame()
    if len(off):
        off = off[off["off_from"].astype(str).str[:10] == day]

    figures = sorted(p.name for p in (data_dir / "figures").glob(f"{day}_*.png")) \
        if (data_dir / "figures").exists() else []
    return {
        "days": days,
        "day": day,
        "processed_at": summary_csv.stat().st_mtime,
        "summary": records(summary[summary["day"].astype(str) == day]),
        "gaps": records(gaps) if len(gaps) else [],
        "off_wrist": records(off) if len(off) else [],
        "figures": [{"name": f, "version": int((data_dir / "figures" / f).stat().st_mtime)}
                    for f in figures],
    }


def open_folder(path):
    """Open a folder in Finder (Mac), Explorer (Windows) or the file manager (Linux)."""
    path.mkdir(parents=True, exist_ok=True)
    if sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    elif sys.platform.startswith("win"):
        os.startfile(str(path))
    else:
        subprocess.Popen(["xdg-open", str(path)])


# ---------- first-time setup: check the login, then save it ----------

def check_broker(cfg, timeout=10):
    """Try to log in to the broker. Returns None if it works, otherwise a plain-language problem."""
    answer, done = {}, threading.Event()
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                         client_id=f"har-setup-check-{os.getpid()}", clean_session=True)
    client.username_pw_set(cfg["username"], cfg["password"])
    if cfg.get("tls", True):
        client.tls_set()

    def on_connect(c, userdata, flags, reason_code, properties):
        answer["code"] = reason_code
        done.set()
    client.on_connect = on_connect

    try:
        client.connect(cfg["host"], int(cfg["port"]), keepalive=10)
    except ssl.SSLError as e:
        return ("The encrypted connection failed (" + str(getattr(e, "reason", e)) + "). On a Mac with "
                "Python from python.org: open Applications → Python 3.x → Install Certificates.command, then try again.")
    except socket.gaierror:
        return "This broker address doesn't exist. Copy it again, without https:// and without :8883."
    except OSError as e:
        return (f"Can't reach the broker ({e.strerror or e}). Check the address and your internet. "
                "Some campus networks block port 8883: try another Wi-Fi or a phone hotspot.")

    client.loop_start()
    answered = done.wait(timeout)
    client.loop_stop()
    try:
        client.disconnect()
    except Exception:
        pass
    if not answered:
        return "The broker didn't answer within 10 seconds. Check the address and port."
    code = answer["code"]
    if code.is_failure:
        if "password" in str(code).lower() or "authoriz" in str(code).lower():
            return "Wrong username or password."
        return f"The broker refused the connection: {code}."
    return None


def save_config(cfg):
    CONFIG.write_text(json.dumps(cfg, indent=2))
    try:
        os.chmod(CONFIG, 0o600)        # only you can read the password (Mac/Linux)
    except OSError:
        pass


# ---------- the web server ----------

def create_app(rx, port):
    app = Flask(__name__)
    jobs = Jobs()
    current = {"rx": rx}                  # replaced when the settings change
    switch_lock = threading.Lock()

    def receiver():
        return current["rx"]

    def data_dir():
        return receiver().data_dir

    def needs_setup():
        return jsonify(setup_needed=True, broker={"connected": False, "host": None}, watches=[],
                       server_time=time.time())

    def from_our_page():
        """Buttons may only be pressed from this dashboard, not from another website."""
        origin = request.headers.get("Origin")
        return origin in (None, f"http://localhost:{port}", f"http://127.0.0.1:{port}")

    @app.get("/")
    def index():
        return send_file(HERE / "dashboard.html")

    @app.get("/api/status")
    def status():
        rx = receiver()
        if rx is None:
            return needs_setup()
        now = time.time()
        data_dir = rx.data_dir
        raw = data_dir / "raw"
        devices = {p.name for p in raw.iterdir() if p.is_dir()} if raw.exists() else set()
        with rx.lock:
            devices |= set(rx.last_seen)
            last_seen = dict(rx.last_seen)
            stats = {d: dict(s) for d, s in rx.status.items()}
        watches = []
        for d in sorted(devices):
            seen = last_seen.get(d)
            s = stats.get(d, {})
            watches.append(dict(
                id=d,
                live=seen is not None and now - seen < LIVE_TIMEOUT_S,
                seconds_since_data=None if seen is None else round(now - seen, 1),
                delay_s=None if s.get("delay") is None else round(s["delay"], 1),
                rate_kbs=round(s.get("rate", 0.0), 1),
                chunk=s.get("seq"),
                verified_today=verified_today(raw / d),
                **diary_facts(data_dir / "events" / f"{d}_events.log"),
            ))
        return jsonify(setup_needed=False,
                       broker={"connected": rx.broker_connected, "host": rx.cfg["host"]},
                       watches=watches, server_time=now)

    @app.get("/api/live/<device>")
    def live(device):
        if not DEVICE_ID.match(device):
            abort(400)                                    # only plain watch IDs, no paths
        if receiver() is None:
            return jsonify(device=device, seconds=0, sensors={})
        seconds = min(max(request.args.get("seconds", 30, type=float), 1), 120)
        samples = recent_samples(data_dir() / "raw" / device, seconds)
        return jsonify(device=device, seconds=seconds, sensors=samples or {})

    @app.get("/api/report")
    def report():
        if receiver() is None:
            return jsonify(days=[], day=None, processed_at=None)
        return jsonify(day_report(data_dir(), request.args.get("day", "")))

    @app.get("/api/jobs")
    def job_state():
        return jsonify(jobs.snapshot())

    @app.post("/api/jobs/process")
    def run_process():
        if not from_our_page() or receiver() is None:
            abort(403)
        started = jobs.start("process", ["process_data.py", "--data", str(data_dir())])
        return jsonify(started=started)

    @app.post("/api/jobs/figures")
    def run_figures():
        if not from_our_page() or receiver() is None:
            abort(403)
        day = (request.get_json(silent=True) or {}).get("day", "")
        args = ["visualize_data.py", "--data", str(data_dir())]
        if DAY.match(day):
            args += ["--day", day]
        return jsonify(started=jobs.start("figures", args))

    @app.post("/api/open-folder")
    def open_data_folder():
        if not from_our_page() or receiver() is None:
            abort(403)
        open_folder(data_dir())
        return jsonify(ok=True)

    @app.get("/figures/<name>")
    def figure(name):
        if not FIGURE.match(name):
            abort(404)                                    # only our figure files, no paths
        if receiver() is None:
            abort(404)
        path = data_dir() / "figures" / name
        if not path.exists():
            abort(404)
        return send_file(path)

    @app.get("/api/settings")
    def settings():
        rx = receiver()
        cfg = rx.cfg if rx else {}
        return jsonify(host=cfg.get("host", ""), port=cfg.get("port", 8883), tls=cfg.get("tls", True),
                       username=cfg.get("username", ""), has_password=bool(cfg.get("password")),
                       data_dir=cfg.get("data_dir", DEFAULT_DATA_DIR))   # the password is never sent back

    @app.post("/api/setup")
    def setup():
        if not from_our_page():
            abort(403)
        form = request.get_json(silent=True) or {}
        old = receiver().cfg if receiver() else {}
        cfg = {
            "host": str(form.get("host", "")).strip().removeprefix("https://").removeprefix("ssl://")
                       .removeprefix("mqtts://").split(":")[0].strip("/"),
            "port": int(form.get("port") or 8883),
            "tls": bool(form.get("tls", True)),
            "username": str(form.get("username", "")).strip(),
            "password": str(form.get("password", "")) or old.get("password", ""),   # empty = keep old
            "data_dir": str(form.get("data_dir", "")).strip() or DEFAULT_DATA_DIR,
        }
        if not cfg["host"] or not cfg["username"] or not cfg["password"]:
            return jsonify(ok=False, error="Fill in the broker address, username and password.")
        problem = check_broker(cfg)
        if problem:
            return jsonify(ok=False, error=problem)

        save_config(cfg)
        with switch_lock:                               # stop the old receiver, start the new one
            old_rx = receiver()
            if old_rx is not None:
                old_rx.client.loop_stop()
                old_rx.client.disconnect()
            new_rx = DashboardReceiver(cfg)
            new_rx.start()
            current["rx"] = new_rx
        return jsonify(ok=True)

    return app


def already_running(port):
    """Is something (probably an earlier dashboard) already using this port?"""
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--no-browser", action="store_true", help="don't open the browser")
    args = parser.parse_args()
    url = f"http://localhost:{args.port}"

    if already_running(args.port):
        print(f"The dashboard is already running. Opening {url}")
        webbrowser.open(url)
        return

    rx = None
    if CONFIG.exists():
        rx = DashboardReceiver(json.loads(CONFIG.read_text()))
        rx.start()
    else:
        print("First start: fill in the setup form in your browser.")

    logging.getLogger("werkzeug").setLevel(logging.ERROR)   # don't print every page request
    print(f"\nDashboard running at {url}  (keep this window open, close it to stop)\n")
    if not args.no_browser:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    create_app(rx, args.port).run(host="127.0.0.1", port=args.port, threaded=True)


if __name__ == "__main__":
    main()