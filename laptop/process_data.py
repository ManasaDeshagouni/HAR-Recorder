#!/usr/bin/env python3
"""
Turns the watch's binary chunk files into normal tables, one per sensor per day,
plus reports about gaps and off-wrist time.

Usage:
    python3 process_data.py                    # uses ~/HAR_Recorder/data
    python3 process_data.py --data <folder>

Reads:   <data>/raw/<device>/chunk_*.bin  (+ .sha256)
         <data>/events/*.log              the watch's diary (from adb or live streaming)
Writes:  <data>/processed/<YYYY-MM-DD>/<sensor>.csv
         <data>/reports/daily_summary.csv
         <data>/reports/gaps.csv            every gap, with the reason from the watch's diary
         <data>/reports/off_wrist.csv
         <data>/reports/problems.txt
"""
import argparse
import hashlib
import struct
from pathlib import Path

import numpy as np
import pandas as pd

# ---------- the file format (must match ChunkWriter.kt) ----------

HEADER = struct.Struct(">4siqiqqi")   # magic, version, seq, boot, startElapsedNs, startWallMs, periodUs
RECORD = np.dtype([("code", ">i1"), ("t_ns", ">i8"), ("x", ">f4"), ("y", ">f4"), ("z", ">f4")])

# code: (name, value columns, expected milliseconds between readings or None for on-change)
SENSORS = {
    1: ("accelerometer",       ["x", "y", "z"],    20.0),
    2: ("gyroscope",           ["x", "y", "z"],    20.0),
    3: ("magnetometer",        ["x", "y", "z"],    20.0),
    4: ("barometer",           ["pressure_hpa"],  200.0),
    5: ("offbody",             ["on_wrist"],       None),
    6: ("gravity",             ["x", "y", "z"],    20.0),
    7: ("linear_acceleration", ["x", "y", "z"],    20.0),
}
GAP_FACTOR = 5   # a pause longer than 5 expected intervals counts as a gap


def sha256_of_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_chunk(path):
    """Returns (header dict, numpy records). Ignores a half-written last record."""
    data = Path(path).read_bytes()
    magic, version, seq, boot, start_el_ns, start_wall_ms, _ = HEADER.unpack_from(data, 0)
    if magic != b"HARC" or version != 1:
        raise ValueError("not a HAR chunk file")
    pos = HEADER.size
    (id_len,) = struct.unpack_from(">H", data, pos)
    device = data[pos + 2: pos + 2 + id_len].decode()
    body = data[pos + 2 + id_len:]
    n = len(body) // RECORD.itemsize
    records = np.frombuffer(body[: n * RECORD.itemsize], dtype=RECORD)
    header = dict(seq=seq, boot=boot, start_el_ns=start_el_ns, start_wall_ms=start_wall_ms,
                  device=device, torn_bytes=len(body) - n * RECORD.itemsize)
    return header, records


def local_timezone():
    try:
        import tzlocal
        return str(tzlocal.get_localzone())
    except Exception:
        return "UTC"


# ---------- step 1: load every verified chunk ----------

def load_all(raw_dir, problems):
    frames, seqs = [], {}
    for path in sorted(raw_dir.glob("*/*.bin")):
        sidecar = path.with_name(path.name + ".sha256")
        if not sidecar.exists() or sha256_of_file(path) != sidecar.read_text().split()[0].lower():
            problems.append(f"CHECKSUM FAILED: {path.name} (skipped)")
            continue
        try:
            h, rec = read_chunk(path)
        except Exception as e:
            problems.append(f"UNREADABLE: {path.name}: {e}")
            continue
        seqs.setdefault(h["device"], []).append(h["seq"])
        if h["torn_bytes"]:
            problems.append(f"TORN TAIL: {path.name} lost its last partial reading (normal after a crash)")
        if len(rec) == 0:
            continue
        df = pd.DataFrame({
            "code": rec["code"].astype(np.int8),
            "t_ns": rec["t_ns"].astype(np.int64),
            "x": rec["x"].astype(np.float32),
            "y": rec["y"].astype(np.float32),
            "z": rec["z"].astype(np.float32),
        })
        df["device"] = h["device"]
        df["boot"] = h["boot"]
        df["seq"] = h["seq"]
        # The header's clock pair converts "ns since boot" into real time
        df["wall_ms"] = h["start_wall_ms"] + (df["t_ns"] - h["start_el_ns"]) // 1_000_000
        frames.append(df)

    for device, s in seqs.items():
        s = sorted(set(s))
        missing = sorted(set(range(s[0], s[-1] + 1)) - set(s))
        if missing:
            problems.append(f"MISSING FILES: device {device} file numbers {missing} are not on this computer")

    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


# ---------- step 2: the watch's diary ----------

def load_diary(data_dir):
    """All diary lines (from adb pulls and live streaming), merged, sorted by time."""
    lines = set()
    for path in (data_dir / "events").glob("*.log"):
        lines.update(l for l in path.read_text(errors="replace").splitlines() if l.count(",") >= 4)
    rows = []
    for line in lines:
        wall, _, boot, event, detail = line.split(",", 4)
        try:
            rows.append((int(wall), int(boot), event, detail))
        except ValueError:
            continue                                  # a half-written line, skip it
    diary = pd.DataFrame(rows, columns=["wall_ms", "boot", "event", "detail"])
    return diary.sort_values("wall_ms").reset_index(drop=True)


def explain_gap(diary, start_ms, end_ms, reboot):
    """Returns (reason, planned). planned = True means it was not data loss."""
    if diary.empty:
        return "unknown (no diary)", False
    margin = 5_000                                    # diary and data clocks can differ slightly
    during = diary[(diary["wall_ms"] >= start_ms - margin) & (diary["wall_ms"] <= end_ms + margin)]
    user_stop = (during["event"] == "SERVICE_STOP") & during["detail"].str.contains("user_requested=true")
    if user_stop.any():
        return "stopped by user", True
    if reboot:
        before = diary[diary["wall_ms"] <= start_ms + margin]["detail"]
        levels = before.str.extract(r"battery=(\d+)")[0].dropna()
        if len(levels) and int(levels.iloc[-1]) <= 5:
            return "battery ran out", False
        return "watch rebooted", False
    if (during["event"] == "SERVICE_START").any():
        return "app restarted (reinstall or crash)", False
    if (during["event"] == "SERVICE_STOP").any():
        return "stopped by the system", False
    return "unknown", False


# ---------- step 3: find gaps ----------

def find_gaps(df, diary):
    rows = []
    for (device, code), g in df.groupby(["device", "code"]):
        name, _, period_ms = SENSORS.get(code, (f"sensor{code}", [], None))
        if period_ms is None:
            continue                          # on-change sensors have no regular rhythm
        g = g.sort_values(["wall_ms", "t_ns"])
        new_boot = g["boot"].ne(g["boot"].shift()).to_numpy()
        # Within one boot use the precise sensor clock; across a reboot only wall time works
        dt = np.where(new_boot, g["wall_ms"].diff().to_numpy(), g["t_ns"].diff().to_numpy() / 1e6)
        for i in np.where(dt > GAP_FACTOR * period_ms)[0]:
            reason, planned = explain_gap(diary, int(g["wall_ms"].iloc[i - 1]),
                                          int(g["wall_ms"].iloc[i]), bool(new_boot[i]))
            rows.append(dict(device=device, sensor=name,
                             gap_start=g["local_time"].iloc[i - 1],
                             gap_end=g["local_time"].iloc[i],
                             gap_seconds=round(float(dt[i]) / 1000, 3),
                             reboot=bool(new_boot[i]),
                             reason=reason, planned=planned))
    return pd.DataFrame(rows, columns=["device", "sensor", "gap_start", "gap_end",
                                       "gap_seconds", "reboot", "reason", "planned"])


def find_off_wrist(df):
    rows = []
    ob = df[df["code"] == 5].sort_values("wall_ms")
    off_since = None
    for _, r in ob.iterrows():
        on = r["x"] > 0.5
        if not on and off_since is None:
            off_since = r["local_time"]
        elif on and off_since is not None:
            rows.append(dict(off_from=off_since, back_on=r["local_time"],
                             minutes=round((r["local_time"] - off_since).total_seconds() / 60, 1)))
            off_since = None
    if off_since is not None:
        rows.append(dict(off_from=off_since, back_on=None, minutes=None))
    return pd.DataFrame(rows, columns=["off_from", "back_on", "minutes"])


# ---------- main ----------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="~/HAR_Recorder/data")
    parser.add_argument("--tz", default=local_timezone(), help="time zone, e.g. America/Los_Angeles")
    args = parser.parse_args()

    data = Path(args.data).expanduser()
    reports = data / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    problems = []

    print("Reading files...")
    df = load_all(data / "raw", problems)
    if df.empty:
        print("No data found in", data / "raw")
        return

    before = len(df)
    df = df.drop_duplicates(["device", "boot", "code", "t_ns"])
    if len(df) < before:
        problems.append(f"DUPLICATES: removed {before - len(df)} repeated readings")

    df["local_time"] = pd.to_datetime(df["wall_ms"], unit="ms", utc=True).dt.tz_convert(args.tz)
    df["day"] = df["local_time"].dt.strftime("%Y-%m-%d")

    diary = load_diary(data)
    gaps = find_gaps(df, diary)
    gaps.to_csv(reports / "gaps.csv", index=False)
    off_wrist = find_off_wrist(df)
    off_wrist.to_csv(reports / "off_wrist.csv", index=False)

    print("Writing tables...")
    summary = []
    for (day, code), s in df.groupby(["day", "code"]):
        name, value_cols, period_ms = SENSORS.get(code, (f"sensor{code}", ["x", "y", "z"], None))
        s = s.sort_values(["wall_ms", "t_ns"])

        table = pd.DataFrame({"local_time": s["local_time"], "t_ns": s["t_ns"]})
        for col, src in zip(value_cols, ["x", "y", "z"]):
            table[col] = s[src].to_numpy()
        table["boot"] = s["boot"].to_numpy()
        table["file"] = s["seq"].to_numpy()

        out_dir = data / "processed" / day
        out_dir.mkdir(parents=True, exist_ok=True)
        table.to_csv(out_dir / f"{name}.csv", index=False)

        row = dict(day=day, sensor=name, readings=len(s),
                   first=s["local_time"].iloc[0].strftime("%H:%M:%S"),
                   last=s["local_time"].iloc[-1].strftime("%H:%M:%S"))
        if period_ms is not None:
            dts = s.groupby("boot")["t_ns"].diff().dropna() / 1e6
            normal = dts[dts <= GAP_FACTOR * period_ms]
            row["measured_hz"] = round(1000 / normal.mean(), 2) if len(normal) else None
            day_gaps = gaps[(gaps["sensor"] == name) &
                            (gaps["gap_start"].astype(str).str[:10] == day)] if len(gaps) else gaps
            lost = day_gaps[~day_gaps["planned"].astype(bool)] if len(day_gaps) else day_gaps
            row["gaps"] = len(lost)                        # planned stops are not counted as gaps
            row["gap_minutes"] = round(lost["gap_seconds"].sum() / 60, 1) if len(lost) else 0.0
        summary.append(row)

    summary = pd.DataFrame(summary)
    if "gaps" in summary:
        summary["gaps"] = summary["gaps"].astype("Int64")
    summary.to_csv(reports / "daily_summary.csv", index=False)
    (reports / "problems.txt").write_text("\n".join(problems) + "\n" if problems else "No problems found\n")

    pd.set_option("display.width", 200)
    print("\n=== Summary ===")
    print(summary.to_string(index=False))
    acc_gaps = gaps[gaps["sensor"] == "accelerometer"]
    if len(acc_gaps):
        print("\n=== Gaps (accelerometer; other sensors match) ===")
        for _, g in acc_gaps.iterrows():
            minutes = g["gap_seconds"] / 60
            length = f"{minutes:8.1f} min" if minutes >= 1 else f"{g['gap_seconds']:8.1f} s  "
            flag = "  (planned)" if g["planned"] else ""
            print(f"  {g['gap_start']:%Y-%m-%d %H:%M:%S} → {g['gap_end']:%m-%d %H:%M:%S}"
                  f"  {length}  {g['reason']}{flag}")
    else:
        print("\nNo gaps")
    print("(full list: reports/gaps.csv)")
    if len(off_wrist):
        print("\nOff wrist:")
        print(off_wrist.to_string(index=False))
    print("\nProblems:" if problems else "\nNo problems found")
    for p in problems:
        print("  " + p)
    print(f"\nTables in: {data / 'processed'}")


if __name__ == "__main__":
    main()