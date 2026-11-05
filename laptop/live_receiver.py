#!/usr/bin/env python3
"""
Receives the watch's data LIVE through an MQTT broker (works from any network).

The watch sends pieces of its 5-minute chunk files as they are written. This
script writes each piece at the same position in the same file here, so the
files on this computer become exact copies of the files on the watch.
When a chunk is finished, its SHA-256 is checked; only then does this script
tell the watch it may delete its copy. Missing or damaged chunks, and missing
diary lines, are requested again automatically.

Usage:
    python3 live_receiver.py                 # reads live_config.json next to this script
    python3 live_receiver.py --config other.json
"""
import argparse
import hashlib
import json
import os
import struct
import sys
import time
import zlib
from pathlib import Path

import paho.mqtt.client as mqtt

TYPE_DATA, TYPE_COMPLETE, TYPE_EVENTS = 1, 2, 3
MSG_HEAD = struct.Struct(">bqq")                 # type, chunk number, byte offset
CHUNK_HEAD = struct.Struct(">4siqiqqi")          # same header as ChunkWriter.kt
RECORD_SIZE = 21


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def write_at(path, offset, data):
    """Write bytes at an exact position (creates the file if needed). Repeats are harmless."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "r+b" if path.exists() else "w+b") as f:
        f.seek(offset)
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def newest_reading_wall_ms(path):
    """Real time (ms) of the last complete reading in a chunk file, or None."""
    try:
        with open(path, "rb") as f:
            head = f.read(CHUNK_HEAD.size + 2)
            if len(head) < CHUNK_HEAD.size + 2:
                return None
            magic, _, _, _, start_ns, start_wall_ms, _ = CHUNK_HEAD.unpack_from(head)
            if magic != b"HARC":
                return None          # header not received yet (we joined mid-file)
            (id_len,) = struct.unpack_from(">H", head, CHUNK_HEAD.size)
            body_start = CHUNK_HEAD.size + 2 + id_len
            n = (path.stat().st_size - body_start) // RECORD_SIZE
            if n <= 0:
                return None
            f.seek(body_start + (n - 1) * RECORD_SIZE + 1)
            (t_ns,) = struct.unpack(">q", f.read(8))
            return start_wall_ms + (t_ns - start_ns) / 1e6
    except (OSError, struct.error):
        return None


class Receiver:
    def __init__(self, cfg):
        self.cfg = cfg
        self.data_dir = Path(cfg.get("data_dir", "~/HAR_Recorder/data")).expanduser()
        self.status = {}          # device -> dict for the status line
        self.last_print = 0.0
        self.events_asked = {}    # device -> when we last asked for missing diary lines
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id=cfg.get("client_id", "har-laptop"),
                                  clean_session=False)          # broker keeps our messages while we're offline
        self.client.username_pw_set(cfg["username"], cfg["password"])
        if cfg.get("tls", True):
            self.client.tls_set()
        self.client.on_connect = self.on_connect
        self.client.on_disconnect = self.on_disconnect
        self.client.on_message = self.on_message

    # ---------- connection ----------

    def on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            print(f"Connection refused: {reason_code}")
            return
        client.subscribe("har/+/data", qos=1)
        print(f"Connected to {self.cfg['host']}, waiting for watches...")

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        print(f"\nDisconnected ({reason_code}), reconnecting...")

    def run(self):
        self.client.connect(self.cfg["host"], int(self.cfg.get("port", 8883)), keepalive=30)
        self.client.loop_forever(retry_first_connection=True)

    # ---------- messages ----------

    def on_message(self, client, userdata, msg):
        device = msg.topic.split("/")[1]
        try:
            kind, seq, offset = MSG_HEAD.unpack_from(msg.payload)
            body = msg.payload[MSG_HEAD.size:]
            raw = self.data_dir / "raw" / device
            name = f"chunk_{device}_{seq:08d}"
            if kind == TYPE_DATA:
                if (raw / f"{name}.bin").exists():
                    return                                     # already complete and verified
                data = zlib.decompress(body)
                write_at(raw / f"{name}.part", offset, data)
                self.track(device, seq, len(data), raw / f"{name}.part")
            elif kind == TYPE_COMPLETE:
                total, sha = struct.unpack(">q64s", body)
                self.complete(device, seq, raw, name, total, sha.decode())
            elif kind == TYPE_EVENTS:
                path = self.data_dir / "events" / f"{device}_events.log"
                have = path.stat().st_size if path.exists() else 0
                if offset > have:
                    # Earlier diary lines never arrived: ask for them instead of leaving a hole
                    now = time.time()
                    if now - self.events_asked.get(device, 0) > 10:
                        self.events_asked[device] = now
                        self.publish(device, "resend_events", have)
                        print(f"\n  ! diary incomplete: asking the watch to resend from byte {have}")
                    return
                write_at(path, offset, zlib.decompress(body))
        except Exception as e:
            print(f"\nBad message on {msg.topic}: {e}")

    def complete(self, device, seq, raw, name, total, sha):
        part, done = raw / f"{name}.part", raw / f"{name}.bin"
        if done.exists():
            self.publish(device, "ack", seq)                  # already have it; just confirm again
            return
        if part.exists() and part.stat().st_size == total and sha256_of(part) == sha:
            (raw / f"{name}.bin.sha256").write_text(f"{sha}  {name}.bin\n")
            os.replace(part, done)
            self.publish(device, "ack", seq)
            print(f"\n  ✓ chunk {seq} complete and verified")
            self.request_stale(device, raw, seq)
        else:
            have = part.stat().st_size if part.exists() else 0
            print(f"\n  ! chunk {seq} damaged or incomplete ({have}/{total} bytes): asking the watch to resend")
            if part.exists():
                part.unlink()
            self.publish(device, "resend", seq)

    def request_stale(self, device, raw, finished_seq):
        """Older chunks that never finished, or never arrived at all → ask for them again.
        (The watch ignores requests for chunks it no longer has, e.g. unused numbers.)"""
        for seq in range(max(0, finished_seq - 20), finished_seq):
            name = f"chunk_{device}_{seq:08d}"
            if (raw / f"{name}.bin").exists():
                continue
            part = raw / f"{name}.part"
            if part.exists():
                part.unlink()
            self.publish(device, "resend", seq)

    def publish(self, device, what, number):
        self.client.publish(f"har/{device}/{what}", str(number), qos=1)

    # ---------- status line ----------

    def track(self, device, seq, nbytes, path):
        s = self.status.setdefault(device, {"bytes": 0, "since": time.time()})
        s["bytes"] += nbytes
        s["seq"] = seq
        wall = newest_reading_wall_ms(path)
        if wall is not None:
            s["delay"] = max(0.0, time.time() - wall / 1000)
        now = time.time()
        if now - self.last_print >= 1:
            self.last_print = now
            parts = []
            for d, st in self.status.items():
                rate = st["bytes"] / max(now - st["since"], 1e-6) / 1000
                delay = st.get("delay")
                delay_txt = f"{delay:5.1f} s behind" if delay is not None else "  ?"
                parts.append(f"{d} | chunk {st['seq']} | {rate:5.1f} KB/s | {delay_txt}")
                st["bytes"], st["since"] = 0, now
            sys.stdout.write("\r" + "   ".join(parts) + "   ")
            sys.stdout.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default=str(Path(__file__).with_name("live_config.json")))
    args = parser.parse_args()
    cfg = json.loads(Path(args.config).read_text())
    try:
        Receiver(cfg).run()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()