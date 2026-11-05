#!/usr/bin/env python3
"""
Draws the collected watch data and checks that it is physically sensible.

Usage:
    python3 visualize_data.py                          # latest day, zoom on first minute
    python3 visualize_data.py --start 19:10 --seconds 30
    python3 visualize_data.py --day 2026-10-15 --data ~/HAR_Recorder/data

Reads:   <data>/processed/<day>/*.csv   and   <data>/reports/gaps.csv, off_wrist.csv
Writes:  <data>/figures/<day>_overview.png    whole day at a glance
         <data>/figures/<day>_zoom.png        every sensor, a few seconds in detail
         <data>/figures/<day>_timing.png      how regular the sampling is
         <data>/reports/<day>_sanity_checks.txt
"""
import argparse
from pathlib import Path

import matplotlib
matplotlib.use("Agg")                      # draw straight to files, no window needed
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

XYZ = ["x", "y", "z"]
AXIS_COLORS = {"x": "#d62728", "y": "#2ca02c", "z": "#1f77b4"}   # red, green, blue

# name: (label, unit)
MOTION = {
    "accelerometer":       ("Accelerometer", "m/s²"),
    "gyroscope":           ("Gyroscope", "rad/s"),
    "magnetometer":        ("Magnetometer", "µT"),
    "gravity":             ("Gravity", "m/s²"),
    "linear_acceleration": ("Linear acceleration", "m/s²"),
}


def load(day_dir, name, columns):
    path = day_dir / f"{name}.csv"
    if not path.exists():
        return None
    df = pd.read_csv(path, usecols=["local_time", "t_ns"] + columns)
    df["local_time"] = pd.to_datetime(df["local_time"], format="ISO8601")
    return df


def magnitude(df):
    return np.sqrt(df["x"] ** 2 + df["y"] ** 2 + df["z"] ** 2)


def shade(ax, gaps, off_wrist):
    """Grey = no data (gap), orange = watch off the wrist."""
    for _, g in gaps.iterrows():
        ax.axvspan(g["gap_start"], g["gap_end"], color="grey", alpha=0.25, lw=0)
    for _, o in off_wrist.iterrows():
        if pd.notna(o["back_on"]):
            ax.axvspan(o["off_from"], o["back_on"], color="orange", alpha=0.25, lw=0)


# ---------- figure 1: the whole day ----------

def plot_overview(s, gaps, off_wrist, title, out):
    acc = s["accelerometer"].set_index("local_time")
    # Movement intensity: how much |acceleration| varies within each second
    intensity = magnitude(acc).resample("1s").std()

    rows = [("Movement intensity\n(std of |accel| per s)", intensity, "m/s²")]
    if s.get("barometer") is not None:
        baro = s["barometer"].set_index("local_time")["pressure_hpa"].resample("1s").mean()
        rows.append(("Air pressure", baro, "hPa"))
    if s.get("magnetometer") is not None:
        mag = magnitude(s["magnetometer"].set_index("local_time")).resample("1s").mean()
        rows.append(("|Magnetic field|", mag, "µT"))

    fig, axes = plt.subplots(len(rows), 1, figsize=(14, 2.6 * len(rows)), sharex=True)
    axes = np.atleast_1d(axes)
    for ax, (label, series, unit) in zip(axes, rows):
        ax.plot(series.index, series.values, lw=0.6, color="#1f77b4")
        ax.set_ylabel(f"{label}\n[{unit}]", fontsize=9)
        ax.ticklabel_format(axis="y", useOffset=False)   # show 1009.3, not "+1.009e3"
        shade(ax, gaps, off_wrist)
        ax.grid(alpha=0.3)
    axes[0].set_title(f"{title} — grey: no data (gap), orange: watch off wrist")
    axes[-1].set_xlabel("Local time")
    fig.autofmt_xdate()
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


# ---------- figure 2: a few seconds of every sensor ----------

def plot_zoom(s, start, seconds, out):
    end = start + pd.Timedelta(seconds=seconds)
    names = [n for n in MOTION if s.get(n) is not None]
    has_baro = s.get("barometer") is not None
    fig, axes = plt.subplots(len(names) + has_baro, 1,
                             figsize=(14, 2.2 * (len(names) + has_baro)), sharex=True)
    axes = np.atleast_1d(axes)
    for ax, name in zip(axes, names):
        df = s[name]
        w = df[(df["local_time"] >= start) & (df["local_time"] < end)]
        for axis in XYZ:
            ax.plot(w["local_time"], w[axis], lw=0.8, color=AXIS_COLORS[axis], label=axis)
        label, unit = MOTION[name]
        ax.set_ylabel(f"{label}\n[{unit}]", fontsize=9)
        ax.grid(alpha=0.3)
        ax.legend(loc="upper right", fontsize=8, ncol=3)
    if has_baro:
        df = s["barometer"]
        w = df[(df["local_time"] >= start) & (df["local_time"] < end)]
        axes[-1].plot(w["local_time"], w["pressure_hpa"], lw=1, color="#9467bd")
        axes[-1].set_ylabel("Pressure\n[hPa]", fontsize=9)
        axes[-1].ticklabel_format(axis="y", useOffset=False)
        axes[-1].grid(alpha=0.3)
    axes[0].set_title(f"All sensors, {start:%H:%M:%S} + {seconds} s  (x = red, y = green, z = blue)")
    axes[-1].set_xlabel("Local time")
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


# ---------- figure 3: sampling regularity ----------

def plot_timing(acc, out):
    dt = acc["t_ns"].diff().dropna() / 1e6
    dt = dt[(dt > 0) & (dt < 100)]                       # leave real gaps out
    fig, ax = plt.subplots(figsize=(8, 3.5))
    ax.hist(dt, bins=np.arange(10, 30.25, 0.25), color="#1f77b4")
    ax.axvline(20, color="red", ls="--", lw=1, label="target 20 ms (50 Hz)")
    ax.set_xlabel("Time between accelerometer readings [ms]")
    ax.set_ylabel("Count")
    ax.set_title("Sampling regularity (one tall spike near 20 ms = good)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


# ---------- sanity checks ----------

def still_mask(acc):
    """Seconds where the wrist barely moves (|accel| varies < 0.05 m/s²)."""
    m = magnitude(acc.set_index("local_time"))
    std = m.resample("1s").std()
    mean = m.resample("1s").mean()
    return mean[std < 0.05]


def checks(s):
    results = []

    def add(name, value, ok_range, unit):
        lo, hi = ok_range
        ok = lo <= value <= hi
        results.append((ok, f"{'PASS' if ok else 'CHECK'}  {name}: {value:.2f} {unit} (expected {lo}–{hi})"))

    acc = s["accelerometer"]
    still = still_mask(acc)
    if len(still):
        add("|accel| when the wrist is still (should be gravity)", still.median(), (9.6, 10.0), "m/s²")
    if s.get("gravity") is not None:
        add("|gravity| average", magnitude(s["gravity"]).mean(), (9.7, 9.9), "m/s²")
    if s.get("gravity") is not None and s.get("linear_acceleration") is not None:
        a = acc[["t_ns"] + XYZ].sort_values("t_ns")
        g = s["gravity"][["t_ns"] + XYZ].sort_values("t_ns")
        l = s["linear_acceleration"][["t_ns"] + XYZ].sort_values("t_ns")
        m = pd.merge_asof(a, g, on="t_ns", suffixes=("", "_g"), direction="nearest", tolerance=10_000_000)
        m = pd.merge_asof(m, l, on="t_ns", suffixes=("", "_l"), direction="nearest", tolerance=10_000_000).dropna()
        err = np.sqrt(sum((m[c] - m[c + "_g"] - m[c + "_l"]) ** 2 for c in XYZ))
        add("accel − (gravity + linear), median difference", err.median(), (0.0, 0.3), "m/s²")
    if s.get("magnetometer") is not None:
        add("|magnetic field| median (Earth's field)", magnitude(s["magnetometer"]).median(), (20, 70), "µT")
    if s.get("barometer") is not None:
        add("air pressure median", s["barometer"]["pressure_hpa"].median(), (870, 1085), "hPa")
    dt = acc["t_ns"].diff().dropna() / 1e6
    dt = dt[(dt > 0) & (dt < 100)]
    add("median time between readings", dt.median(), (19.0, 21.0), "ms")
    add("readings within ±2 ms of the median", 100 * (abs(dt - dt.median()) <= 2).mean(), (95, 100), "%")
    return results


# ---------- main ----------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="~/HAR_Recorder/data")
    parser.add_argument("--day", help="YYYY-MM-DD (default: latest)")
    parser.add_argument("--start", help="zoom start time HH:MM or HH:MM:SS (default: 1 min into the data)")
    parser.add_argument("--seconds", type=int, default=10, help="zoom length in seconds")
    args = parser.parse_args()

    data = Path(args.data).expanduser()
    days = sorted(p.name for p in (data / "processed").iterdir() if p.is_dir())
    day = args.day or days[-1]
    day_dir = data / "processed" / day
    print(f"Day: {day}")

    s = {name: load(day_dir, name, XYZ) for name in MOTION}
    s["barometer"] = load(day_dir, "barometer", ["pressure_hpa"])
    if s["accelerometer"] is None:
        raise SystemExit(f"No accelerometer.csv in {day_dir}. Run process_data.py first.")

    tz = s["accelerometer"]["local_time"].dt.tz
    gaps = pd.read_csv(data / "reports" / "gaps.csv")
    gaps = gaps[gaps["sensor"] == "accelerometer"]
    for c in ("gap_start", "gap_end"):
        gaps[c] = pd.to_datetime(gaps[c], format="ISO8601", utc=True).dt.tz_convert(tz)
    off_path = data / "reports" / "off_wrist.csv"
    off_wrist = pd.read_csv(off_path) if off_path.exists() else pd.DataFrame(columns=["off_from", "back_on"])
    for c in ("off_from", "back_on"):
        off_wrist[c] = pd.to_datetime(off_wrist[c], format="ISO8601", utc=True).dt.tz_convert(tz)

    figs = data / "figures"
    figs.mkdir(parents=True, exist_ok=True)

    print("Drawing overview...")
    plot_overview(s, gaps, off_wrist, f"{day} overview", figs / f"{day}_overview.png")

    first = s["accelerometer"]["local_time"].iloc[0]
    if args.start:
        start = pd.Timestamp(f"{day} {args.start}").tz_localize(tz)
    else:
        start = first + pd.Timedelta(minutes=1)
    print("Drawing zoom...")
    plot_zoom(s, start, args.seconds, figs / f"{day}_zoom.png")
    print("Drawing timing...")
    plot_timing(s["accelerometer"], figs / f"{day}_timing.png")

    print("\n=== Sanity checks ===")
    lines = []
    for ok, text in checks(s):
        print("  " + text)
        lines.append(text)
    (data / "reports" / f"{day}_sanity_checks.txt").write_text("\n".join(lines) + "\n")
    print(f"\nFigures in: {figs}")


if __name__ == "__main__":
    main()