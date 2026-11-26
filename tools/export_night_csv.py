#!/usr/bin/env python3
"""Export one night's session data to CSVs for analysis/night_report.py.

Pulls the app's SQLite database from a connected phone via adb (debug build, so
`run-as` can read the private app data) and writes per-session CSVs into --out:

  events_<sid>.csv               one row per acoustic_event
  session_<sid>.csv              session row + captureStats JSON flattened to key columns
  measurements_<sid>.csv         one row per measurement
  quiet_gaps_<sid>.csv           one row per quiet_gap
  sleep_stage_<sid>.csv          one row per sleep_stage; on pre-v14 schemas without a
                                 source column it is emitted as GARMIN (only the Garmin
                                 import wrote stage rows before v14)
  garmin_sleep_window_<sid>.csv  Garmin's own sleep window (epoch ms) + duration, stage
                                 seconds and awakeCount, parsed from the verbatim
                                 dailySleep raw_payload of the session's wake date —
                                 the file is omitted when there is no such payload

It also reports the phone-vs-vendor overlap: how much of Garmin's sleep window the
acoustic capture covered. --out must resolve outside this repository — exported
personal night data never lives in the repo (docs/PRIVACY_AND_REGULATORY.md §2).

Binary transfers reuse the verified helpers from export_fixtures.py (`adb exec-out`,
so no pty CRLF mangling, plus on-device byte-count and RIFF verification).

Stdlib only. Examples:

    uv run tools/export_night_csv.py --out /tmp/night-data          # latest session
    uv run tools/export_night_csv.py --session 5 --out /tmp/night5
    uv run tools/export_night_csv.py --session 5 --out /tmp/n5 --keep-db \
        --with-clips /tmp/n5/clips
    uv run tools/export_night_csv.py --serial emulator-5554 --out /tmp/n5
"""

from __future__ import annotations

import argparse
import contextlib
import csv
import json
import math
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from collections import deque
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

# Shared adb transfer helpers (exec-out, so no pty CRLF mangling, plus on-device
# byte-count and RIFF verification with cleanup on failure). Source of truth:
# export_fixtures.py. sys.path[0] is tools/ both as a script and under pytest.
from export_fixtures import DB_NAME, pull_clip, pull_db

# Shared pure helpers (detect_serial / latest_session_id / clip join) live in
# analysis/tooling.py; analysis/ is not a package, hence the sys.path insert.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "analysis"))
from tooling import detect_serial, latest_session_id

APP_PACKAGE = "com.sleepanalysis"
GARMIN_SOURCE = "GARMIN"
DAILY_SLEEP_ENDPOINT = "/sleep-service/sleep/dailySleep"

# Garmin keys a night's payload by the LOCAL WAKE date — the morning the night ends
# (app NightDates.garminWakeDate); open sessions fall back to startedAt + 12 h.
OPEN_SESSION_WAKE_OFFSET_MS = 12 * 60 * 60 * 1000

# Bounded depth of the tolerant payload key search, mirroring the app's
# GarminJsonParsers (MAX_DEPTH = 5) so exports parse payloads like report time does.
MAX_SEARCH_DEPTH = 5

SESSION_COLUMNS = [
    "id",
    "startedAt",
    "endedAt",
    "status",
    "timeZone",
    "morningNotes",
    "resumedAfterInterruption",
    "createdAt",
]

EVENTS_COLUMNS = [
    "id",
    "start_ms",
    "end_ms",
    "duration_ms",
    "type",
    "user_label",
    "peak_dbfs",
    "mean_dbfs",
    "tier",
    "confidence",
    "clip_id",
]

MEASUREMENTS_COLUMNS = [
    "id",
    "timestamp",
    "sourceType",
    "method",
    "isClinical",
    "measurementType",
    "tier",
    "valueNum",
    "valueText",
    "unit",
    "confidence",
    "modelVersion",
    "periodStart",
    "periodEnd",
    "notes",
    "createdAt",
]

QUIET_GAPS_COLUMNS = ["id", "startMs", "endMs", "durationMs", "kind", "meanDbfs"]

SLEEP_STAGE_COLUMNS = [
    "id",
    "sessionId",
    "startMs",
    "endMs",
    "stage",
    "source",
    "createdAt",
]

GARMIN_COLUMNS = [
    "sleepStartTimestampGMT",
    "sleepEndTimestampGMT",
    "sleepTimeSeconds",
    "deepSleepSeconds",
    "lightSleepSeconds",
    "remSleepSeconds",
    "awakeSleepSeconds",
    "awakeCount",
]


@dataclass(frozen=True)
class NightExport:
    """What one export produced — the files plus the numbers the summary reports."""

    session: dict
    files: tuple[Path, ...]
    n_events: int
    events_by_type: dict[str, int]
    n_measurements: int
    n_quiet_gaps: int
    n_sleep_stages: int
    capture_start_ms: int
    capture_end_ms: int | None
    wake_date: str
    garmin: dict | None
    garmin_note: str | None


def repo_root() -> Path:
    """The repository root — tools/ lives directly under it."""
    return Path(__file__).resolve().parent.parent


def resolve_out_dir(out: str) -> Path:
    """Resolved output dir; refuses paths inside this repository.

    Exported night data is personal (docs/PRIVACY_AND_REGULATORY.md §2): it must
    never land in the repo, not even unstaged, so the guard is absolute and also
    covers not-yet-existing paths (resolved, not stat-ed).
    """
    root = repo_root()
    resolved = Path(out).expanduser().resolve()
    if resolved == root or root in resolved.parents:
        raise ValueError(
            f"{out} resolves to {resolved}, inside this repository ({root}) — "
            "exported night data never lives in the repo "
            "(docs/PRIVACY_AND_REGULATORY.md §2)"
        )
    return resolved




def parse_capture_stats(capture_stats: str | None) -> dict | None:
    """The captureStats JSON object, or None when absent/malformed (tolerant, app parity)."""
    if not capture_stats or not capture_stats.strip():
        return None
    try:
        parsed = json.loads(capture_stats)
    except ValueError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _stats_cell(value: object) -> str:
    if value is None:
        return ""
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, (dict, list)):
        return json.dumps(value, separators=(",", ":"))
    return str(value)


def flatten_capture_stats(capture_stats: str | None) -> dict[str, str]:
    """captureStats JSON -> flat string columns (keys as-is; nested values as JSON).

    A malformed payload is kept under `capture_stats_raw` — export never silently
    drops data. A blank string carries no data to keep, so it flattens to {}.
    """
    if capture_stats is not None and not capture_stats.strip():
        return {}
    parsed = parse_capture_stats(capture_stats)
    if parsed is None:
        return {} if not capture_stats else {"capture_stats_raw": capture_stats}
    return {key: _stats_cell(value) for key, value in parsed.items()}


def capture_start_ms(session: dict) -> int:
    """When audio capture actually began (captureStats.capture_started_at), else session start."""
    parsed = parse_capture_stats(session["captureStats"])
    started = find_number(parsed, "capture_started_at") if parsed else None
    return int(started) if started is not None else int(session["startedAt"])


def fetch_session(conn: sqlite3.Connection, session_id: int) -> dict | None:
    """The sleep_session row (incl. raw captureStats) as a dict, or None."""
    cols = [*SESSION_COLUMNS, "captureStats"]
    row = conn.execute(
        f"SELECT {', '.join(cols)} FROM sleep_session WHERE id = ?", (session_id,)
    ).fetchone()
    if row is None:
        return None
    return dict(zip(cols, row))


def fetch_events(conn: sqlite3.Connection, session_id: int) -> list[dict]:
    rows = conn.execute(
        """
        SELECT e.id, e.startMs, e.endMs, e.durationMs, e.type, e.userLabel,
               e.peakDbfs, e.meanDbfs, e.intensityTier, e.confidence, e.clipId
        FROM acoustic_event e
        WHERE e.sessionId = ?
        ORDER BY e.startMs, e.id
        """,
        (session_id,),
    ).fetchall()
    return [dict(zip(EVENTS_COLUMNS, r)) for r in rows]


def fetch_measurements(conn: sqlite3.Connection, session_id: int) -> list[dict]:
    rows = conn.execute(
        f"""
        SELECT {", ".join(MEASUREMENTS_COLUMNS)}
        FROM measurement
        WHERE sessionId = ?
        ORDER BY timestamp, id
        """,
        (session_id,),
    ).fetchall()
    return [dict(zip(MEASUREMENTS_COLUMNS, r)) for r in rows]


def fetch_quiet_gaps(conn: sqlite3.Connection, session_id: int) -> list[dict]:
    rows = conn.execute(
        f"""
        SELECT {", ".join(QUIET_GAPS_COLUMNS)}
        FROM quiet_gap
        WHERE sessionId = ?
        ORDER BY startMs, id
        """,
        (session_id,),
    ).fetchall()
    return [dict(zip(QUIET_GAPS_COLUMNS, r)) for r in rows]


def fetch_sleep_stages(conn: sqlite3.Connection, session_id: int) -> list[dict]:
    """sleep_stage rows for the session, in timeline order.

    Schema-tolerant across v13/v14: the `source` column only exists from v14, so
    on older DBs it is emitted as GARMIN — pre-v14 stage rows were written
    exclusively by the Garmin import path.
    """
    cols = {row[1] for row in conn.execute("PRAGMA table_info(sleep_stage)")}
    source_expr = "source" if "source" in cols else f"'{GARMIN_SOURCE}'"
    rows = conn.execute(
        f"""
        SELECT id, sessionId, startMs, endMs, stage, {source_expr} AS source, createdAt
        FROM sleep_stage
        WHERE sessionId = ?
        ORDER BY startMs, id
        """,
        (session_id,),
    ).fetchall()
    return [dict(zip(SLEEP_STAGE_COLUMNS, r)) for r in rows]


def garmin_wake_date(started_at_ms: int, ended_at_ms: int | None, tz_id: str) -> date:
    """The local wake calendar day — the join key for Garmin payloads (app NightDates).

    A session starting on day D and ending on day D+1 is Garmin's "day D+1" entry;
    open sessions anchor on startedAt + 12 h, which lands on the wake date for any
    realistic sleep window.
    """
    wake_ms = (
        ended_at_ms
        if ended_at_ms is not None
        else started_at_ms + OPEN_SESSION_WAKE_OFFSET_MS
    )
    return datetime.fromtimestamp(wake_ms // 1000, tz=ZoneInfo(tz_id)).date()


def find_number(
    node: object, *keys: str, max_depth: int = MAX_SEARCH_DEPTH
) -> float | None:
    """Breadth-first bounded search for the first finite number under any of [keys].

    Mirrors the app's GarminJsonParsers.findDouble so this export parses the same
    verbatim payloads exactly like report time does (booleans are not numbers).
    """
    queue: deque[tuple[object, int]] = deque([(node, 0)])
    while queue:
        current, depth = queue.popleft()
        if isinstance(current, dict):
            for key in keys:
                value = current.get(key)
                if (
                    isinstance(value, (int, float))
                    and not isinstance(value, bool)
                    and math.isfinite(value)
                ):
                    return float(value)
            if depth < max_depth:
                queue.extend((child, depth + 1) for child in current.values())
        elif isinstance(current, list) and depth < max_depth:
            queue.extend((child, depth + 1) for child in current)
    return None


def _whole(value: float | None) -> int | float | None:
    """Numeric CSV cell: integral floats as ints, other floats as-is; None stays None."""
    if value is None:
        return None
    return int(value) if value.is_integer() else value


def parse_garmin_sleep_row(payload: str) -> dict | None:
    """The garmin_sleep_window_<sid>.csv row; None when the payload is unusable.

    Same key picks as the app (GarminJsonParsers.parseSleepWindow/parseDailySleep):
    GMT window epochs with the Local mirrors as fallback, degenerate windows (end
    <= start) rejected, then duration / stage seconds / awakening count.
    """
    try:
        root = json.loads(payload)
    except ValueError:
        return None
    start = find_number(root, "sleepStartTimestampGMT", "sleepStartTimestampLocal")
    end = find_number(root, "sleepEndTimestampGMT", "sleepEndTimestampLocal")
    if start is None or end is None or end <= start:
        return None
    row: dict = {
        "sleepStartTimestampGMT": int(start),
        "sleepEndTimestampGMT": int(end),
    }
    for key in (
        "sleepTimeSeconds",
        "deepSleepSeconds",
        "lightSleepSeconds",
        "remSleepSeconds",
        "awakeSleepSeconds",
        "awakeCount",
    ):
        row[key] = _whole(find_number(root, key))
    return row


def fetch_garmin_payload(
    conn: sqlite3.Connection, session: dict
) -> tuple[str, str | None]:
    """(wake date ISO, verbatim dailySleep payload) — payload None when not stored.

    Payloads are keyed by (source, date, endpoint) with date = the session's local
    wake date, exactly like the app's report-time lookup.
    """
    wake = garmin_wake_date(
        session["startedAt"], session["endedAt"], str(session["timeZone"])
    )
    row = conn.execute(
        "SELECT payload FROM raw_payload WHERE source = ? AND date = ? AND endpoint = ?",
        (GARMIN_SOURCE, wake.isoformat(), DAILY_SLEEP_ENDPOINT),
    ).fetchone()
    return wake.isoformat(), row[0] if row else None


def overlap_percent(
    capture_start_ms: int,
    capture_end_ms: int,
    garmin_start_ms: int,
    garmin_end_ms: int,
) -> float:
    """How much of the vendor sleep window the phone captured, in percent.

    Intersection of [captureStart, captureEnd] with [garminStart, garminEnd] over
    the Garmin window length (same intersection as the app's ReportAssembler).
    """
    overlap = max(
        0, min(capture_end_ms, garmin_end_ms) - max(capture_start_ms, garmin_start_ms)
    )
    length = garmin_end_ms - garmin_start_ms
    if length <= 0:
        return 0.0
    return overlap / length * 100


def write_csv(path: Path, columns: list[str], rows: list[dict]) -> Path:
    """One CSV with a fixed header row; None cells come out empty.

    Cells are written verbatim: free-text columns (morningNotes, measurement notes) could
    carry spreadsheet formula characters (=, +, -, @). Acceptable for self-produced data
    opened locally; neutralize here if these CSVs are ever shared.
    """
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
    return path


def export_csvs(
    conn: sqlite3.Connection, session_id: int, out_dir: Path
) -> NightExport:
    """Writes all per-session CSVs into [out_dir]; returns the summary numbers.

    Raises ValueError when [session_id] has no sleep_session row.
    """
    session = fetch_session(conn, session_id)
    if session is None:
        raise ValueError(f"session {session_id} not found")
    out_dir.mkdir(parents=True, exist_ok=True)

    events = fetch_events(conn, session_id)
    measurements = fetch_measurements(conn, session_id)
    gaps = fetch_quiet_gaps(conn, session_id)
    stages = fetch_sleep_stages(conn, session_id)
    stats = flatten_capture_stats(session["captureStats"])
    session_row = {**{c: session[c] for c in SESSION_COLUMNS}, **stats}

    files = [
        write_csv(out_dir / f"events_{session_id}.csv", EVENTS_COLUMNS, events),
        write_csv(
            out_dir / f"session_{session_id}.csv",
            [*SESSION_COLUMNS, *stats],
            [session_row],
        ),
        write_csv(
            out_dir / f"measurements_{session_id}.csv",
            MEASUREMENTS_COLUMNS,
            measurements,
        ),
        write_csv(out_dir / f"quiet_gaps_{session_id}.csv", QUIET_GAPS_COLUMNS, gaps),
        write_csv(
            out_dir / f"sleep_stage_{session_id}.csv", SLEEP_STAGE_COLUMNS, stages
        ),
    ]

    wake_iso, payload = fetch_garmin_payload(conn, session)
    garmin_row = parse_garmin_sleep_row(payload) if payload is not None else None
    garmin_note: str | None = None
    if garmin_row is not None:
        files.append(
            write_csv(
                out_dir / f"garmin_sleep_window_{session_id}.csv",
                GARMIN_COLUMNS,
                [garmin_row],
            )
        )
    elif payload is None:
        garmin_note = f"no {GARMIN_SOURCE} {DAILY_SLEEP_ENDPOINT} payload for wake date {wake_iso}"
    else:
        garmin_note = (
            f"{GARMIN_SOURCE} dailySleep payload for wake date {wake_iso} has no "
            "usable sleep window"
        )

    events_by_type: dict[str, int] = {}
    for e in events:
        events_by_type[e["type"]] = events_by_type.get(e["type"], 0) + 1

    return NightExport(
        session=session,
        files=tuple(files),
        n_events=len(events),
        events_by_type=events_by_type,
        n_measurements=len(measurements),
        n_quiet_gaps=len(gaps),
        n_sleep_stages=len(stages),
        capture_start_ms=capture_start_ms(session),
        capture_end_ms=session["endedAt"],
        wake_date=wake_iso,
        garmin=garmin_row,
        garmin_note=garmin_note,
    )


def export_clips(
    serial: str | None, conn: sqlite3.Connection, session_id: int, out_dir: Path
) -> int:
    """Copies the session's clip WAVs from the phone into [out_dir]; returns the count.

    Same verified transfer as night_loudest (exec-out + on-device byte count +
    RIFF check); a mangled or missing clip is skipped with a warning, not fatal.

    Also writes manifest_<sid>.json next to the WAVs — the verified event↔clip
    join (event id, type, timing, peak, per-file export outcome) so the analysis
    never re-derives the association from clip filenames (a naming scheme is a
    convention, not a contract; the Nov 9 regex break proved it).
    """
    rels = conn.execute(
        """
        SELECT e.id, e.type, e.startMs, e.endMs, e.peakDbfs, e.confidence, c.filePath
        FROM acoustic_event e
        JOIN audio_clip c ON (c.eventId = e.id OR c.id = e.clipId)
        WHERE e.sessionId = ? AND c.filePath IS NOT NULL
        ORDER BY e.startMs, e.id
        """,
        (session_id,),
    ).fetchall()
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = []
    exported = 0
    for event_id, event_type, start_ms, end_ms, peak_dbfs, confidence, rel in rels:
        entry = {
            "event_id": event_id,
            "event_type": event_type,
            "start_ms": start_ms,
            "end_ms": end_ms,
            "peak_dbfs": peak_dbfs,
            "confidence": confidence,
            "path": Path(rel).name,
            "exported": False,
            "error": None,
        }
        manifest.append(entry)
        dest = out_dir / entry["path"]
        try:
            pull_clip(serial, rel, dest)
        except (subprocess.CalledProcessError, ValueError) as exc:
            entry["error"] = str(exc)
            print(f"warning: FAILED {rel}: {exc}", file=sys.stderr)
            continue
        entry["exported"] = True
        exported += 1
    manifest_path = out_dir / f"manifest_{session_id}.json"
    manifest_path.write_text(
        json.dumps({"source_session": session_id, "clips": manifest}, indent=2),
        encoding="utf-8",
    )
    print(
        f"wrote {manifest_path.name} ({len(manifest)} clip entr{'y' if len(manifest) == 1 else 'ies'})"
    )
    return exported


def loc(ms: int, tz: ZoneInfo) -> datetime:
    return datetime.fromtimestamp(ms // 1000, tz=tz)


def hms(seconds: float) -> str:
    s = round(seconds)  # round(x) with no ndigits already returns int
    return f"{s // 3600}h{s % 3600 // 60:02d}m{s % 60:02d}s"


def print_summary(exp: NightExport) -> None:
    s = exp.session
    tz = ZoneInfo(str(s["timeZone"]))
    end_txt = (
        f"{loc(s['endedAt'], tz):%Y-%m-%d %H:%M:%S}"
        if s["endedAt"] is not None
        else "?"
    )
    print(
        f"session {s['id']}: {loc(s['startedAt'], tz):%Y-%m-%d %H:%M:%S} -> {end_txt}"
        f" ({s['status']}, {s['timeZone']})"
    )
    ce = exp.capture_end_ms
    if ce is not None:
        print(
            f"capture window {loc(exp.capture_start_ms, tz):%H:%M:%S}-"
            f"{loc(ce, tz):%H:%M:%S} ({hms((ce - exp.capture_start_ms) / 1000)})"
        )
    else:
        print("capture window open (no endedAt yet)")
    print(f"events: {exp.n_events} total")
    for event_type in sorted(exp.events_by_type):
        print(f"  {event_type}: {exp.events_by_type[event_type]}")
    print(
        f"measurements: {exp.n_measurements}, quiet gaps: {exp.n_quiet_gaps},"
        f" sleep stages: {exp.n_sleep_stages}"
    )
    print(f"wrote {len(exp.files)} CSV file(s) to the output directory:")
    for path in exp.files:
        print(f"  {path.name}")

    if exp.garmin is not None and ce is not None:
        g_start = int(exp.garmin["sleepStartTimestampGMT"])
        g_end = int(exp.garmin["sleepEndTimestampGMT"])
        pct = overlap_percent(exp.capture_start_ms, ce, g_start, g_end)
        print(
            f"garmin sleep window {loc(g_start, tz):%H:%M:%S}-"
            f"{loc(g_end, tz):%H:%M:%S}: phone captured {pct:.1f}% of the vendor window"
        )
    else:
        note = exp.garmin_note
        if note is None:
            note = "capture window has no end (session still open)"
        print(f"garmin sleep window: n/a ({note})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--session", type=int, help="session id (default: latest)")
    parser.add_argument(
        "--out",
        metavar="DIR",
        required=True,
        help="output directory for the CSVs (must be outside this repository)",
    )
    parser.add_argument(
        "--serial", help="adb device serial (default: adb's only/auto device)"
    )
    parser.add_argument(
        "--keep-db",
        action="store_true",
        help="copy the pulled SQLite DB next to the CSVs instead of discarding it",
    )
    parser.add_argument(
        "--with-clips",
        metavar="DIR",
        help="also export this session's clip WAVs here (must be outside the repo)",
    )
    args = parser.parse_args(argv)

    # Fail fast on the privacy guard, before any adb traffic or file writes.
    try:
        out_dir = resolve_out_dir(args.out)
        clips_dir = resolve_out_dir(args.with_clips) if args.with_clips else None
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory(prefix="export_night_csv_") as tmp:
        work = Path(tmp)
        serial = args.serial if args.serial is not None else detect_serial()
        db_path = pull_db(serial, work)
        with contextlib.closing(sqlite3.connect(db_path)) as conn:
            try:
                session_id = (
                    args.session
                    if args.session is not None
                    else latest_session_id(conn)
                )
                exp = export_csvs(conn, session_id, out_dir)
            except (ValueError, OSError) as exc:
                print(f"error: {exc}", file=sys.stderr)
                return 1
            print_summary(exp)

            if clips_dir is not None:
                n = export_clips(serial, conn, session_id, clips_dir)
                print(f"exported {n} clip WAV(s) to {args.with_clips}")
            if args.keep_db:
                # Merge the WAL into the main db file so the kept copy is self-contained.
                conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                shutil.copy2(db_path, out_dir / DB_NAME)
                print(f"kept database copy: {out_dir / DB_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
