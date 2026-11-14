#!/usr/bin/env python3
"""Nightly battery drain from a session log.

Reads a session log (the app's per-night `files/logs/session_<id>.log`, pulled
via `run-as cat`) and summarizes device battery: the app appends
`batt=NN% charging=true|false` to every 15-minute heartbeat and the stop line.
Drain is only meaningful over `charging=false` stretches — a night on the
charger (the normal case) is reported as plugged in, not as 0% drain.

Stdlib only. Examples:

    uv run tools/night_battery.py ~/sleep-local/morning/<run>/session_14.log
    adb exec-out run-as com.sleepanalysis cat files/logs/session_14.log \\
        | uv run tools/night_battery.py -
"""

from __future__ import annotations

import argparse
import itertools
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

# BatteryStatus.format() writes both fields together; the null case renders
# `batt=? charging=?` — note NO `%` after the ?.
BATT_RE = re.compile(r"batt=(\d+|\?)%? charging=(true|false|\?)")

# Device-drain regression tripwire. NOT the P0 gate: the ≤10%/8h criterion in
# docs/NEXT_STEPS.md is against the APP's attributed usage (Android Settings →
# Battery / batterystats, read before the next full charge resets it), while
# this number is total device drain, whose realistic floor on the A059 for a
# continuous-recording night is well above 10% (SoC held awake + mic path).
GATE_PCT_PER_8H = 25.0


@dataclass(frozen=True)
class Reading:
    """One battery observation from a heartbeat/stop log line."""

    ts: datetime
    batt: int | None  # None when the log says batt=?
    charging: bool | None  # None when the log says charging=?


@dataclass(frozen=True)
class Stretch:
    """A run of consecutive unplugged readings; drain needs >= 2 readings."""

    readings: list[Reading] = field(default_factory=list)

    @property
    def hours(self) -> float:
        span = (self.readings[-1].ts - self.readings[0].ts).total_seconds()
        return span / 3600.0

    @property
    def drain(self) -> int | None:
        """first - last battery %; None without an interval (single reading)."""
        if len(self.readings) < 2:
            return None
        first, last = self.readings[0].batt, self.readings[-1].batt
        if first is None or last is None:
            return None
        return first - last

    @property
    def pct_per_8h(self) -> float | None:
        """Gate-normalized rate; None when no measurable drain or zero span."""
        if self.drain is None or self.hours <= 0:
            return None
        return self.drain / self.hours * 8.0


@dataclass(frozen=True)
class BatterySummary:
    readings: int
    skipped_placeholders: int
    stretches: list[Stretch]
    clock_backwards: bool

    @property
    def has_telemetry(self) -> bool:
        return self.readings > 0 or self.skipped_placeholders > 0

    @property
    def unplugged_all_night(self) -> bool:
        """Every reading (>= 1) sits in one stretch — no charger contact seen."""
        return (
            bool(self.stretches)
            and sum(len(s.readings) for s in self.stretches) == self.readings
        )


def parse_log(text: str) -> list[Reading]:
    """Battery readings in log order; lines without a battery field are ignored.

    Unparseable timestamps drop the line entirely (the drain math needs order).
    """
    readings = []
    for line in text.splitlines():
        m = BATT_RE.search(line)
        if m is None:
            continue
        try:
            ts = datetime.fromisoformat(line.split()[0])
        except (IndexError, ValueError):
            continue
        batt = int(m.group(1)) if m.group(1) != "?" else None
        charging = (
            {"true": True, "false": False}[m.group(2)] if m.group(2) != "?" else None
        )
        readings.append(Reading(ts=ts, batt=batt, charging=charging))
    return readings


def summarize(readings: list[Reading]) -> BatterySummary:
    """Per-stretch drain over charging=false runs, in timestamp order.

    A charger contact (charging=true) or unknown state ends a stretch. Clock-jump
    detection runs on the raw log order (SessionLogger's own warning is about
    adjacent lines); the drain math uses the time-sorted readings.
    """
    clock_backwards = any(
        later.ts < earlier.ts for earlier, later in itertools.pairwise(readings)
    )
    ordered = sorted(readings, key=lambda r: r.ts)
    stretches: list[Stretch] = []
    current: list[Reading] = []
    for r in ordered:
        if r.charging is False:
            current.append(r)
        else:
            if current:
                stretches.append(Stretch(current))
                current = []
    if current:
        stretches.append(Stretch(current))
    return BatterySummary(
        readings=len(readings),
        skipped_placeholders=sum(1 for r in readings if r.batt is None),
        stretches=stretches,
        clock_backwards=clock_backwards,
    )


def _fmt_h(hours: float) -> str:
    h, rem = int(hours), hours - int(hours)
    m = round(rem * 60)
    h, m = (h + 1, 0) if m == 60 else (h, m)
    return f"{h}h{m:02d}m"


def _fmt_ts(ts: datetime) -> str:
    return ts.strftime("%H:%M:%S")


def _stretch_line(stretch: Stretch) -> str:
    first, last = stretch.readings[0], stretch.readings[-1]
    base = (
        f"unplugged {_fmt_ts(first.ts)}-{_fmt_ts(last.ts)} UTC"
        f" ({_fmt_h(stretch.hours)})"
    )
    if len(stretch.readings) == 1:
        return f"{base}: single reading {first.batt}% (no interval)"
    if stretch.drain is None:
        return f"{base}: no measurable drain (endpoint unreadable)"
    # Delta display: a drop reads negative (93% -> 90% (-3%)), matching the
    # first->last direction; `drain` itself stays "amount lost" for the gate.
    line = f"{base}: {first.batt}% -> {last.batt}% ({-stretch.drain:+d}%)"
    if stretch.pct_per_8h is not None:
        line += f" = {stretch.pct_per_8h:.1f}%/8h"
    return line


def format_report(summary: BatterySummary, gate: float = GATE_PCT_PER_8H) -> str:
    if not summary.has_telemetry:
        return "no battery telemetry in this log (build predates the batt= field)"
    lines = [f"battery readings: {summary.readings}"]
    if summary.skipped_placeholders:
        lines.append(f"unreadable (batt=?): {summary.skipped_placeholders}")
    if summary.clock_backwards:
        lines.append("note: timestamps step backwards somewhere (device clock jump?)")
    if not summary.stretches:
        if summary.skipped_placeholders == summary.readings:
            lines.append(
                f"charging state unknown ({summary.skipped_placeholders} unreadable"
                " batt=?) — drain not measurable"
            )
        elif summary.skipped_placeholders:
            lines.append(
                f"no unplugged reading observed ({summary.skipped_placeholders}"
                " unreadable batt=?) — drain not measurable"
            )
        else:
            lines.append("plugged in all night — drain not measurable")
        return "\n".join(lines)
    for s in summary.stretches:
        lines.append(_stretch_line(s))
    if not summary.unplugged_all_night:
        lines.append("note: partially plugged — rates are per stretch, not per night")
    longest = max(summary.stretches, key=lambda s: s.hours)
    if longest.pct_per_8h is not None:
        verdict = "OK" if longest.pct_per_8h <= gate else "OVER"
        lines.append(
            f"device-drain tripwire (longest stretch {_fmt_h(longest.hours)}):"
            f" {longest.pct_per_8h:.1f}%/8h vs <= {gate:g}% — {verdict}"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "log",
        help="session log line path, or - for stdin (adb exec-out run-as … cat)",
    )
    parser.add_argument(
        "--gate",
        type=float,
        default=GATE_PCT_PER_8H,
        help="device-drain tripwire threshold in %/8h (default: %(default)s)",
    )
    args = parser.parse_args(argv)
    text = (
        sys.stdin.read()
        if args.log == "-"
        else Path(args.log).read_text(errors="replace")
    )
    print(format_report(summarize(parse_log(text)), gate=args.gate))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
