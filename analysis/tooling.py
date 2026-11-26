"""Shared helpers for the tools/ scripts, moved out of per-tool copies.

Stdlib-only. detect_serial/latest_session_id/union_duration_ms and the
belt-and-braces clip join each existed as identical copies in two to four
tools (see docs/functional_spec/10-python-tooling-ci.md N.7); this module is
now the single source. Importable from tools/ via an explicit sys.path insert
(tools/ has no package layout and pytest's prepend mode only puts tools/ on
sys.path).
"""

from __future__ import annotations

import sqlite3
import subprocess

APP_PACKAGE = "com.sleepanalysis"

# Belt-and-braces clip join (morning_report.sh step 4 / export_night_csv
# parity): matches clips linked by either direction. Valid because the app
# back-fills audio_clip.eventId after inserting both rows
# (SessionRepository.insertEvent -> clipDao.setEventId); the OR also catches
# rows where only the legacy clipId link exists.
CLIP_JOIN_SQL = "audio_clip c ON (c.eventId = e.id OR c.id = e.clipId)"


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


def latest_session_id(conn: sqlite3.Connection) -> int:
    """The most recently started session (same one-line pick every tool uses);
    ValueError when the database has no sessions at all."""
    row = conn.execute(
        "SELECT id FROM sleep_session ORDER BY startedAt DESC LIMIT 1"
    ).fetchone()
    if row is None:
        raise ValueError("no sessions in the database")
    return row[0]


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
