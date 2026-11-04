"""Night analysis for SleepAnalysis session 11 (night of 2026-11-03 -> 04, Zurich).

Reads untrusted CSVs exported from the pulled SQLite DB and produces:
 - validated metrics (union-aware snore time, bouts, hourly distribution)
 - comparison against session 10
 - matplotlib charts (PNG)

Run (data and output live outside the repo, never committed):
  NIGHT_DATA=/path/to/night-data NIGHT_REPORT_OUT=/path/to/out \
  NIGHT_REPORT_CLIP=<clip filename> \
    uv run --with matplotlib --with numpy -- python -I night_report.py
"""

import csv
import os
import wave
from collections import defaultdict
from datetime import UTC, datetime
from itertools import pairwise
from pathlib import Path
from zoneinfo import ZoneInfo

import matplotlib

matplotlib.use("Agg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import numpy as np

DATA = Path(os.environ["NIGHT_DATA"])
OUT = Path(os.environ["NIGHT_REPORT_OUT"])
TZ = ZoneInfo("Europe/Zurich")

# Capture window of session 11 (epoch ms), from sleep_session + session_11.log
CAPTURE_START_MS = 1793747285656  # delayed capture actually started
CAPTURE_END_MS = 1793774521635  # manual stop, COMPLETED
NIGHT_START_MS = 1793743685656  # user tapped start (60-min delay followed)

Z_S11 = 1793743685656  # session 10/11 reference epochs for context
Z_S10 = 1793652742902


def loc(ms: float) -> datetime:
    return datetime.fromtimestamp(ms / 1000, tz=UTC).astimezone(TZ)


def hms(s: float) -> str:
    s = round(s)  # round(x) with no ndigits already returns int
    return f"{s // 3600}h{s % 3600 // 60:02d}m{s % 60:02d}s"


def load_events(path: Path) -> list[dict]:
    with path.open(newline="") as f:
        rows = list(csv.DictReader(f))
    ev = []
    for r in rows:
        ev.append(
            {
                "id": int(r["id"]),
                "type": r["type"],
                "start": int(r["startMs"]),
                "end": int(r["endMs"]),
                "dur": int(r["durationMs"]),
                "peak": float(r["peakDbfs"]),
                "mean": float(r["meanDbfs"]),
                "tier": r["intensityTier"],
                "conf": float(r["confidence"]),
            }
        )
    ev.sort(key=lambda e: e["start"])
    return ev


def union_time(evs: list[dict]) -> float:
    """Total seconds covered by at least one event (overlap-aware)."""
    if not evs:
        return 0.0
    total = 0.0
    cur_s, cur_e = evs[0]["start"], evs[0]["end"]
    for e in evs[1:]:
        if e["start"] <= cur_e:
            cur_e = max(cur_e, e["end"])
        else:
            total += cur_e - cur_s
            cur_s, cur_e = e["start"], e["end"]
    total += cur_e - cur_s
    return total / 1000


def bouts(evs: list[dict], gap_s: float) -> list[tuple[int, int, int]]:
    """Chain events separated by <= gap_s. Returns (startMs, endMs, nEvents)."""
    out = []
    cur = [evs[0]]
    for e in evs[1:]:
        if e["start"] - cur[-1]["end"] <= gap_s * 1000:
            cur.append(e)
        else:
            out.append((cur[0]["start"], cur[-1]["end"], len(cur)))
            cur = [e]
    out.append((cur[0]["start"], cur[-1]["end"], len(cur)))
    return out


def hourly(snore: list[dict], noise: list[dict]) -> dict[str, dict]:
    """Union-aware snore seconds and event counts bucketed by local hour."""
    hours: dict[str, dict] = defaultdict(
        lambda: {"snore_s": 0.0, "n": 0, "noise": 0, "peak": []}
    )
    for e in snore:
        h = loc(e["start"]).strftime("%H")
        hours[h]["n"] += 1
        hours[h]["peak"].append(e["peak"])
    for e in noise:
        hours[loc(e["start"]).strftime("%H")]["noise"] += 1
    # union-aware seconds per hour
    seg: list[tuple[int, int]] = []
    cur_s = cur_e = None
    for e in snore:
        if cur_s is None:
            cur_s, cur_e = e["start"], e["end"]
        elif e["start"] <= cur_e:
            cur_e = max(cur_e, e["end"])
        else:
            seg.append((cur_s, cur_e))
            cur_s, cur_e = e["start"], e["end"]
    if cur_s is not None:
        seg.append((cur_s, cur_e))
    for s, t in seg:
        # split across hour boundaries in local time
        x = s
        while x < t:
            hour_end = int(
                loc(x)
                .replace(minute=0, second=0, microsecond=0)
                .astimezone(UTC)
                .timestamp()
                * 1000
                + 3600_000
            )
            e_min = min(t, hour_end)
            hours[loc(x).strftime("%H")]["snore_s"] += (e_min - x) / 1000
            x = e_min
    return hours


def main() -> None:
    s11 = load_events(DATA / "events_s11.csv")
    s10 = load_events(DATA / "events_s10.csv")
    snore11 = [e for e in s11 if e["type"] == "SNORE"]
    noise11 = [e for e in s11 if e["type"] == "OTHER_NOISE"]

    print("=== VALIDATION ===")
    bad_dur = [e for e in s11 if abs(e["end"] - e["start"] - e["dur"]) > 1]
    outside = [
        e for e in s11 if e["start"] < CAPTURE_START_MS or e["end"] > CAPTURE_END_MS
    ]
    low_conf = [e for e in s11 if e["conf"] < 0.5]
    long_ev = [e for e in snore11 if e["dur"] > 30_000]
    print(f"dur mismatch: {len(bad_dur)}   outside capture window: {len(outside)}")
    print(
        f"conf<0.5: {len(low_conf)} ({sum(e['type'] == 'SNORE' for e in low_conf)} snore, {len(low_conf) - sum(e['type'] == 'SNORE' for e in low_conf)} noise)"
    )
    print(f"snore events >30s (likely merged bouts): {len(long_ev)}")
    # overlap within type
    ov = sum(1 for a, b in pairwise(snore11) if b["start"] < a["end"])
    print(f"adjacent SNORE pairs overlapping: {ov}")
    # gaps in capture
    gaps = [
        (a["end"], b["start"])
        for a, b in pairwise(s11)
        if b["start"] - a["end"] > 600_000
    ]
    print("quiet gaps >10 min:")
    for s, t in gaps:
        print(f"  {loc(s):%H:%M:%S} - {loc(t):%H:%M:%S}  ({(t - s) / 60000:.0f} min)")

    print("\n=== CORE METRICS (session 11) ===")
    win_s = (CAPTURE_END_MS - CAPTURE_START_MS) / 1000
    bed_s = (CAPTURE_END_MS - NIGHT_START_MS) / 1000
    print(
        f"tap start {loc(NIGHT_START_MS):%H:%M:%S}, capture {loc(CAPTURE_START_MS):%H:%M:%S}-{loc(CAPTURE_END_MS):%H:%M:%S} ({hms(win_s)})"
    )
    print(f"snore events: {len(snore11)}, noise events: {len(noise11)}")
    print(
        f"snore time sum: {hms(sum(e['dur'] for e in snore11) / 1000)} | union: {hms(union_time(snore11))}"
    )
    print(
        f"share of capture window: {union_time(snore11) / win_s * 100:.1f}%  (of time-in-bed: {union_time(snore11) / bed_s * 100:.1f}%)"
    )
    print(
        f"snore peak dBFS avg {np.mean([e['peak'] for e in snore11]):.1f}, max {max(e['peak'] for e in snore11):.1f}"
    )
    print(f"snore mean dBFS avg {np.mean([e['mean'] for e in snore11]):.1f}")
    print(
        f"durations: median {np.median([e['dur'] for e in snore11]) / 1000:.1f}s, p90 {np.percentile([e['dur'] for e in snore11], 90) / 1000:.1f}s, max {max(e['dur'] for e in snore11) / 1000:.1f}s"
    )
    longest = max(snore11, key=lambda e: e["dur"])
    print(
        f"longest single event: {loc(longest['start']):%H:%M:%S} dur {longest['dur'] / 1000:.0f}s peak {longest['peak']:.1f} conf {longest['conf']:.2f}"
    )

    print("\n=== BOUTS (chained snore, union-aware) ===")
    for gap in (1, 2, 5, 10):
        b = bouts(snore11, gap)
        longest_b = max(b, key=lambda x: x[1] - x[0])
        print(
            f"gap<={gap:>2}s: {len(b):>4} bouts | longest {hms((longest_b[1] - longest_b[0]) / 1000)} "
            f"({loc(longest_b[0]):%H:%M:%S}-{loc(longest_b[1]):%H:%M:%S}, {longest_b[2]} events)"
        )

    print("\n=== HOURLY (Zurich local) ===")
    hh = hourly(snore11, noise11)
    print(f"{'hour':>5} {'snoreMin':>8} {'events':>6} {'noise':>5} {'avgPeak':>8}")
    for h in sorted(hh):
        d = hh[h]
        ap = np.mean(d["peak"]) if d["peak"] else float("nan")
        print(f"{h}:00  {d['snore_s'] / 60:8.1f} {d['n']:6d} {d['noise']:5d} {ap:8.1f}")

    print("\n=== TREND ===")
    for name, evs in (
        (
            "first half",
            [
                e
                for e in snore11
                if e["start"] < (CAPTURE_START_MS + CAPTURE_END_MS) / 2
            ],
        ),
        (
            "second half",
            [
                e
                for e in snore11
                if e["start"] >= (CAPTURE_START_MS + CAPTURE_END_MS) / 2
            ],
        ),
    ):
        u = union_time(evs)
        print(
            f"{name}: {len(evs)} events, {hms(u)}, rate {len(evs) / (win_s / 2) * 3600:.0f}/h, avgPeak {np.mean([e['peak'] for e in evs]):.1f}"
        )
    # 15-min trend slope of snore share
    bstart = CAPTURE_START_MS
    buckets = []
    t = bstart
    while t < CAPTURE_END_MS:
        nxt = min(t + 900_000, CAPTURE_END_MS)
        inb = [e for e in snore11 if e["start"] < nxt and e["end"] > t]
        clipped = sum((min(e["end"], nxt) - max(e["start"], t)) for e in inb)
        buckets.append(
            (
                t,
                clipped / (nxt - t) * 100,
                np.mean([e["peak"] for e in inb]) if inb else np.nan,
            )
        )
        t = nxt
    shares = [b[1] for b in buckets]
    peaks = [b[2] for b in buckets]
    x = np.arange(len(buckets))
    print(
        f"share slope: {np.polyfit(x, shares, 1)[0]:+.2f} %-pts per 15-min (start {shares[0]:.0f}% -> end {shares[-1]:.0f}%)"
    )
    print(
        f"peak trend: {np.polyfit(x, np.nan_to_num(peaks, nan=np.nanmean(peaks)), 1)[0]:+.2f} dB per 15-min"
    )

    print("\n=== COMPARISON s10 vs s11 ===")
    snore10 = [e for e in s10 if e["type"] == "SNORE"]
    print(
        f"s10: {len(snore10)} snores, union {hms(union_time(snore10))}, avgPeak {np.mean([e['peak'] for e in snore10]):.1f}"
    )
    print(
        f"s11: {len(snore11)} snores, union {hms(union_time(snore11))}, avgPeak {np.mean([e['peak'] for e in snore11]):.1f}"
    )

    # WAV sanity check: DB peak vs decoded PCM peak
    print("\n=== WAV CROSS-CHECK ===")
    wav_p = DATA / "clips" / os.environ["NIGHT_REPORT_CLIP"]
    with wave.open(str(wav_p)) as w:
        pcm = (
            np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(
                np.float64
            )
            / 32768
        )
    db_peak = 20 * np.log10(max(pcm.max(), -pcm.min()) + 1e-12)
    match = [e for e in s11 if abs(e["start"] - 1793747290769) < 3000]
    print(f"file peak {db_peak:.1f} dBFS, dur {len(pcm) / 16000:.1f}s")
    if match:
        e = match[0]
        print(
            f"DB event: start {loc(e['start']):%H:%M:%S}, peak {e['peak']:.1f}, mean {e['mean']:.1f}, conf {e['conf']:.2f}, type {e['type']}, tier {e['tier']}"
        )

    # ---- charts ----
    plt.rcParams.update({"figure.dpi": 150, "font.size": 9})
    ts = [loc(e["start"]) for e in snore11]
    pk = [e["peak"] for e in snore11]

    fig, ax = plt.subplots(figsize=(11, 4))
    ax.scatter(ts, pk, s=6, alpha=0.5, label="SNORE", color="tab:red")
    ax.scatter(
        [loc(e["start"]) for e in noise11],
        [e["peak"] for e in noise11],
        s=6,
        alpha=0.5,
        label="OTHER_NOISE",
        color="tab:blue",
    )
    ax.axvspan(
        loc(NIGHT_START_MS),
        loc(CAPTURE_START_MS),
        color="grey",
        alpha=0.15,
        label="delay (no capture)",
    )
    ax.invert_yaxis()
    ax.set_ylabel("peak dBFS (quieter up)")
    ax.set_title(
        f"Session 11 — acoustic events, {loc(CAPTURE_START_MS):%a %d %b %Y} (Zurich)"
    )
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M", tz=TZ))
    ax.xaxis.set_major_locator(mdates.HourLocator(interval=1, tz=TZ))
    ax.legend(loc="lower right")
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(OUT / "chart_timeline.png")

    fig, ax = plt.subplots(figsize=(8, 3.5))
    hrs = sorted(hh)
    mins = [hh[h]["snore_s"] / 60 for h in hrs]
    cnt = [hh[h]["n"] for h in hrs]
    ax.bar(
        [f"{h}:00" for h in hrs],
        mins,
        color="tab:red",
        alpha=0.7,
        label="snoring (union min)",
    )
    ax2 = ax.twinx()
    ax2.plot([f"{h}:00" for h in hrs], cnt, "o-", color="k", ms=4, label="events")
    ax.set_ylabel("minutes snoring")
    ax2.set_ylabel("events")
    ax.set_title("Hourly snoring distribution")
    ax.tick_params(axis="x", rotation=45)
    fig.tight_layout()
    fig.savefig(OUT / "chart_hourly.png")

    fig, ax = plt.subplots(figsize=(8, 3.5))
    bins = np.logspace(np.log10(0.4), np.log10(105), 30)
    ax.hist(
        [e["dur"] / 1000 for e in snore10],
        bins=bins,
        alpha=0.5,
        label="night Nov 2→3 (s10)",
        color="tab:blue",
    )
    ax.hist(
        [e["dur"] / 1000 for e in snore11],
        bins=bins,
        alpha=0.5,
        label="night Nov 3→4 (s11)",
        color="tab:red",
    )
    ax.set_xscale("log")
    ax.set_xlabel("event duration (s)")
    ax.set_ylabel("count")
    ax.set_title("Snore event duration histogram")
    ax.legend()
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(OUT / "chart_durations.png")

    fig, ax = plt.subplots(figsize=(9, 3.5))
    labels = [loc(b[0]).strftime("%H:%M") for b in buckets]
    ax.plot(labels, shares, "o-", color="tab:red", label="snore share % of 15-min")
    ax2 = ax.twinx()
    ax2.plot(
        labels, peaks, "s--", color="tab:orange", alpha=0.8, label="mean peak dBFS"
    )
    ax.set_ylabel("snore share (%)")
    ax2.set_ylabel("mean peak dBFS")
    ax2.invert_yaxis()
    ax.set_title("Intensity & share trend through the night (15-min buckets)", pad=28)
    ax.tick_params(axis="x", rotation=45)
    lines = ax.get_lines() + ax2.get_lines()
    fig.legend(
        lines,
        [l.get_label() for l in lines],
        loc="upper center",
        ncol=2,
        frameon=True,
        bbox_to_anchor=(0.5, 0.99),
    )
    ax.grid(alpha=0.3)
    fig.tight_layout(rect=(0, 0, 1, 0.9))
    fig.savefig(OUT / "chart_trend.png")
    print("\ncharts written to", OUT)


if __name__ == "__main__":
    main()
