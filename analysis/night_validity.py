"""Night validity mirror (analysis side) of the app's domain/validity/NightValidity.kt.

Same grading rules, reason ids and thresholds as the Kotlin object (its KDoc marks which
thresholds are app conventions vs cited — do not change one side without the other).
stdlib-only, so the tools/ test suite can import it without matplotlib.

The pure core is assess(): it takes the night's capture evidence as plain dicts and returns
{"version", "grade", "reasons"} — reasons are ordered {id, severity, detail} exactly like the
NIGHT_VALIDITY row's notes JSON. Config arrives via environment variables (like night_report.py):
capture_from_env() reads the NIGHT_VALIDITY_* capture summary; night_report.py feeds the rest.

RELATIVE ONLY: phone dBFS is uncalibrated (M2) — the ambient check is a z-score against the
rolling prior-night distribution, never an absolute-dB comparison.
"""

import json
import os

VERSION = "night-validity-v1"

# Thresholds mirrored from NightValidity.kt (app conventions vs cited — see its KDoc).
CAPTURE_FAILED_MIN_WINDOW_S = 30
SHORTFALL_WARN = 0.10
SHORTFALL_CRITICAL = 0.40
UNEXPLAINED_GAP_WARN_MIN = 15.0
UNEXPLAINED_GAP_CRITICAL_MIN = 60.0
CLIPPED_WARN = 0.10
CLIPPED_CRITICAL = 0.30
AMBIENT_Z_TRIGGER = 2.5
LOW_SNORE_SHARE = 0.01
HOP_MS = 500  # analysis hop grid; expected windows = window_seconds * 2

_SEVERITIES = ("INFO", "WARNING", "CRITICAL")
_GRADES = ("A", "B", "C", "D")


def capture_from_env(prefix: str = "NIGHT_VALIDITY") -> dict | None:
    """Optional capture summary from env; None (→ stats_missing) when unset.

    Keys: <prefix>_WINDOWS_PROCESSED, <prefix>_WINDOW_SECONDS, <prefix>_THERMAL_EVENTS,
    <prefix>_SKIPPED_HOPS. Partial input is honored — missing numbers become None.
    """
    if (
        os.environ.get(f"{prefix}_WINDOWS_PROCESSED") is None
        and os.environ.get(f"{prefix}_WINDOW_SECONDS") is None
    ):
        return None

    def _int(name: str) -> int | None:
        raw = os.environ.get(f"{prefix}_{name}")
        try:
            return int(raw) if raw is not None else None
        except ValueError:
            return None

    return {
        "windows_processed": _int("WINDOWS_PROCESSED"),
        "window_seconds": _int("WINDOW_SECONDS"),
        "thermal_throttle_events": _int("THERMAL_EVENTS") or 0,
        "skipped_hops": _int("SKIPPED_HOPS") or 0,
    }


def assess(
    *,
    capture: dict | None = None,
    clips: dict | None = None,
    uncaptured_gap_minutes: float | None = None,
    session_status: str | None = None,
    resumed: bool = False,
    snore_share: float | None = None,
    ambient_z: float | None = None,
    garmin_warnings: list[dict] | None = None,
) -> dict:
    """Grades the night. Same order, ids, severities and grade matrix as NightValidity.compute.

    capture: {"windows_processed", "window_seconds", "thermal_throttle_events", "skipped_hops"}
      — None means no capture stats at all (stats_missing, grade D).
    clips: {"clip_count", "clipped_count"} — None when no clip scan ran.
    garmin_warnings: [{"id": "garmin_*", "detail": str}] — passed through verbatim (the
      GarminValidators mirror lives in the app; the analysis side consumes its output).
    """
    reasons: list[dict] = []

    def add(rid: str, severity: str, detail: str) -> None:
        reasons.append({"id": rid, "severity": severity, "detail": detail})

    if capture is None:
        add("stats_missing", "CRITICAL", "no capture statistics for this night")
    else:
        processed = capture.get("windows_processed")
        window_s = capture.get("window_seconds") or 0
        if processed is None:
            pass  # stats unknown — never invent a capture failure
        elif processed == 0 and window_s > CAPTURE_FAILED_MIN_WINDOW_S:
            add(
                "capture_failed",
                "CRITICAL",
                f"no analysis windows processed in a {window_s}s capture window",
            )
        else:
            expected = window_s * 2  # 0.5 s hop
            if expected > 0 and processed is not None and processed < expected:
                shortfall = 1.0 - processed / expected
                detail = f"processed {processed} of ~{expected} expected windows ({shortfall * 100:.0f}% short)"
                if shortfall > SHORTFALL_CRITICAL:
                    add("capture_gaps_severe", "CRITICAL", detail)
                elif shortfall > SHORTFALL_WARN:
                    add("capture_gaps", "WARNING", detail)
        if (capture.get("thermal_throttle_events") or 0) > 0 and (
            capture.get("skipped_hops") or 0
        ) > 0:
            add(
                "thermal_throttle",
                "WARNING",
                f"thermal throttling engaged {capture['thermal_throttle_events']} time(s), "
                f"{capture['skipped_hops']} analysis hops skipped",
            )
    if uncaptured_gap_minutes is not None:
        if uncaptured_gap_minutes > UNEXPLAINED_GAP_CRITICAL_MIN:
            add(
                "unexplained_gap",
                "CRITICAL",
                f"{uncaptured_gap_minutes:.0f} min of the night have no capture evidence",
            )
        elif uncaptured_gap_minutes > UNEXPLAINED_GAP_WARN_MIN:
            add(
                "unexplained_gap",
                "WARNING",
                f"{uncaptured_gap_minutes:.0f} min of the night have no capture evidence",
            )
    if clips is not None and clips.get("clip_count", 0) > 0:
        rate = clips["clipped_count"] / clips["clip_count"]
        detail = (
            f"{clips['clipped_count']} of {clips['clip_count']} clips digitally clipped"
        )
        if rate > CLIPPED_CRITICAL:
            add("clipped_clips", "CRITICAL", detail)
        elif rate > CLIPPED_WARN:
            add("clipped_clips", "WARNING", detail)
    if ambient_z is not None and ambient_z > AMBIENT_Z_TRIGGER:
        add(
            "ambient_noise_high",
            "WARNING",
            f"ambient P95 z={ambient_z:+.1f} vs prior nights (relative only, uncalibrated dBFS)",
        )
    if session_status is not None and (session_status != "COMPLETED" or resumed):
        detail = (
            "resumed after an interruption"
            if resumed
            else f"status {session_status.lower()}"
        )
        add("session_interrupted", "WARNING", f"session {detail}")
    for warning in garmin_warnings or []:
        add(warning["id"], "WARNING", warning.get("detail", ""))
    if snore_share is not None and snore_share < LOW_SNORE_SHARE:
        add(
            "low_snore_signal",
            "INFO",
            f"snoring covered {snore_share * 100:.1f}% of captured audio (below the 1% reliability floor)",
        )

    criticals = any(r["severity"] == "CRITICAL" for r in reasons)
    warning_count = sum(1 for r in reasons if r["severity"] == "WARNING")
    if criticals:
        grade = "D"
    elif warning_count <= 1:
        grade = "A"
    elif warning_count <= 3:
        grade = "B"
    else:
        grade = "C"
    return {"version": VERSION, "grade": grade, "reasons": reasons}


def garmin_warnings_from_env(
    name: str = "NIGHT_VALIDITY_GARMIN_WARNINGS",
) -> list[dict]:
    """Optional Garmin validator warnings as a JSON array of {id, detail} (bad JSON → none)."""
    raw = os.environ.get(name)
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except ValueError:
        return []
    if not isinstance(parsed, list):
        return []
    return [w for w in parsed if isinstance(w, dict) and isinstance(w.get("id"), str)]
