"""Tests for export_night_csv: CSV contents, the Garmin wake-date join, the
phone-vs-vendor overlap math, and the outside-the-repo guard on --out."""

import csv
import json
import sqlite3
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import export_night_csv as enc
import pytest

ZURICH = ZoneInfo("Europe/Zurich")

WAKE = date(2026, 11, 4)  # this session's Garmin wake date (night of Nov 3 -> 4)


def ms(*args: int) -> int:
    """Epoch ms of a Zurich wall-clock time (y, m, d, h, min[, s])."""
    return round(datetime(*args, tzinfo=ZURICH).timestamp() * 1000)


START = ms(2026, 11, 3, 22, 15)
CAPTURE_START = ms(2026, 11, 3, 22, 16, 30)
END = ms(2026, 11, 4, 7, 30)

CAPTURE_STATS = json.dumps(
    {
        "schema_version": 3,
        "windows_processed": 59_000,
        "ambient_dbfs_median": -38.5,
        "capture_started_at": CAPTURE_START,
        "thermal_throttle_events": 0,
        "ambient_level_buckets": [[CAPTURE_START, CAPTURE_START + 300_000, -41.2]],
    }
)

GARMIN_WINDOW = (ms(2026, 11, 3, 23, 47), ms(2026, 11, 4, 7, 2))


def garmin_payload(start_ms: int, end_ms: int) -> str:
    """dailySleep raw payload with the keys the app's parser looks for."""
    return json.dumps(
        {
            "sleepStartTimestampGMT": start_ms,
            "sleepEndTimestampGMT": end_ms,
            "sleepTimeSeconds": 26400.0,
            "deepSleepSeconds": 5400.0,
            "lightSleepSeconds": 13200.0,
            "remSleepSeconds": 6000.0,
            "awakeSleepSeconds": 1800.0,
            "awakeCount": 3.0,
        }
    )


# The payload stored under the evening date (2026-11-03) is the wrong night's entry —
# it must never win the wake-date join, so its window values differ on purpose.
PAYLOAD_EVENING = garmin_payload(ms(2026, 11, 3, 22, 0), ms(2026, 11, 3, 23, 0))
PAYLOAD_WAKE = garmin_payload(*GARMIN_WINDOW)


def build_db(path: Path, *, with_vendor: bool = True) -> sqlite3.Connection:
    """Synthetic stand-in for sleepanalysis.db, mirroring the app's Room schema."""
    if path.is_dir():  # callers hand in tmp_path; keep the real DB filename
        path = path / "sleepanalysis.db"
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE sleep_session (
            id INTEGER PRIMARY KEY, startedAt INTEGER, endedAt INTEGER, status TEXT,
            timeZone TEXT, deviceModel TEXT, osVersion TEXT, appVersion TEXT,
            captureStats TEXT, audioBytesTotal INTEGER, morningNotes TEXT,
            resumedAfterInterruption INTEGER DEFAULT 0, createdAt INTEGER);
        CREATE TABLE acoustic_event (
            id INTEGER PRIMARY KEY, sessionId INTEGER, type TEXT, startMs INTEGER,
            endMs INTEGER, offsetMs INTEGER, durationMs INTEGER, peakDbfs REAL,
            meanDbfs REAL, intensityTier TEXT, confidence REAL, modelVersion TEXT,
            clipId INTEGER, userLabel TEXT, labeledAt INTEGER, featuresJson TEXT,
            createdAt INTEGER);
        CREATE TABLE measurement (
            id INTEGER PRIMARY KEY, sessionId INTEGER, timestamp INTEGER,
            sourceType TEXT, method TEXT, isClinical INTEGER, measurementType TEXT,
            tier TEXT, valueNum REAL, valueText TEXT, unit TEXT, confidence REAL,
            modelVersion TEXT, periodStart INTEGER, periodEnd INTEGER, notes TEXT,
            createdAt INTEGER);
        CREATE TABLE quiet_gap (
            id INTEGER PRIMARY KEY, sessionId INTEGER, startMs INTEGER, endMs INTEGER,
            durationMs INTEGER, kind TEXT, meanDbfs REAL, createdAt INTEGER);
        CREATE TABLE audio_clip (
            id INTEGER PRIMARY KEY, eventId INTEGER, filePath TEXT, createdAt INTEGER);
        CREATE TABLE raw_payload (
            id INTEGER PRIMARY KEY, source TEXT, endpoint TEXT, date TEXT,
            payload TEXT, fetchedAt INTEGER);
    """)
    conn.execute(
        "INSERT INTO sleep_session (id, startedAt, endedAt, status, timeZone,"
        " deviceModel, osVersion, appVersion, captureStats, audioBytesTotal,"
        " morningNotes, resumedAfterInterruption, createdAt)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            1,
            START,
            END,
            "COMPLETED",
            "Europe/Zurich",
            "Pixel 8",
            "15",
            "1.0",
            CAPTURE_STATS,
            123_456,
            "slept fine",
            0,
            START,
        ),
    )
    conn.executemany(
        "INSERT INTO acoustic_event (id, sessionId, type, startMs, endMs, offsetMs,"
        " durationMs, peakDbfs, meanDbfs, intensityTier, confidence, modelVersion,"
        " clipId, userLabel) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                1,
                1,
                "SNORE",
                ms(2026, 11, 3, 23, 10),
                ms(2026, 11, 3, 23, 14),
                3_300_000,
                240_000,
                -16.6,
                -21.3,
                "LOUD",
                0.92,
                "energy-heuristic-v0",
                7,
                "SNORE",
            ),
            (
                2,
                1,
                "SNORE",
                ms(2026, 11, 4, 2, 3),
                ms(2026, 11, 4, 2, 5),
                13_800_000,
                120_000,
                -22.0,
                -26.5,
                "AUDIBLE",
                0.75,
                "energy-heuristic-v0",
                None,
                None,
            ),
            (
                3,
                1,
                "OTHER_NOISE",
                ms(2026, 11, 4, 5, 40),
                ms(2026, 11, 4, 5, 40, 12),
                26_500_000,
                12_000,
                -30.1,
                -34.8,
                "AUDIBLE",
                0.55,
                "energy-heuristic-v0",
                None,
                "MOVEMENT",
            ),
        ],
    )
    conn.executemany(
        "INSERT INTO measurement (id, sessionId, timestamp, sourceType, method,"
        " isClinical, measurementType, tier, valueNum, valueText, unit, confidence,"
        " modelVersion, periodStart, periodEnd, notes)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                1,
                1,
                END,
                "GARMIN",
                "vendor",
                0,
                "VENDOR_SLEEP_SCORE",
                "GOOD",
                78.0,
                None,
                "score",
                None,
                None,
                START,
                END,
                None,
            ),
            (
                2,
                1,
                START,
                "ACOUSTIC",
                "energy-heuristic-v0",
                0,
                "SNORE_SCORE",
                "BAD",
                12.0,
                None,
                "score",
                0.5,
                "score-formula-v1",
                START,
                END,
                "rough night",
            ),
        ],
    )
    conn.executemany(
        "INSERT INTO quiet_gap (id, sessionId, startMs, endMs, durationMs, kind,"
        " meanDbfs) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            (
                1,
                1,
                ms(2026, 11, 4, 1, 0),
                ms(2026, 11, 4, 1, 45),
                2_700_000,
                "QUIET",
                -42.5,
            ),
            (
                2,
                1,
                ms(2026, 11, 4, 6, 0),
                ms(2026, 11, 4, 6, 20),
                1_200_000,
                None,
                None,
            ),
        ],
    )
    conn.executemany(
        "INSERT INTO audio_clip (id, eventId, filePath) VALUES (?, ?, ?)",
        [(7, 1, f"session_1/evt_snore_{ms(2026, 11, 3, 23, 10)}_1.wav")],
    )
    if with_vendor:
        conn.executemany(
            "INSERT INTO raw_payload (source, endpoint, date, payload)"
            " VALUES (?, ?, ?, ?)",
            [
                (
                    "GARMIN",
                    "/sleep-service/sleep/dailySleep",
                    "2026-11-03",
                    PAYLOAD_EVENING,
                ),
                (
                    "GARMIN",
                    "/sleep-service/sleep/dailySleep",
                    "2026-11-04",
                    PAYLOAD_WAKE,
                ),
                (
                    "GARMIN",
                    "/sleep-service/sleep/2026-11-04",
                    "2026-11-04",
                    PAYLOAD_EVENING,
                ),
            ],
        )
    return conn


def read_csv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        return list(reader.fieldnames or []), list(reader)


def test_export_csv_contents(tmp_path):
    out = tmp_path / "out"
    exp = enc.export_csvs(build_db(tmp_path), 1, out)

    assert [p.name for p in exp.files] == [
        "events_1.csv",
        "session_1.csv",
        "measurements_1.csv",
        "quiet_gaps_1.csv",
        "garmin_sleep_window_1.csv",
    ]

    header, rows = read_csv(out / "events_1.csv")
    assert header == enc.EVENTS_COLUMNS
    assert len(rows) == 3
    assert rows[0]["type"] == "SNORE"
    assert rows[0]["start_ms"] == str(ms(2026, 11, 3, 23, 10))
    assert rows[0]["peak_dbfs"] == "-16.6"
    assert rows[0]["tier"] == "LOUD"  # intensityTier exported as tier
    assert rows[0]["user_label"] == "SNORE"
    assert rows[0]["clip_id"] == "7"
    assert rows[2]["type"] == "OTHER_NOISE"
    assert rows[2]["user_label"] == "MOVEMENT"

    header, rows = read_csv(out / "session_1.csv")
    assert rows[0]["timeZone"] == "Europe/Zurich"
    assert rows[0]["status"] == "COMPLETED"
    assert rows[0]["morningNotes"] == "slept fine"
    # captureStats flattened to key columns: scalars as strings, nested as JSON.
    assert rows[0]["capture_started_at"] == str(CAPTURE_START)
    assert rows[0]["windows_processed"] == "59000"
    assert rows[0]["ambient_level_buckets"] == json.dumps(
        [[CAPTURE_START, CAPTURE_START + 300_000, -41.2]], separators=(",", ":")
    )

    header, rows = read_csv(out / "measurements_1.csv")
    assert len(rows) == 2
    # Exporter order is (timestamp, id): the phone row (START) precedes the vendor row (END).
    assert rows[0]["measurementType"] == "SNORE_SCORE"
    assert rows[0]["notes"] == "rough night"
    assert rows[1]["measurementType"] == "VENDOR_SLEEP_SCORE"
    assert rows[1]["valueNum"] == "78.0"

    header, rows = read_csv(out / "quiet_gaps_1.csv")
    assert rows[0]["kind"] == "QUIET"
    assert rows[0]["meanDbfs"] == "-42.5"
    assert rows[1]["kind"] == ""  # null -> empty cell
    assert rows[1]["meanDbfs"] == ""


def test_export_csvs_unknown_session(tmp_path):
    with pytest.raises(ValueError, match="not found"):
        enc.export_csvs(build_db(tmp_path), 99, tmp_path / "out")


def test_garmin_payload_selected_by_wake_date(tmp_path):
    out = tmp_path / "out"
    exp = enc.export_csvs(build_db(tmp_path), 1, out)

    # The wake date is the morning the night ends (2026-11-04), not the evening the
    # session began: the 2026-11-03 dailySleep row and the same-date detail-endpoint
    # row must both lose to the wake-date dailySleep payload.
    assert exp.wake_date == "2026-11-04"
    _, rows = read_csv(out / "garmin_sleep_window_1.csv")
    assert len(rows) == 1
    assert rows[0]["sleepStartTimestampGMT"] == str(GARMIN_WINDOW[0])
    assert rows[0]["sleepEndTimestampGMT"] == str(GARMIN_WINDOW[1])
    assert rows[0]["sleepTimeSeconds"] == "26400"
    assert rows[0]["deepSleepSeconds"] == "5400"
    assert rows[0]["lightSleepSeconds"] == "13200"
    assert rows[0]["remSleepSeconds"] == "6000"
    assert rows[0]["awakeSleepSeconds"] == "1800"
    assert rows[0]["awakeCount"] == "3"


def test_garmin_wake_date_is_the_morning_the_night_ends():
    assert enc.garmin_wake_date(START, END, "Europe/Zurich") == WAKE


def test_garmin_wake_date_open_session_falls_forward_12h():
    # 23:50 tap start, no endedAt yet: +12 h lands on the wake morning, still Nov 4.
    assert enc.garmin_wake_date(ms(2026, 11, 3, 23, 50), None, "Europe/Zurich") == WAKE


@pytest.mark.parametrize(
    ("capture", "garmin", "expected"),
    [
        ((100, 200), (100, 200), 100.0),  # identical windows
        ((150, 250), (100, 200), 50.0),  # capture starts mid-window
        ((100, 200), (150, 250), 50.0),  # capture ends mid-window
        ((120, 180), (100, 200), 60.0),  # capture inside the vendor window
        ((0, 100), (100, 200), 0.0),  # touching, no intersection
        ((0, 100), (200, 300), 0.0),  # disjoint
        ((0, 100), (50, 50), 0.0),  # degenerate vendor window
    ],
)
def test_overlap_percent_math(capture, garmin, expected):
    assert enc.overlap_percent(*capture, *garmin) == pytest.approx(expected)


def test_export_without_vendor_payload_omits_garmin_csv(tmp_path):
    out = tmp_path / "out"
    exp = enc.export_csvs(build_db(tmp_path, with_vendor=False), 1, out)

    assert exp.garmin is None
    assert exp.garmin_note == (
        "no GARMIN /sleep-service/sleep/dailySleep payload for wake date 2026-11-04"
    )
    assert [p.name for p in exp.files] == [
        "events_1.csv",
        "session_1.csv",
        "measurements_1.csv",
        "quiet_gaps_1.csv",
    ]
    assert not (out / "garmin_sleep_window_1.csv").exists()


def test_parse_garmin_sleep_row_tolerates_shapes_and_rejects_degenerate():
    # The app falls back to the *Local mirrors when the GMT keys are absent.
    row = enc.parse_garmin_sleep_row(
        json.dumps({"sleepStartTimestampLocal": 1000, "sleepEndTimestampLocal": 2000})
    )
    assert row["sleepStartTimestampGMT"] == 1000
    assert row["sleepEndTimestampGMT"] == 2000

    # Keys may sit nested (e.g. under dailySleepDTO) — bounded search, app parity.
    row = enc.parse_garmin_sleep_row(
        json.dumps(
            {
                "dailySleepDTO": {
                    "sleepStartTimestampGMT": 1000,
                    "sleepEndTimestampGMT": 2000,
                }
            }
        )
    )
    assert row is not None and row["sleepEndTimestampGMT"] == 2000

    assert (
        enc.parse_garmin_sleep_row(
            '{"sleepStartTimestampGMT": 5000, "sleepEndTimestampGMT": 1000}'
        )
        is None
    )  # end <= start: degenerate
    assert enc.parse_garmin_sleep_row("not json{") is None
    assert enc.parse_garmin_sleep_row("{}") is None


def test_flatten_capture_stats_variants():
    assert enc.flatten_capture_stats(None) == {}
    assert enc.flatten_capture_stats("  ") == {}
    assert enc.flatten_capture_stats("not json{") == {"capture_stats_raw": "not json{"}
    flat = enc.flatten_capture_stats(
        json.dumps({"a": 1, "b": True, "c": None, "d": [1, 2]})
    )
    assert flat == {"a": "1", "b": "true", "c": "", "d": "[1,2]"}


def test_capture_start_prefers_capture_stats():
    session = {
        "captureStats": json.dumps({"capture_started_at": 555}),
        "startedAt": 111,
    }
    assert enc.capture_start_ms(session) == 555
    assert enc.capture_start_ms({"captureStats": None, "startedAt": 111}) == 111
    assert enc.capture_start_ms({"captureStats": "{}", "startedAt": 111}) == 111


def test_resolve_out_dir_allows_paths_outside_the_repo(tmp_path):
    resolved = enc.resolve_out_dir(str(tmp_path / "night5"))
    assert resolved == (tmp_path / "night5").resolve()


def test_resolve_out_dir_refuses_repo_paths(tmp_path):
    root = enc.repo_root()
    with pytest.raises(ValueError, match="inside this repository"):
        enc.resolve_out_dir(str(root / "night-data"))
    with pytest.raises(ValueError, match="inside this repository"):
        enc.resolve_out_dir(str(root))  # the repo root itself
    # Not-yet-existing paths inside the repo are refused too (resolved, not stat-ed).
    with pytest.raises(ValueError, match="inside this repository"):
        enc.resolve_out_dir(str(root / "night-data" / "session-5"))
    # A symlink pointing INTO the repo resolves to its target — refused as well.
    link = tmp_path / "sneaky"
    link.symlink_to(root / "night-data", target_is_directory=True)
    with pytest.raises(ValueError, match="inside this repository"):
        enc.resolve_out_dir(str(link))
    # But a sibling of the repo is fine.
    assert (
        enc.resolve_out_dir(str(root.parent / "elsewhere"))
        == (root.parent / "elsewhere").resolve()
    )


def test_main_refuses_repo_out_before_touching_adb(monkeypatch, capsys):
    def no_adb(cmd, **kwargs):
        raise AssertionError("adb must not run before --out is validated")

    monkeypatch.setattr(enc.subprocess, "run", no_adb)
    out = enc.repo_root() / "night-data"
    assert enc.main(["--out", str(out)]) == 1
    assert "inside this repository" in capsys.readouterr().err
    assert not out.exists()


def test_export_clips_writes_manifest(tmp_path, monkeypatch):
    """The clips manifest pins the verified event↔clip join (id, type, timing, peak,
    export outcome) so analysis never re-derives it from clip filenames."""
    conn = build_db(tmp_path)
    monkeypatch.setattr(
        enc, "pull_clip", lambda serial, rel, dest: dest.write_bytes(b"RIFFxxxx")
    )
    clips_dir = tmp_path / "clips"
    n = enc.export_clips("serial-1", conn, 1, clips_dir)
    assert n == 1
    manifest = json.loads((clips_dir / "manifest_1.json").read_text(encoding="utf-8"))
    assert manifest["source_session"] == 1
    (entry,) = manifest["clips"]
    assert entry["event_id"] == 1
    assert entry["event_type"] == "SNORE"
    assert entry["path"] == f"evt_snore_{ms(2026, 11, 3, 23, 10)}_1.wav"
    assert entry["start_ms"] == ms(2026, 11, 3, 23, 10)
    assert entry["peak_dbfs"] == -16.6
    assert entry["confidence"] == 0.92
    assert entry["exported"] is True
    assert entry["error"] is None


def test_export_clips_manifest_marks_failed_pulls(tmp_path, monkeypatch):
    """A clip that fails verification stays in the manifest as not-exported — the
    consumer must be able to tell 'no audio' from 'audio not pulled'."""
    conn = build_db(tmp_path)

    def failing_pull(serial, rel, dest):
        raise ValueError("transfer mangled")

    monkeypatch.setattr(enc, "pull_clip", failing_pull)
    clips_dir = tmp_path / "clips"
    n = enc.export_clips("serial-1", conn, 1, clips_dir)
    assert n == 0
    assert not any(clips_dir.glob("*.wav"))
    entry = json.loads((clips_dir / "manifest_1.json").read_text(encoding="utf-8"))[
        "clips"
    ][0]
    assert entry["exported"] is False
    assert "mangled" in entry["error"]


def test_export_clips_zero_clips_writes_empty_manifest(tmp_path, monkeypatch):
    """A quiet session with no clips at all still gets a manifest (empty list) — the
    analysis's cross-check then falls back cleanly instead of treating a missing
    manifest differently from a genuinely empty clip set."""
    conn = build_db(tmp_path)
    conn.execute("DELETE FROM audio_clip")
    clips_dir = tmp_path / "clips"
    monkeypatch.setattr(
        enc, "pull_clip", lambda serial, rel, dest: dest.write_bytes(b"RIFFxxxx")
    )
    n = enc.export_clips("serial-1", conn, 1, clips_dir)
    assert n == 0
    manifest = json.loads((clips_dir / "manifest_1.json").read_text(encoding="utf-8"))
    assert manifest["source_session"] == 1
    assert manifest["clips"] == []
