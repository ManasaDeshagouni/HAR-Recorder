#!/usr/bin/env python3
"""
Copies finished data files from the watch to this computer.

For each file it:
  1. copies it over adb,
  2. checks its SHA-256 checksum on THIS computer's disk,
  3. only then deletes it from the watch.

Anything that fails stays on the watch and is retried next time.
Works on macOS, Windows and Linux.

Usage:
    python3 pull_from_watch.py                     # copy, verify, delete from watch
    python3 pull_from_watch.py --keep-on-watch     # copy and verify only, never delete
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.example.myapplication"
REMOTE_DIR = "files/har/chunks"


# ---------- helpers ----------

def find_adb():
    """Find the adb program on any operating system."""
    found = shutil.which("adb")
    if found:
        return found
    for candidate in ("~/Library/Android/sdk/platform-tools/adb",            # macOS
                      "~/Android/Sdk/platform-tools/adb",                     # Linux
                      "~/AppData/Local/Android/Sdk/platform-tools/adb.exe"):  # Windows
        p = Path(candidate).expanduser()
        if p.exists():
            return str(p)
    sys.exit("adb not found. Install Android Studio's platform-tools.")


def sha256_of_file(path):
    """Compute the SHA-256 fingerprint of a file, reading it in pieces."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_safely(path, data):
    """Write to a temp file, force it onto disk, then rename (all-or-nothing)."""
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


class Watch:
    """Small wrapper around the adb commands we need."""

    def __init__(self, adb):
        self.adb = adb

    def run(self, args, binary=False):
        result = subprocess.run([self.adb] + args, capture_output=True)
        if result.returncode != 0:
            message = result.stderr.decode(errors="replace").strip()
            raise RuntimeError(message or "adb command failed")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def list_files(self):
        out = self.run(["shell", "run-as", PACKAGE, "ls", REMOTE_DIR])
        return [line.strip() for line in out.splitlines() if line.strip()]

    def read_file(self, relative_path):
        # exec-out passes raw bytes through untouched (safe for binary files)
        return self.run(["exec-out", "run-as", PACKAGE, "cat", relative_path], binary=True)

    def delete_files(self, *relative_paths):
        self.run(["shell", "run-as", PACKAGE, "rm", "-f", *relative_paths])


# ---------- main ----------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="~/HAR_Recorder/data", help="where to store data on this computer")
    parser.add_argument("--keep-on-watch", action="store_true", help="never delete anything on the watch")
    args = parser.parse_args()

    out_dir = Path(args.out).expanduser()
    watch = Watch(find_adb())

    try:
        names = watch.list_files()
    except RuntimeError as e:
        sys.exit(f"Could not reach the watch: {e}\nIs it connected? Check with: adb devices")

    # Only finished files that already have their checksum (.part = still being written)
    finished = sorted(n for n in names if n.endswith(".bin") and n + ".sha256" in names)
    print(f"{len(finished)} finished file(s) on the watch")

    copied = failed = 0
    for name in finished:
        device_id = name.split("_")[1]                 # chunk_<device>_<seq>.bin
        dest_dir = out_dir / "raw" / device_id
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / name
        dest_sha = dest_dir / (name + ".sha256")

        try:
            sha_bytes = watch.read_file(f"{REMOTE_DIR}/{name}.sha256")
            expected = sha_bytes.decode().split()[0].lower()

            # Copy only if we don't already have a perfect copy
            if not (dest.exists() and sha256_of_file(dest) == expected):
                write_safely(dest, watch.read_file(f"{REMOTE_DIR}/{name}"))
                write_safely(dest_sha, sha_bytes)

            # THE CONFIRMATION: re-read from this computer's disk and compare
            if sha256_of_file(dest) != expected:
                raise RuntimeError("checksum mismatch after copy")

            if not args.keep_on_watch:
                watch.delete_files(f"{REMOTE_DIR}/{name}", f"{REMOTE_DIR}/{name}.sha256")

            copied += 1
            print(f"  OK    {name}")
        except Exception as e:
            failed += 1
            print(f"  FAIL  {name}: {e}  (kept on watch, will retry next time)")

    # The diary only ever grows on the watch, so we just keep a fresh copy
    try:
        (out_dir / "events").mkdir(parents=True, exist_ok=True)
        write_safely(out_dir / "events" / "events.log", watch.read_file("files/har/events.log"))
    except Exception as e:
        print(f"Could not copy events.log: {e}")

    action = "copied and verified" if args.keep_on_watch else "copied, verified and removed from watch"
    print(f"\nDone: {copied} {action}, {failed} failed")
    print(f"Data folder: {out_dir}")


if __name__ == "__main__":
    main()