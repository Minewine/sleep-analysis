"""Tests for the pure manifest/clip parts of export_fixtures."""

import json
import subprocess
from datetime import UTC, datetime

import export_fixtures as ef
import pytest

WAV_BYTES = b"RIFF" + b"x" * 124  # 128 bytes total


def fake_adb(device_size: int = len(WAV_BYTES), payload: bytes = WAV_BYTES):
    """Stand-in for subprocess.run covering the two adb calls pull_clip makes.

    Dispatches on the command shape: the `run-as ... sh -c 'wc -c < ...'`
    on-device size probe returns a printed count, the `exec-out run-as ... cat`
    transfer writes the payload to the stdout file object.
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


def row(**overrides):
    base = {
        "event_id": 7,
        "session_id": 3,
        "type": "SNORE",
        "start_ms": 45_000,
        "offset_ms": 4_000,
        "duration_ms": 3_500,
        "peak_dbfs": -16.6,
        "mean_dbfs": -20.1,
        "intensity_tier": "LOUD",
        "confidence": 0.87,
        "user_label": "SNORE",
        "labeled_at": 1_700_000_000_000,
        "features_json": json.dumps({"snr_peak_db": 60.0}),
        "file_path": "session_3/evt_45000.wav",
    }
    base.update(overrides)
    return base


def test_clip_name_matches_on_device_scheme():
    assert ef.clip_name(3, 45_000) == "session_3/evt_45000.wav"


def test_is_riff_rejects_non_wav_bytes():
    assert ef.is_riff(b"RIFF....")
    assert not ef.is_riff(b"/sys/bin/sh: cat: session_3/evt_45000.wav: No such file")
    assert not ef.is_riff(b"")
    assert not ef.is_riff(b"RIF")


def test_build_entry_embeds_features_parsed():
    entry = ef.build_entry(row(), "session_3/evt_45000.wav")
    assert entry["clip"] == "session_3/evt_45000.wav"
    assert entry["user_label"] == "SNORE"
    assert entry["features"] == {"snr_peak_db": 60.0}
    # Raw JSON string must never leak into the manifest as a string value.
    assert "features_json" not in entry


def test_build_entry_handles_null_and_broken_features():
    assert ef.build_entry(row(features_json=None), "c.wav")["features"] is None
    assert ef.build_entry(row(features_json="not json{"), "c.wav")["features"] is None


def test_write_manifest_shape(tmp_path):
    entries = [ef.build_entry(row(), "session_3/evt_45000.wav")]
    path = ef.write_manifest(
        tmp_path, 3, entries, now=datetime(2026, 11, 3, 12, 0, 0, tzinfo=UTC)
    )
    manifest = json.loads(path.read_text(encoding="utf-8"))
    assert manifest["sample_rate"] == 16000
    assert manifest["clip_ms"] == 8000
    assert manifest["source_session"] == 3
    assert manifest["exported_at"] == "2026-11-03T12:00:00+00:00"
    assert manifest["entries"] == entries


def test_write_manifest_entries_without_features(tmp_path):
    entries = [ef.build_entry(row(user_label=None, features_json=None), "c.wav")]
    manifest = json.loads(
        ef.write_manifest(tmp_path, 3, entries).read_text(encoding="utf-8")
    )
    assert manifest["entries"][0]["user_label"] is None
    assert manifest["entries"][0]["features"] is None


def test_pull_clip_uses_exec_out_and_verifies_device_size(tmp_path, monkeypatch):
    dest = tmp_path / "session_3" / "evt_45000.wav"

    calls = []

    def spy_run(cmd, check, stdout=None, capture_output=False, text=False):
        calls.append(cmd)
        return fake_adb()(cmd, check, stdout, capture_output, text)

    monkeypatch.setattr(ef.subprocess, "run", spy_run)
    ef.pull_clip("serial-1", "session_3/evt_45000.wav", dest)
    assert dest.stat().st_size == len(WAV_BYTES)
    assert dest.read_bytes()[:4] == b"RIFF"

    cat_call = next(c for c in calls if "cat" in c)
    assert "exec-out" in cat_call  # no pty -> no CRLF mangling of the WAV bytes
    assert "run-as" in cat_call and "com.sleepanalysis" in cat_call
    size_call = next(c for c in calls if "wc -c" in " ".join(c))
    assert "run-as" in size_call and "com.sleepanalysis" in size_call


def test_pull_clip_deletes_and_fails_on_size_mismatch(tmp_path, monkeypatch):
    dest = tmp_path / "session_3" / "evt_45000.wav"

    # Device says 999 bytes but the transfer delivers something else: mangled
    # (or an error page that happens to be RIFF-sized) — must fail loudly.
    monkeypatch.setattr(ef.subprocess, "run", fake_adb(device_size=999))
    with pytest.raises(ValueError, match="999"):
        ef.pull_clip("serial-1", "session_3/evt_45000.wav", dest)
    assert not dest.exists()  # bad pull must not leave a junk fixture behind


def test_pull_clip_validates_riff_and_cleans_up(tmp_path, monkeypatch):
    dest = tmp_path / "session_3" / "evt_45000.wav"

    # Byte count matches but the payload is not a WAV (e.g. an on-device error
    # page of exactly the expected size) — caught by the RIFF magic check.
    junk = b"not a wav" + b"x" * 120
    monkeypatch.setattr(
        ef.subprocess, "run", fake_adb(device_size=len(junk), payload=junk)
    )
    with pytest.raises(ValueError, match="not a RIFF"):
        ef.pull_clip("serial-1", "session_3/evt_45000.wav", dest)
    assert not dest.exists()

    monkeypatch.setattr(ef.subprocess, "run", fake_adb())
    ef.pull_clip("serial-1", "session_3/evt_45000.wav", dest)
    assert dest.read_bytes()[:4] == b"RIFF"
