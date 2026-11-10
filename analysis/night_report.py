"""Night analysis for a pair of SleepAnalysis acoustic sessions (this night vs the previous one).

Reads untrusted CSVs exported from the pulled SQLite DB (tools/export_night_csv.py)
and produces:
 - validated metrics (union-aware snore time, bouts, hourly distribution)
 - comparison against the previous night
 - matplotlib charts (PNG)

No session identity is baked in: NIGHT_EVENTS_CSV / NIGHT_PREV_EVENTS_CSV name the
current and previous night's events CSVs inside NIGHT_DATA, and the labels shown in
prints and chart titles are derived from those filenames (events_5.csv -> "5").

Run (data, output and session timing live outside the repo, never committed):
  NIGHT_DATA=/path/to/night-data NIGHT_REPORT_OUT=/path/to/out \
  NIGHT_EVENTS_CSV=events_5.csv NIGHT_PREV_EVENTS_CSV=events_4.csv \
  NIGHT_REPORT_CLIP=<clip filename> \
  NIGHT_CAPTURE_START_MS=<epoch ms> NIGHT_CAPTURE_END_MS=<epoch ms> \
  NIGHT_START_MS=<epoch ms> NIGHT_PREV_START_MS=<epoch ms> \
    uv run --with matplotlib --with numpy -- python -I night_report.py
"""

import csv
import json
import os
import re
import sys
import wave
from collections import defaultdict
from datetime import UTC, datetime
from itertools import pairwise
from pathlib import Path
from zoneinfo import ZoneInfo

# `python -I` does not add the script directory to sys.path — do it explicitly so the
# report can import its pure-aggregation sibling (stdlib-only, see night_aggregates.py).
sys.path.insert(0, str(Path(__file__).resolve().parent))

import matplotlib

matplotlib.use("Agg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import numpy as np
from night_aggregates import events_counting_as_snore, match_clip_entry
from night_validity import assess as assess_night_validity
from night_validity import capture_from_env, garmin_warnings_from_env

DATA = Path(os.environ["NIGHT_DATA"])
OUT = Path(os.environ["NIGHT_REPORT_OUT"])
TZ = ZoneInfo("Europe/Zurich")

# Current and previous night's events CSVs (filenames inside NIGHT_DATA, as written
# by tools/export_night_csv.py); the labels used in prints and chart titles derive
# from the filenames, so no session naming is baked in here.
CUR_CSV = os.environ["NIGHT_EVENTS_CSV"]
PREV_CSV = os.environ["NIGHT_PREV_EVENTS_CSV"]


def label_of(csv_name: str) -> str:
    """Report label for one night, derived from its events CSV filename."""
    return Path(csv_name).stem.removeprefix("events_")


CUR_LABEL = label_of(CUR_CSV)
PREV_LABEL = label_of(PREV_CSV)


def _epoch_env(name: str) -> int:
    return int(os.environ[name])


def _env_float(name: str) -> float | None:
    """Optional float env var (night-validity inputs); missing/unparsable → None."""
    raw = os.environ.get(name)
    try:
        return float(raw) if raw is not None else None
    except ValueError:
        return None


# Capture window (epoch ms) of the night under analysis — no session-specific
# timing is baked into the repo; everything comes from the caller's environment.
CAPTURE_START_MS = _epoch_env("NIGHT_CAPTURE_START_MS")
CAPTURE_END_MS = _epoch_env("NIGHT_CAPTURE_END_MS")
NIGHT_START_MS = _epoch_env("NIGHT_START_MS")  # tap start, night under analysis
PREV_START_MS = _epoch_env("NIGHT_PREV_START_MS")  # tap start, previous night


def loc(ms: float) -> datetime:
    return datetime.fromtimestamp(ms / 1000, tz=UTC).astimezone(TZ)


def hms(s: float) -> str:
    s = round(s)  # round(x) with no ndigits already returns int
    return f"{s // 3600}h{s % 3600 // 60:02d}m{s % 60:02d}s"


def night_span(evs: list[dict]) -> str:
    """'Nov 2->3'-style local span of one night: first event start -> last event end."""
    if not evs:
        return "?"
    d0 = loc(evs[0]["start"])
    d1 = loc(max(e["end"] for e in evs))
    if d0.month == d1.month:
        return f"{d0:%b} {d0.day}→{d1.day}"
    return f"{d0:%b} {d0.day}→{d1:%b} {d1.day}"


def load_events(path: Path) -> list[dict]:
    with path.open(newline="") as f:
        rows = list(csv.DictReader(f))
    ev = []
    for r in rows:
        ev.append(
            {
                "id": int(r["id"]),
                "type": r["type"],
                "start": int(r["start_ms"]),
                "end": int(r["end_ms"]),
                "dur": int(r["duration_ms"]),
                "peak": float(r["peak_dbfs"]),
                "mean": float(r["mean_dbfs"]),
                "tier": r["tier"],
                "conf": float(r["confidence"]),
                "user_label": r.get("user_label") or "",
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
    cur = load_events(DATA / CUR_CSV)
    prev = load_events(DATA / PREV_CSV)
    # Label-aware snore set (snore-score-v3, mirrors the app's aggregates): a non-SNORE
    # user label excludes the event from snoring entirely; noise counts stay
    # detector-type-based, exactly like ReportAssembler's presentation side.
    snore = [e for e in cur if events_counting_as_snore(e)]
    noise = [e for e in cur if e["type"] == "OTHER_NOISE"]
    excluded = [
        e for e in cur if e["type"] == "SNORE" and not events_counting_as_snore(e)
    ]
    if excluded:
        print(
            f"label-aware: {len(excluded)} detector-SNORE event(s) excluded "
            f"(user relabeled: {sorted({e['user_label'] for e in excluded})})"
        )
    prev_snore = [e for e in prev if events_counting_as_snore(e)]

    print("=== VALIDATION ===")
    bad_dur = [e for e in cur if abs(e["end"] - e["start"] - e["dur"]) > 1]
    outside = [
        e for e in cur if e["start"] < CAPTURE_START_MS or e["end"] > CAPTURE_END_MS
    ]
    low_conf = [e for e in cur if e["conf"] < 0.5]
    long_ev = [e for e in snore if e["dur"] > 30_000]
    print(f"dur mismatch: {len(bad_dur)}   outside capture window: {len(outside)}")
    print(
        f"conf<0.5: {len(low_conf)} ({sum(e['type'] == 'SNORE' for e in low_conf)} snore, {len(low_conf) - sum(e['type'] == 'SNORE' for e in low_conf)} noise)"
    )
    print(f"snore events >30s (likely merged bouts): {len(long_ev)}")
    # overlap within type
    ov = sum(1 for a, b in pairwise(snore) if b["start"] < a["end"])
    print(f"adjacent SNORE pairs overlapping: {ov}")
    # gaps in capture
    gaps = [
        (a["end"], b["start"])
        for a, b in pairwise(cur)
        if b["start"] - a["end"] > 600_000
    ]
    print("quiet gaps >10 min:")
    for s, t in gaps:
        print(f"  {loc(s):%H:%M:%S} - {loc(t):%H:%M:%S}  ({(t - s) / 60000:.0f} min)")

    # Night validity (domain/validity.NightValidity mirror): grade + ordered reasons.
    # Capture stats / ambient / Garmin inputs arrive via NIGHT_VALIDITY_* env (see
    # night_validity.capture_from_env); without them the grade degrades to stats_missing D —
    # the app's stored NIGHT_VALIDITY row stays the source of truth for full nights.
    validity = assess_night_validity(
        capture=capture_from_env(),
        uncaptured_gap_minutes=_env_float("NIGHT_VALIDITY_UNCAPTURED_MIN"),
        session_status=os.environ.get("NIGHT_VALIDITY_SESSION_STATUS"),
        resumed=os.environ.get("NIGHT_VALIDITY_RESUMED") == "1",
        snore_share=_env_float("NIGHT_VALIDITY_SNORE_SHARE"),
        ambient_z=_env_float("NIGHT_VALIDITY_AMBIENT_Z"),
        garmin_warnings=garmin_warnings_from_env(),
    )
    print(f"\nnight validity: grade {validity['grade']} ({validity['version']})")
    for r in validity["reasons"]:
        print(f"  [{r['severity']}] {r['id']}: {r['detail']}")

    print(f"\n=== CORE METRICS (session {CUR_LABEL}) ===")
    win_s = (CAPTURE_END_MS - CAPTURE_START_MS) / 1000
    bed_s = (CAPTURE_END_MS - NIGHT_START_MS) / 1000
    print(
        f"tap start {loc(NIGHT_START_MS):%H:%M:%S}, capture {loc(CAPTURE_START_MS):%H:%M:%S}-{loc(CAPTURE_END_MS):%H:%M:%S} ({hms(win_s)})"
    )
    print(f"snore events: {len(snore)}, noise events: {len(noise)}")
    print(
        f"snore time sum: {hms(sum(e['dur'] for e in snore) / 1000)} | union: {hms(union_time(snore))}"
    )
    print(
        f"share of capture window: {union_time(snore) / win_s * 100:.1f}%  (of time-in-bed: {union_time(snore) / bed_s * 100:.1f}%)"
    )
    print(
        f"snore peak dBFS avg {np.mean([e['peak'] for e in snore]):.1f}, max {max(e['peak'] for e in snore):.1f}"
    )
    print(f"snore mean dBFS avg {np.mean([e['mean'] for e in snore]):.1f}")
    print(
        f"durations: median {np.median([e['dur'] for e in snore]) / 1000:.1f}s, p90 {np.percentile([e['dur'] for e in snore], 90) / 1000:.1f}s, max {max(e['dur'] for e in snore) / 1000:.1f}s"
    )
    longest = max(snore, key=lambda e: e["dur"])
    print(
        f"longest single event: {loc(longest['start']):%H:%M:%S} dur {longest['dur'] / 1000:.0f}s peak {longest['peak']:.1f} conf {longest['conf']:.2f}"
    )

    print("\n=== BOUTS (chained snore, union-aware) ===")
    for gap in (1, 2, 5, 10):
        b = bouts(snore, gap)
        longest_b = max(b, key=lambda x: x[1] - x[0])
        print(
            f"gap<={gap:>2}s: {len(b):>4} bouts | longest {hms((longest_b[1] - longest_b[0]) / 1000)} "
            f"({loc(longest_b[0]):%H:%M:%S}-{loc(longest_b[1]):%H:%M:%S}, {longest_b[2]} events)"
        )

    print("\n=== HOURLY (Zurich local) ===")
    hh = hourly(snore, noise)
    print(f"{'hour':>5} {'snoreMin':>8} {'events':>6} {'noise':>5} {'avgPeak':>8}")
    for h in sorted(hh):
        d = hh[h]
        ap = np.mean(d["peak"]) if d["peak"] else float("nan")
        print(f"{h}:00  {d['snore_s'] / 60:8.1f} {d['n']:6d} {d['noise']:5d} {ap:8.1f}")

    print("\n=== TREND ===")
    for name, evs in (
        (
            "first half",
            [e for e in snore if e["start"] < (CAPTURE_START_MS + CAPTURE_END_MS) / 2],
        ),
        (
            "second half",
            [e for e in snore if e["start"] >= (CAPTURE_START_MS + CAPTURE_END_MS) / 2],
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
        inb = [e for e in snore if e["start"] < nxt and e["end"] > t]
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

    print(f"\n=== COMPARISON {PREV_LABEL} vs {CUR_LABEL} ===")
    snore_prev = prev_snore
    print(
        f"{PREV_LABEL}: {len(snore_prev)} snores, union {hms(union_time(snore_prev))}, avgPeak {np.mean([e['peak'] for e in snore_prev]):.1f}"
    )
    print(
        f"{CUR_LABEL}: {len(snore)} snores, union {hms(union_time(snore))}, avgPeak {np.mean([e['peak'] for e in snore]):.1f}"
    )

    # WAV sanity check: DB peak vs decoded PCM peak (skipped when no clip is given —
    # a quiet night can legitimately have zero clips).
    clip_name = os.environ.get("NIGHT_REPORT_CLIP", "")
    if clip_name:
        print("\n=== WAV CROSS-CHECK ===")
        wav_p = DATA / "clips" / clip_name
        with wave.open(str(wav_p)) as w:
            pcm = (
                np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(
                    np.float64
                )
                / 32768
            )
        db_peak = 20 * np.log10(max(pcm.max(), -pcm.min()) + 1e-12)
        print(f"file peak {db_peak:.1f} dBFS, dur {len(pcm) / 16000:.1f}s")
        # The DB event the clip belongs to: prefer the export's manifest_<sid>.json
        # (exact event id — the verified join), fall back to the clip-name scheme
        # evt_<type>_<start_ms>_<seq>.wav for older exports without a manifest.
        m = re.fullmatch(r"evt_\w+_(\d+)_\d+\.wav", clip_name)
        match: list[dict] = []
        source = "filename scheme"
        manifest_p = DATA / "clips" / f"manifest_{CUR_LABEL}.json"
        if manifest_p.exists():
            try:
                entries = json.loads(manifest_p.read_text())["clips"]
                if not isinstance(entries, list):
                    entries = []
            except (OSError, ValueError, KeyError):
                entries = []
            entry = match_clip_entry(entries, clip_name)
            if entry is not None:
                # A manifest whose event id is absent from the CSVs (hand-corrupted,
                # or a different export generation) must fall through to the fallback.
                match = [e for e in cur if e["id"] == entry.get("event_id")]
                if match:
                    source = f"manifest ({manifest_p.name})"
        if not match and m:
            match = [e for e in cur if abs(e["start"] - int(m.group(1))) < 3000]
        if match:
            e = match[0]
            print(
                f"DB event (via {source}): start {loc(e['start']):%H:%M:%S}, peak {e['peak']:.1f}, mean {e['mean']:.1f}, conf {e['conf']:.2f}, type {e['type']}, tier {e['tier']}"
            )

    # ---- charts ----
    plt.rcParams.update({"figure.dpi": 150, "font.size": 9})
    ts = [loc(e["start"]) for e in snore]
    pk = [e["peak"] for e in snore]

    fig, ax = plt.subplots(figsize=(11, 4))
    ax.scatter(ts, pk, s=6, alpha=0.5, label="SNORE", color="tab:red")
    ax.scatter(
        [loc(e["start"]) for e in noise],
        [e["peak"] for e in noise],
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
        f"Session {CUR_LABEL} — acoustic events, {loc(CAPTURE_START_MS):%a %d %b %Y} (Zurich)"
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
        [e["dur"] / 1000 for e in snore_prev],
        bins=bins,
        alpha=0.5,
        label=f"night {night_span(snore_prev)} ({PREV_LABEL})",
        color="tab:blue",
    )
    ax.hist(
        [e["dur"] / 1000 for e in snore],
        bins=bins,
        alpha=0.5,
        label=f"night {night_span(snore)} ({CUR_LABEL})",
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
