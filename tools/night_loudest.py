#!/usr/bin/env python3
"""Rank the loudest noises of a recorded night.

Pulls the app's SQLite database from a connected phone via adb (debug build,
so `run-as` can read the private app data) and prints the loudest acoustic
events ranked by peak dBFS, plus a per-hour noise profile. Optionally exports
the clip WAVs of the top events.

Stdlib only. Examples:

    uv run tools/night_loudest.py                      # latest night, top 15
    uv run tools/night_loudest.py --top 30 --export /tmp/clips
    uv run tools/night_loudest.py --session 6
    uv run tools/night_loudest.py --serial emulator-5554
"""

from __future__ import annotations

import argparse
import sqlite3
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

# Shared adb transfer helpers (exec-out, so no pty CRLF mangling, plus on-device
# byte-count and RIFF verification with cleanup on failure). Source of truth:
# export_fixtures.py. sys.path[0] is tools/ both as a script and under pytest.
from export_fixtures import pull_clip, pull_db

APP_PACKAGE = "com.sleepanalysis"


@dataclass(frozen=True)
class Event:
    """One acoustic event row, as needed for ranking/reporting."""

    start_ms: int
    time: str
    hour: str
    type: str
    peak_dbfs: float
    mean_dbfs: float
    duration_s: int
    tier: str
    clip_id: int | None
    confidence: float


def detect_serial() -> str | None:
    """First attached device with the app installed; None if unambiguous (0-1 devices)."""
    out = subprocess.run(
        ["adb", "devices"], check=True, capture_output=True, text=True
    ).stdout
    serials = [
        line.split()[0]
        for line in out.splitlines()[1:]
        if line.strip() and line.split()[1] == "device"
    ]
    if len(serials) <= 1:
        return serials[0] if serials else None
    for serial in serials:
        installed = subprocess.run(
            ["adb", "-s", serial, "shell", "pm", "list", "packages", APP_PACKAGE],
            capture_output=True,
            text=True,
            check=False,  # non-zero is just "device lacks the package"
        )
        if APP_PACKAGE in installed.stdout:
            return serial
    return None


def fetch_events(conn: sqlite3.Connection, session_id: int) -> list[Event]:
    rows = conn.execute(
        """
        SELECT startMs,
               datetime(startMs / 1000, 'unixepoch', 'localtime') AS t,
               strftime('%H', datetime(startMs / 1000, 'unixepoch', 'localtime')) AS h,
               type, peakDbfs, meanDbfs, durationMs / 1000, intensityTier,
               clipId, confidence
        FROM acoustic_event WHERE sessionId = ?
        """,
        (session_id,),
    ).fetchall()
    return [
        Event(
            start_ms=r[0],
            time=r[1],
            hour=r[2],
            type=r[3],
            peak_dbfs=r[4],
            mean_dbfs=r[5],
            duration_s=r[6],
            tier=r[7],
            clip_id=r[8],
            confidence=r[9],
        )
        for r in rows
    ]


def fetch_session(
    conn: sqlite3.Connection, session_id: int
) -> tuple[str, str, str] | None:
    row = conn.execute(
        """
        SELECT datetime(startedAt / 1000, 'unixepoch', 'localtime'),
               COALESCE(datetime(endedAt / 1000, 'unixepoch', 'localtime'), '?'),
               status
        FROM sleep_session WHERE id = ?
        """,
        (session_id,),
    ).fetchone()
    return (row[0], row[1], row[2]) if row else None


def latest_session_id(conn: sqlite3.Connection) -> int:
    return conn.execute(
        "SELECT id FROM sleep_session ORDER BY startedAt DESC LIMIT 1"
    ).fetchone()[0]


def rank_loudest(events: list[Event], limit: int) -> list[Event]:
    """The [limit] loudest events, loudest peak first (dBFS: louder = closer to 0)."""
    return sorted(events, key=lambda e: e.peak_dbfs, reverse=True)[:limit]


def hourly_peaks(events: list[Event]) -> list[tuple[str, int, float, int]]:
    """Per hour-of-day: (hour, event count, loudest peak dBFS, clip count), hour-sorted."""
    buckets: dict[str, list[Event]] = {}
    for e in events:
        buckets.setdefault(e.hour, []).append(e)
    return sorted(
        (
            h,
            len(es),
            max(e.peak_dbfs for e in es),
            sum(1 for e in es if e.clip_id is not None),
        )
        for h, es in buckets.items()
    )


def format_table(events: list[Event]) -> str:
    header = f"{'#':>3}  {'time':<16}  {'type':<12}  {'peak':>6}  {'mean':>6}  {'dur':>4}  {'tier':<5}  {'clip':<4}  conf"
    lines = [header]
    for i, e in enumerate(events, 1):
        clip = f"#{e.clip_id}" if e.clip_id is not None else "-"
        lines.append(
            f"{i:>3}  {e.time:<16}  {e.type:<12}  {e.peak_dbfs:>6.1f}  {e.mean_dbfs:>6.1f}"
            f"  {e.duration_s:>3}s  {e.tier:<5}  {clip:<4}  {e.confidence:.2f}"
        )
    return "\n".join(lines)


def format_hourly(rows: list[tuple[str, int, float, int]]) -> str:
    lines = [f"{'hour':>6}  {'events':>6}  {'max peak':>9}  {'clips':>5}"]
    for h, n, peak, clips in rows:
        lines.append(f"{h + ':00':>6}  {n:>6}  {peak:>9.1f}  {clips:>5}")
    return "\n".join(lines)


def export_clips(
    serial: str | None,
    conn: sqlite3.Connection,
    session_id: int,
    events: list[Event],
    out_dir: Path,
) -> int:
    """Copies the clip WAVs of [events] from the phone into [out_dir]; returns the count.

    Transfers via the verified shared pull_clip (exec-out + on-device byte count
    + RIFF check); a mangled or failed clip is skipped with a warning, not fatal.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    exported = 0
    for e in events:
        if e.clip_id is None:
            continue
        rel = conn.execute(
            "SELECT filePath FROM audio_clip WHERE id = ?", (e.clip_id,)
        ).fetchone()
        if rel is None:
            continue
        rel = rel[0]
        dest = out_dir / Path(rel).name
        try:
            pull_clip(serial, rel, dest)
        except (subprocess.CalledProcessError, ValueError) as exc:
            print(f"warning: FAILED {rel}: {exc}", file=sys.stderr)
            continue
        exported += 1
    return exported


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--serial", help="adb device serial (default: adb's only/auto device)"
    )
    parser.add_argument("--session", type=int, help="session id (default: latest)")
    parser.add_argument(
        "--top", type=int, default=15, help="how many loudest events to show"
    )
    parser.add_argument(
        "--export", metavar="DIR", help="also export the top events' clip WAVs here"
    )
    args = parser.parse_args(argv)

    with tempfile.TemporaryDirectory(prefix="night_loudest_") as tmp:
        work = Path(tmp)
        serial = args.serial if args.serial is not None else detect_serial()
        db_path = pull_db(serial, work)
        conn = sqlite3.connect(db_path)
        session_id = (
            args.session if args.session is not None else latest_session_id(conn)
        )
        session = fetch_session(conn, session_id)
        if session is None:
            print(f"error: session {session_id} not found", file=sys.stderr)
            return 1
        events = fetch_events(conn, session_id)
        top = rank_loudest(events, args.top)

        print(f"session {session_id}: {session[0]} -> {session[1]} ({session[2]})")
        print(f"\nTOP {len(top)} LOUDEST of {len(events)} events")
        print(format_table(top))
        print("\nPEAK PER HOUR (max peak dBFS)")
        print(format_hourly(hourly_peaks(events)))

        if args.export:
            n = export_clips(serial, conn, session_id, top, Path(args.export))
            print(f"\nexported {n} clip(s) to {args.export}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
