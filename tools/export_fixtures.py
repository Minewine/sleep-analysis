#!/usr/bin/env python3
"""Export labeled clip WAVs as a fixture set for offline detector eval.

Pulls the app's SQLite database and clip WAVs from a connected phone via adb
(debug build, so `run-as` can read the private app data) and writes a fixture
directory per session: `evt_<start_ms>.wav` clips plus a `manifest.json` the
unit-test fixture runner (`com.sleepanalysis.monitoring.eval.FixtureEvalRunTest`)
consumes. Clips are already software-gained at capture — replay must not
re-gain them (docs/NEXT_STEPS.md P2).

Binary transfers use `adb exec-out` (raw stdout, no PTY), because plain
`adb shell cat` runs through a pty that mangles 0x0a/0x0d bytes. Each pulled
clip is additionally verified against the on-device byte count
(`run-as ... sh -c 'wc -c < file'`, executed inside the app sandbox — the
device-level shell cannot open app-private paths); on mismatch the pulled file
is deleted and the clip recorded as failed.

Stdlib only. Examples:

    uv run tools/export_fixtures.py                     # latest session, all clips
    uv run tools/export_fixtures.py --session 6         # one session
    uv run tools/export_fixtures.py --min-labels 1      # only user-labeled clips
    uv run tools/export_fixtures.py --serial emulator-5554 --out /tmp/fix
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import subprocess
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

APP_PACKAGE = "com.sleepanalysis"
DB_NAME = "sleepanalysis.db"
CLIPS_DIR = "files/clips"

QUERY = """
    SELECT e.id, e.sessionId, e.type, e.startMs, e.offsetMs, e.durationMs,
           e.peakDbfs, e.meanDbfs, e.intensityTier, e.confidence,
           e.userLabel, e.labeledAt, e.featuresJson, c.filePath
    FROM acoustic_event e
    LEFT JOIN audio_clip c ON c.eventId = e.id
    WHERE e.sessionId = ? AND c.filePath IS NOT NULL
"""


def adb_device_args(serial: str | None) -> list[str]:
    return ["adb", *([] if serial is None else ["-s", serial])]


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


def pull_db(serial: str | None, work_dir: Path) -> Path:
    """Copies the app database (+ WAL) from the phone into [work_dir]; returns the db path."""
    args = adb_device_args(serial)
    db_path = work_dir / DB_NAME
    wal_path = work_dir / (DB_NAME + "-wal")
    with db_path.open("wb") as f:
        # exec-out: raw binary stdout — `adb shell` runs a pty that would CRLF-mangle the db.
        subprocess.run(
            [*args, "exec-out", "run-as", APP_PACKAGE, "cat", f"databases/{DB_NAME}"],
            check=True,
            stdout=f,
        )
    wal = subprocess.run(
        [*args, "exec-out", "run-as", APP_PACKAGE, "cat", f"databases/{DB_NAME}-wal"],
        capture_output=True,
        check=False,  # WAL may not exist; absence is fine
    )
    if wal.returncode == 0 and wal.stdout:
        wal_path.write_bytes(wal.stdout)
    return db_path


def fetch_rows(conn: sqlite3.Connection, session_id: int) -> list[dict]:
    """All labeled-or-not clip-backed acoustic events of [session_id], as dicts."""
    columns = [
        "event_id",
        "session_id",
        "type",
        "start_ms",
        "offset_ms",
        "duration_ms",
        "peak_dbfs",
        "mean_dbfs",
        "intensity_tier",
        "confidence",
        "user_label",
        "labeled_at",
        "features_json",
        "file_path",
    ]
    return [
        dict(zip(columns, row)) for row in conn.execute(QUERY, (session_id,)).fetchall()
    ]


def clip_name(session_id: int, start_ms: int) -> str:
    """Fixture-relative clip path — same name scheme the app writes on-device."""
    return f"session_{session_id}/evt_{start_ms}.wav"


def is_riff(data: bytes) -> bool:
    return data[:4] == b"RIFF"


def build_entry(row: dict, clip_rel: str) -> dict:
    """One manifest entry; features_json is embedded parsed or null (never raw string)."""
    features = None
    raw = row["features_json"]
    if raw:
        try:
            features = json.loads(raw)
        except (ValueError, TypeError):
            features = None
    return {
        "event_id": row["event_id"],
        "session_id": row["session_id"],
        "type": row["type"],
        "user_label": row["user_label"],
        "start_ms": row["start_ms"],
        "offset_ms": row["offset_ms"],
        "duration_ms": row["duration_ms"],
        "peak_dbfs": row["peak_dbfs"],
        "mean_dbfs": row["mean_dbfs"],
        "features": features,
        "clip": clip_rel,
    }


def write_manifest(
    out_dir: Path, session_id: int, entries: list[dict], now: datetime | None = None
) -> Path:
    manifest = {
        "sample_rate": 16000,
        "clip_ms": 8000,
        "exported_at": (now or datetime.now(UTC)).isoformat(),
        "source_session": session_id,
        "entries": entries,
    }
    path = out_dir / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return path


def remote_size(serial: str | None, rel_path: str) -> int:
    """Byte count of one app-private clip, read on the device.

    The `wc -c < file` redirect runs inside `run-as ... sh -c` so the file is
    opened as the app user — the on-device shell itself cannot read app-private
    paths, and without the redirect wc would print the name alongside the count.
    """
    out = subprocess.run(
        [
            *adb_device_args(serial),
            "shell",
            "run-as",
            APP_PACKAGE,
            "sh",
            "-c",
            f"wc -c < '{CLIPS_DIR}/{rel_path}'",
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return int(out.split()[0])


def pull_clip(serial: str | None, rel_path: str, dest: Path) -> None:
    """Copies one clip WAV from the phone into [dest], verifying the RIFF magic.

    Uses `adb exec-out` (raw binary, no pty CRLF mangling) and cross-checks the
    pulled byte count against the on-device size; a mismatch means the transfer
    was mangled, so the partial file is deleted and an error raised (the caller
    records the clip as failed).
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    expected_size = remote_size(serial, rel_path)
    with dest.open("wb") as f:
        subprocess.run(
            [
                *adb_device_args(serial),
                "exec-out",
                "run-as",
                APP_PACKAGE,
                "cat",
                f"{CLIPS_DIR}/{rel_path}",
            ],
            check=True,
            stdout=f,
        )
    actual_size = dest.stat().st_size
    if actual_size != expected_size:
        dest.unlink()
        raise ValueError(
            f"{rel_path}: pulled {actual_size} bytes but device reports "
            f"{expected_size} — transfer mangled"
        )
    with dest.open("rb") as f:
        magic = f.read(4)
    if not is_riff(magic):
        dest.unlink()
        raise ValueError(
            f"{dest}: pulled clip is not a RIFF file (adb output may be an error)"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--serial", help="adb device serial (default: adb's only/auto device)"
    )
    parser.add_argument("--session", type=int, help="session id (default: latest)")
    parser.add_argument(
        "--out",
        metavar="DIR",
        default="fixtures",
        help="fixture output dir (default: fixtures/)",
    )
    parser.add_argument(
        "--min-labels",
        type=int,
        default=0,
        help="only export clips with a user label when >= 1 (default 0 = all clips)",
    )
    args = parser.parse_args(argv)

    with tempfile.TemporaryDirectory(prefix="export_fixtures_") as tmp:
        work = Path(tmp)
        serial = args.serial if args.serial is not None else detect_serial()
        db_path = pull_db(serial, work)
        conn = sqlite3.connect(db_path)
        session_id = (
            args.session
            if args.session is not None
            else conn.execute(
                "SELECT id FROM sleep_session ORDER BY startedAt DESC LIMIT 1"
            ).fetchone()[0]
        )
        rows = fetch_rows(conn, session_id)
        if args.min_labels > 0:
            rows = [r for r in rows if r["user_label"]]
        if not rows:
            print(
                f"error: session {session_id} has no clip-backed events",
                file=sys.stderr,
            )
            return 1

        out_dir = Path(args.out)
        entries: list[dict] = []
        pulled = 0
        for row in rows:
            rel = clip_name(session_id, row["start_ms"])
            dest = out_dir / rel
            try:
                pull_clip(serial, row["file_path"], dest)
            except (subprocess.CalledProcessError, ValueError) as exc:
                print(f"warning: FAILED {row['file_path']}: {exc}", file=sys.stderr)
                continue
            entries.append(build_entry(row, rel))
            pulled += 1
        write_manifest(out_dir, session_id, entries)

        by_class: dict[str, int] = {}
        labeled = 0
        for e in entries:
            by_class[e["type"]] = by_class.get(e["type"], 0) + 1
            if e["user_label"]:
                labeled += 1
        print(
            f"session {session_id}: exported {pulled} clip(s) to {out_dir} ({labeled} labeled)"
        )
        for cls in sorted(by_class):
            print(f"  {cls}: {by_class[cls]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
