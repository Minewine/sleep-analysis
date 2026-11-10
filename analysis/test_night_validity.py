"""Tests for analysis/night_validity.py — the mirror of the app's NightValidity.kt.

Scenarios (the brief's set): clean night → A; 20% window shortfall → B; uncaptured gaps +
thermal → C; zero windows → D; stale garmin → warning; implausible HR dropped → warning.
Boundary behavior must match the Kotlin tests (NightValidityTest) field for field.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from night_validity import assess, capture_from_env, garmin_warnings_from_env


def healthy_capture(**overrides):
    """8 h night fully processed: 28800 s window → 57600 expected windows."""
    base = {
        "windows_processed": 57600,
        "window_seconds": 28800,
        "thermal_throttle_events": 0,
        "skipped_hops": 0,
    }
    base.update(overrides)
    return base


def clean(**overrides):
    """A healthy night; kwargs override assess() inputs directly."""
    kwargs = {
        "capture": healthy_capture(),
        "clips": {"clip_count": 12, "clipped_count": 0},
        "uncaptured_gap_minutes": 0.0,
        "session_status": "COMPLETED",
        "resumed": False,
        "snore_share": 0.15,
        "ambient_z": 0.4,
    }
    kwargs.update(overrides)
    return assess(**kwargs)


def ids(result):
    return [r["id"] for r in result["reasons"]]


def warning_count(result):
    return sum(1 for r in result["reasons"] if r["severity"] == "WARNING")


def test_clean_night_grades_a():
    result = clean()
    assert result["grade"] == "A"
    assert result["reasons"] == []
    assert result["version"] == "night-validity-v1"


def test_twenty_percent_shortfall():
    # 80% of 57600 processed → 20% short: exactly one warning (the grade matrix keeps a
    # single warning at A); any second warning (here: an interrupted session) → B.
    result = clean(capture=healthy_capture(windows_processed=46_080))
    assert ids(result) == ["capture_gaps"]
    assert result["reasons"][0]["detail"] == "processed 46080 of ~57600 expected windows (20% short)"
    assert result["grade"] == "A"
    two = clean(
        capture=healthy_capture(windows_processed=46_080),
        session_status="RECOVERED",
    )
    assert warning_count(two) == 2
    assert two["grade"] == "B"


def test_uncaptured_gaps_plus_thermal_grade_b():
    # Three warnings: unexplained gap, thermal throttling, interrupted session.
    result = clean(
        uncaptured_gap_minutes=20.0,
        capture=healthy_capture(thermal_throttle_events=2, skipped_hops=7200),
        session_status="RECOVERED",
    )
    assert ids(result) == ["thermal_throttle", "unexplained_gap", "session_interrupted"]
    assert warning_count(result) == 3
    assert result["grade"] == "B"


def test_four_warnings_grade_c():
    result = clean(
        uncaptured_gap_minutes=20.0,
        capture=healthy_capture(thermal_throttle_events=2, skipped_hops=7200),
        session_status="RECOVERED",
        clips={"clip_count": 10, "clipped_count": 2},
    )
    assert warning_count(result) == 4
    assert result["grade"] == "C"


def test_zero_windows_grade_d():
    result = assess(capture=healthy_capture(windows_processed=0, window_seconds=3600))
    assert result["grade"] == "D"
    assert ["capture_failed"] == [r["id"] for r in result["reasons"]]
    assert result["reasons"][0]["severity"] == "CRITICAL"


def test_missing_capture_stats_grade_d():
    result = assess(capture=None, session_status="COMPLETED", snore_share=0.2)
    assert result["grade"] == "D"
    assert ids(result) == ["stats_missing"]


def test_unexplained_gap_escalation():
    assert "unexplained_gap" not in ids(clean(uncaptured_gap_minutes=15.0))
    warn = clean(uncaptured_gap_minutes=15.5)
    assert warn["reasons"][0] == {
        "id": "unexplained_gap",
        "severity": "WARNING",
        "detail": "16 min of the night have no capture evidence",
    }
    assert warn["grade"] == "A"  # a single warning stays A
    critical = clean(uncaptured_gap_minutes=61.0)
    assert critical["reasons"][0]["severity"] == "CRITICAL"
    assert critical["grade"] == "D"


def test_clipped_rate_boundaries():
    assert "clipped_clips" not in ids(clean(clips={"clip_count": 10, "clipped_count": 1}))
    assert "clipped_clips" in ids(clean(clips={"clip_count": 10, "clipped_count": 2}))
    critical = clean(clips={"clip_count": 10, "clipped_count": 4})
    assert critical["grade"] == "D"
    # No clips → never a rate.
    assert "clipped_clips" not in ids(clean(clips={"clip_count": 0, "clipped_count": 0}))


def test_ambient_is_relative_and_optional():
    assert "ambient_noise_high" in ids(clean(ambient_z=2.6))
    assert "ambient_noise_high" not in ids(clean(ambient_z=2.5))  # boundary: strict >
    assert "ambient_noise_high" not in ids(clean(ambient_z=None))  # <5 prior nights


def test_low_snore_signal_is_info_only():
    result = clean(snore_share=0.009)
    assert result["reasons"][0]["severity"] == "INFO"
    assert result["grade"] == "A"
    assert "low_snore_signal" not in ids(clean(snore_share=0.01))
    assert "low_snore_signal" not in ids(clean(snore_share=None))


def test_stale_garmin_warning():
    result = clean(
        garmin_warnings=[{"id": "garmin_stale", "detail": "imported 30.0 h after the night ended (limit 24 h)"}],
    )
    assert ids(result) == ["garmin_stale"]
    assert result["reasons"][0]["severity"] == "WARNING"
    assert result["grade"] == "A"


def test_implausible_hr_dropped_passes_through_as_warning():
    result = clean(
        garmin_warnings=[
            {"id": "garmin_implausible", "detail": "values dropped: VENDOR_AVG_HEART_RATE_SLEEP 260.0 outside 30.0–220.0 bpm"},
        ],
    )
    assert "garmin_implausible" in ids(result)
    assert result["grade"] == "A"
    # Plus a second warning the grade must react to.
    two = clean(
        garmin_warnings=[
            {"id": "garmin_implausible", "detail": "x"},
            {"id": "garmin_spo2_distrust", "detail": "y"},
        ],
    )
    assert two["grade"] == "B"


def test_no_garmin_import_is_no_input():
    assert clean(garmin_warnings=None)["reasons"] == []
    assert clean(garmin_warnings=[])["reasons"] == []


def test_capture_from_env(monkeypatch):
    monkeypatch.delenv("NIGHT_VALIDITY_WINDOWS_PROCESSED", raising=False)
    monkeypatch.delenv("NIGHT_VALIDITY_WINDOW_SECONDS", raising=False)
    assert capture_from_env() is None
    monkeypatch.setenv("NIGHT_VALIDITY_WINDOWS_PROCESSED", "100")
    monkeypatch.setenv("NIGHT_VALIDITY_WINDOW_SECONDS", "50")
    monkeypatch.setenv("NIGHT_VALIDITY_THERMAL_EVENTS", "2")
    assert capture_from_env() == {
        "windows_processed": 100,
        "window_seconds": 50,
        "thermal_throttle_events": 2,
        "skipped_hops": 0,
    }


def test_garmin_warnings_from_env(monkeypatch):
    monkeypatch.delenv("NIGHT_VALIDITY_GARMIN_WARNINGS", raising=False)
    assert garmin_warnings_from_env() == []
    monkeypatch.setenv(
        "NIGHT_VALIDITY_GARMIN_WARNINGS",
        '[{"id": "garmin_stale", "detail": "d"}]',
    )
    assert [w["id"] for w in garmin_warnings_from_env()] == ["garmin_stale"]
    monkeypatch.setenv("NIGHT_VALIDITY_GARMIN_WARNINGS", "not json")
    assert garmin_warnings_from_env() == []
    monkeypatch.setenv("NIGHT_VALIDITY_GARMIN_WARNINGS", '{"id": "garmin_stale"}')
    assert garmin_warnings_from_env() == []  # not a list
