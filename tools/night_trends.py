#!/usr/bin/env python3
"""Cross-night trends: snoring vs Garmin vendor sleep metrics.

Answers "do heavy-snoring nights show worse deep sleep / more awakenings?"
from data already on the device: per-night snore load (label-aware events and
union minutes over the capture window — SnoreMath semantics) next to the
Garmin sleep summary the watch syncs into `measurement`, then a tie-corrected
Spearman rank correlation of snore share vs each vendor metric over the
COMPLETED/RECOVERED nights that have vendor rows.

Correlational by nature — with a handful of nights it says so instead of
pretending. Stdlib only (average ranks + Pearson by hand, no scipy).

Stdlib only. Examples:

    uv run tools/night_trends.py                      # pull the DB from the phone
    uv run tools/night_trends.py --db ~/sleep-local/morning/<run>/sleepanalysis.db
    uv run tools/night_trends.py --serial emulator-5554 --min-nights 3
"""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

# Shared adb transfer helpers (exec-out + verification). sys.path[0] is tools/
# both as a script and under pytest.
from export_fixtures import detect_serial, pull_db

MIN_NIGHTS = 5  # below this the report is a count, not an analysis
MIN_PAIRS = 3  # a rank correlation over fewer pairs is noise
SMALL_N = 10  # caveat threshold: correlational, small sample
VERDICT_ABS_RHO = 0.6  # |rho| from which a metric earns a verdict line...
VERDICT_MIN_N = 7  # ...and the nights that line needs before we say "strong"
MISSING = "—"


@dataclass(frozen=True)
class MetricSpec:
    """One vendor metric: where it lives and how it is shown/correlated."""

    measurement_type: str  # measurement.measurementType, e.g. VENDOR_DEEP_SLEEP
    key: str  # Night.metrics key and table column, e.g. "deep"
    label: str  # human name in the correlation/verdict lines
    stage_minutes: (
        bool  # True: stored as SECONDS -> shown as minutes (unit column decides)
    )


# Correlation order (the user's question first: deep sleep, awakenings); the
# night table shows the first five.
METRICS = [
    MetricSpec("VENDOR_DEEP_SLEEP", "deep", "deep sleep", True),
    MetricSpec("VENDOR_LIGHT_SLEEP", "light", "light sleep", True),
    MetricSpec("VENDOR_REM_SLEEP", "rem", "REM sleep", True),
    MetricSpec("VENDOR_SLEEP_AWAKENINGS", "awakenings", "awakenings", False),
    MetricSpec("VENDOR_HRV_RMSSD", "hrv", "HRV (RMSSD)", False),
    MetricSpec("VENDOR_SLEEP_DURATION", "duration", "sleep duration", True),
    MetricSpec("VENDOR_SLEEP_SCORE", "score", "sleep score", False),
]
TABLE_KEYS = ("deep", "light", "rem", "awakenings", "hrv")


@dataclass(frozen=True)
class Night:
    """One usable night: snore load + whatever vendor metrics synced for it."""

    session_id: int
    date: str  # startedAt's localtime date (the "night of")
    snore_share: float | None  # snore minutes / capture window; None = window unknown
    snore_minutes: float
    metrics: dict[str, float | None]  # MetricSpec.key -> display value


def counts_as_snore(event_type: str | None, user_label: str | None) -> bool:
    """SnoreMath.eventsCountingAsSnore, replicated exactly: a user label
    overrides the detector's type (SNORE keeps the event, any other label
    drops it); no label keeps only detector-SNORE events."""
    return user_label == "SNORE" or (user_label is None and event_type == "SNORE")


def union_duration_ms(intervals: list[tuple[int, int]]) -> int:
    """Total covered time of possibly-overlapping [startMs, endMs] intervals,
    counting overlaps once — a direct port of SnoreMath.unionDurationMs
    (summing raw durations double-counts merged multi-window events). Degenerate
    intervals (end before start, from corrupt rows) clamp to a point."""
    if not intervals:
        return 0
    total = 0
    cur_start = 0
    cur_end = -1
    for s, raw_end in sorted(intervals):
        e = max(s, raw_end)
        if cur_end < cur_start:  # first interval
            cur_start, cur_end = s, e
        elif s > cur_end:  # gap (adjacent joins: s == curEnd is not a gap)
            total += cur_end - cur_start
            cur_start, cur_end = s, e
        elif e > cur_end:  # overlap: extend, contained intervals add nothing
            cur_end = e
    return total + max(cur_end - cur_start, 0)


def capture_window_ms(started_at: int, ended_at: int, capture_stats: str | None) -> int:
    """Capture window ms: captureStats $.capture_started_at -> endedAt, falling
    back to startedAt -> endedAt (same rule as tools/morning_report.sh step 4).
    Unparseable stats fall back too; a nonsensical window goes negative and the
    caller reports the night's share as unknown."""
    cap_start: int | None = None
    if capture_stats:
        try:
            raw = json.loads(capture_stats).get("capture_started_at")
        except (json.JSONDecodeError, AttributeError):
            raw = None
        if isinstance(raw, (int, float)) and not isinstance(raw, bool):
            cap_start = int(raw)
    return ended_at - (cap_start if cap_start is not None else started_at)


def metric_value(rows: dict[str, tuple[float, str]], spec: MetricSpec) -> float | None:
    """The display value for one metric: stage metrics convert SECONDS -> minutes
    (MINUTES pass through, any other unit means we don't know how to scale it —
    missing beats mis-scaled); the rest are counts/indices used as-is."""
    found = rows.get(spec.measurement_type)
    if found is None:
        return None
    value, unit = found
    if not spec.stage_minutes:
        return value
    if unit == "SECONDS":
        return value / 60.0
    if unit == "MINUTES":
        return value
    return None


def collect_nights(conn: sqlite3.Connection) -> list[Night]:
    """Per-night snore + vendor rows for every COMPLETED/RECOVERED session with
    at least one vendor measurement row, date-sorted. Rows whose sessionId went
    NULL (they outlived their session) cannot be attributed and never create a
    night; duplicate vendor rows: the later timestamp wins."""
    vendor: dict[int, dict[str, tuple[float, str]]] = {}
    for session_id, mtype, value, unit in conn.execute(
        """
        SELECT sessionId, measurementType, valueNum, unit FROM measurement
        WHERE measurementType LIKE 'VENDOR\\_%' ESCAPE '\\'
          AND sessionId IS NOT NULL AND valueNum IS NOT NULL
        ORDER BY timestamp, id
        """
    ):
        vendor.setdefault(session_id, {})[mtype] = (value, unit)

    nights = []
    for session_id, started_at, ended_at, capture_stats in conn.execute(
        """
        SELECT id, startedAt, endedAt, captureStats FROM sleep_session
        WHERE status IN ('COMPLETED', 'RECOVERED') AND endedAt IS NOT NULL
        ORDER BY startedAt
        """
    ):
        rows = vendor.get(session_id)
        if not rows:
            continue  # no vendor data -> the night can't join a vendor correlation
        counting = [
            (start, end)
            for etype, start, end, label in conn.execute(
                "SELECT type, startMs, endMs, userLabel FROM acoustic_event"
                " WHERE sessionId = ?",
                (session_id,),
            )
            if counts_as_snore(etype, label)
        ]
        snore_ms = union_duration_ms(counting)
        window_ms = capture_window_ms(started_at, ended_at, capture_stats)
        nights.append(
            Night(
                session_id=session_id,
                date=datetime.fromtimestamp(started_at / 1000).strftime("%Y-%m-%d"),
                snore_share=snore_ms / window_ms if window_ms > 0 else None,
                snore_minutes=snore_ms / 60_000,
                metrics={spec.key: metric_value(rows, spec) for spec in METRICS},
            )
        )
    return nights


def _ranks(values: list[float]) -> list[float]:
    """1-based ranks, ties sharing the average of their positions."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        avg = (i + j) / 2 + 1  # positions i..j share the mean of ranks i+1..j+1
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks


def spearman(xs: list[float], ys: list[float]) -> float | None:
    """Tie-corrected Spearman: average ranks for ties, Pearson on the ranks.
    None under MIN_PAIRS or when a column has no variance (no order info)."""
    n = len(xs)
    if n < MIN_PAIRS:
        return None
    rx, ry = _ranks(xs), _ranks(ys)
    mx, my = sum(rx) / n, sum(ry) / n
    dx = [a - mx for a in rx]
    dy = [b - my for b in ry]
    den = math.sqrt(sum(a * a for a in dx) * sum(b * b for b in dy))
    if den == 0:
        return None
    return sum(a * b for a, b in zip(dx, dy)) / den


def correlations(
    nights: list[Night],
) -> list[tuple[MetricSpec, float | None, int]]:
    """Per metric: (spec, rho, n) over the nights having BOTH the snore share
    and that metric — a night missing a metric only drops out of that metric."""
    results = []
    for spec in METRICS:
        pts = [
            (n.snore_share, n.metrics[spec.key])
            for n in nights
            if n.snore_share is not None and n.metrics[spec.key] is not None
        ]
        rho = spearman([p[0] for p in pts], [p[1] for p in pts])
        results.append((spec, rho, len(pts)))
    return results


def _cell(value: float | None) -> str:
    return MISSING if value is None else f"{round(value)}"


def _pct(share: float | None) -> str:
    return MISSING if share is None else f"{share * 100:.1f}"


def format_nights(nights: list[Night]) -> str:
    header = (
        f"{'date':<10}  {'snore%':>7}  {'snore-min':>9}"
        f"  {'deep':>5}  {'light':>5}  {'rem':>5}  {'wake':>4}  {'hrv':>4}"
    )
    lines = [header]
    for n in nights:
        m = n.metrics
        lines.append(
            f"{n.date:<10}  {_pct(n.snore_share):>7}  {n.snore_minutes:>9.1f}"
            f"  {_cell(m['deep']):>5}  {_cell(m['light']):>5}  {_cell(m['rem']):>5}"
            f"  {_cell(m['awakenings']):>4}  {_cell(m['hrv']):>4}"
        )
    return "\n".join(lines)


def format_correlations(
    results: list[tuple[MetricSpec, float | None, int]],
) -> tuple[str, list[str]]:
    """The Spearman section plus the earned verdict lines: one verdict per
    metric clearing |rho| >= VERDICT_ABS_RHO with >= VERDICT_MIN_N nights, an
    honest small-N caveat otherwise, and skipped-not-faked missing metrics."""
    lines = ["SPEARMAN (snore share vs vendor metric)"]
    verdicts = []
    small_n = False
    for spec, rho, n in results:
        if rho is None:
            reason = f"N < {MIN_PAIRS}" if n < MIN_PAIRS else "no variance"
            lines.append(f"  {spec.label:<15} rho    —  N={n} ({reason}; skipped)")
            continue
        small_n = small_n or n < SMALL_N
        lines.append(f"  {spec.label:<15} rho {rho:+.2f}  N={n}")
        if abs(rho) >= VERDICT_ABS_RHO and n >= VERDICT_MIN_N:
            direction = "negative" if rho < 0 else "positive"
            verdicts.append(
                f"snore share vs {spec.label}: rho {rho:+.2f} over {n} nights"
                f" — strong {direction} association worth an experiment"
            )
    if small_n:
        lines.append(
            "  note: N < 10 — correlational, small sample;"
            " direction may flip with more nights"
        )
    return "\n".join(lines), verdicts


def format_report(nights: list[Night], min_nights: int = MIN_NIGHTS) -> str:
    if len(nights) < min_nights:
        return (
            f"not enough nights yet: {len(nights)} usable, correlations need"
            f" >= {min_nights} (COMPLETED/RECOVERED nights with vendor data)"
        )
    corr, verdicts = format_correlations(correlations(nights))
    lines = [
        (
            f"snoring vs Garmin vendor sleep over {len(nights)} nights"
            " (snore share = label-aware snore minutes / capture window)"
        ),
        format_nights(nights),
        (
            "units: vendor sleep stages arrive as SECONDS (measurement.unit), shown"
            " as minutes; awakenings = count; HRV = RMSSD ms"
        ),
        "",
        corr,
    ]
    if verdicts:
        lines.extend(verdicts)
    else:
        lines.append(
            f"not enough data yet: no metric clears |rho| >= {VERDICT_ABS_RHO:g}"
            f" over >= {VERDICT_MIN_N} nights — keep logging"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--db",
        metavar="PATH",
        help="read an already-pulled sleepanalysis.db (default: pull from the phone)",
    )
    parser.add_argument(
        "--serial", help="adb device serial (default: adb's only/auto device)"
    )
    parser.add_argument(
        "--min-nights",
        type=int,
        default=MIN_NIGHTS,
        help="minimum usable nights before correlating (default: %(default)s)",
    )
    args = parser.parse_args(argv)

    if args.db is not None:
        if not Path(args.db).is_file():
            print(f"error: db not found: {args.db}", file=sys.stderr)
            return 1
        conn = sqlite3.connect(args.db)
        try:
            print(format_report(collect_nights(conn), min_nights=args.min_nights))
        finally:
            conn.close()
        return 0

    serial = args.serial if args.serial is not None else detect_serial()
    with tempfile.TemporaryDirectory(prefix="night_trends_") as tmp:
        conn = sqlite3.connect(pull_db(serial, Path(tmp)))
        try:
            print(format_report(collect_nights(conn), min_nights=args.min_nights))
        finally:
            conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
