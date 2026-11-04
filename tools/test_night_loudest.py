"""Tests for the ranking/reporting and clip-export parts of night_loudest."""

import sqlite3
import subprocess

import night_loudest as nl


def e(peak: float, hour: str = "23", clip: int | None = None) -> nl.Event:
    return nl.Event(
        start_ms=0,
        time=f"2026-11-02 {hour}:00:00",
        hour=hour,
        type="SNORE",
        peak_dbfs=peak,
        mean_dbfs=peak - 5,
        duration_s=3,
        tier="QUIET",
        clip_id=clip,
        confidence=0.9,
    )


def test_rank_loudest_orders_by_peak_loudest_first():
    events = [e(-40.0), e(-16.6), e(-30.0)]
    ranked = nl.rank_loudest(events, 3)
    assert [x.peak_dbfs for x in ranked] == [-16.6, -30.0, -40.0]


def test_rank_loudest_limits():
    events = [e(float(p)) for p in range(-50, -40)]
    assert len(nl.rank_loudest(events, 5)) == 5
    assert nl.rank_loudest(events, 5)[0].peak_dbfs == -41.0  # loudest kept


def test_hourly_peaks_groups_and_sorts():
    events = [e(-30, "01"), e(-20, "23"), e(-40, "23", clip=7), e(-35, "01")]
    rows = nl.hourly_peaks(events)
    # Loudest peak per hour (dBFS: −20 is louder than −40), hours sorted.
    assert rows == [("01", 2, -30.0, 0), ("23", 2, -20.0, 1)]


def test_hourly_peaks_empty():
    assert nl.hourly_peaks([]) == []


def test_format_table_lists_clip_ids():
    table = nl.format_table([e(-16.6, clip=42), e(-30.0)])
    assert "#42" in table
    assert " -" in table
    assert "peak" in table and "conf" in table


WAV_BYTES = b"RIFF" + b"x" * 124  # 128 bytes total


def fake_adb(device_size: int = len(WAV_BYTES), payload: bytes = WAV_BYTES):
    """Stand-in for the adb calls pull_clip makes (same shape as test_export_fixtures).

    The on-device `wc -c` size probe returns a printed count; the transfer must
    be `exec-out` and writes the payload to the stdout file object.
    """

    def fake_run(cmd, check, stdout=None, capture_output=False, text=False):
        joined = " ".join(cmd)
        if "wc -c" in joined:
            assert "sh" in cmd and "-c" in cmd, (
                "size must be read inside the app sandbox"
            )
            return subprocess.CompletedProcess(
                cmd, 0, stdout=f"{device_size}\n", stderr=""
            )
        assert "exec-out" in joined, "binary transfer must use exec-out, not shell"
        assert stdout is not None
        stdout.write(payload)
        return subprocess.CompletedProcess(cmd, 0, stdout=None, stderr="")

    return fake_run


def clip_db(*file_paths: str) -> sqlite3.Connection:
    """In-memory audio_clip table mapping clip ids 1..n to the given filePaths."""
    conn = sqlite3.connect(":memory:")
    conn.execute("CREATE TABLE audio_clip (id INTEGER PRIMARY KEY, filePath TEXT)")
    for i, path in enumerate(file_paths, 1):
        conn.execute("INSERT INTO audio_clip VALUES (?, ?)", (i, path))
    return conn


def test_export_clips_pulls_via_exec_out_with_size_check(tmp_path, monkeypatch):
    conn = clip_db("session_3/evt_45000.wav")
    calls = []

    def spy_run(cmd, check, stdout=None, capture_output=False, text=False):
        calls.append(cmd)
        return fake_adb()(cmd, check, stdout, capture_output, text)

    monkeypatch.setattr(nl.subprocess, "run", spy_run)
    out_dir = tmp_path / "clips"

    assert nl.export_clips("serial-1", conn, 3, [e(-16.6, clip=1)], out_dir) == 1

    dest = out_dir / "evt_45000.wav"
    assert dest.read_bytes()[:4] == b"RIFF"
    cat_call = next(c for c in calls if "cat" in c)
    assert "exec-out" in cat_call  # no pty -> no CRLF mangling of the WAV bytes
    assert "run-as" in cat_call and "com.sleepanalysis" in cat_call
    assert any("wc -c" in " ".join(c) for c in calls)


def test_export_clips_skips_size_mismatched_clip(tmp_path, monkeypatch, capsys):
    conn = clip_db("session_3/evt_45000.wav")
    out_dir = tmp_path / "clips"

    # Device says 999 bytes but the transfer delivers something else: mangled —
    # the clip is skipped with a warning and no junk file is left behind.
    monkeypatch.setattr(nl.subprocess, "run", fake_adb(device_size=999))
    assert nl.export_clips("serial-1", conn, 3, [e(-16.6, clip=1)], out_dir) == 0
    assert not (out_dir / "evt_45000.wav").exists()
    assert "999" in capsys.readouterr().err


def test_export_clips_skips_non_riff_clip(tmp_path, monkeypatch, capsys):
    conn = clip_db("session_3/evt_45000.wav")
    out_dir = tmp_path / "clips"

    # Byte count matches but the payload is not a WAV (e.g. an on-device error
    # page of exactly the expected size) — caught by the RIFF magic check.
    junk = b"not a wav" + b"x" * 120
    monkeypatch.setattr(
        nl.subprocess, "run", fake_adb(device_size=len(junk), payload=junk)
    )
    assert nl.export_clips("serial-1", conn, 3, [e(-16.6, clip=1)], out_dir) == 0
    assert not (out_dir / "evt_45000.wav").exists()
    assert "not a RIFF" in capsys.readouterr().err
