"""Tests for the battery-drain summary in night_battery."""

import io
from datetime import UTC, datetime, timedelta

import night_battery as nb

# Real line shape from the device (SessionLogger + BatteryStatus.format);
# {batt} carries its own `%` or the bare `?` null placeholder.
LINE = (
    "{ts} [{event}] windows=43786 skipped=0 clips=0 freeMB=188665"
    " batt={batt} charging={charging}"
)
FIRST_TS = datetime(2026, 11, 12, 23, 11, 46, 95766, tzinfo=UTC)


def log(*readings: tuple[str, str], step_minutes: int = 15) -> str:
    """A minimal log from (batt, charging) pairs, heartbeats spaced [step]
    minutes apart (matching the app's 15-minute heartbeat), last line a stop.
    """
    lines = []
    for i, (batt, charging) in enumerate(readings):
        ts = (
            (FIRST_TS + timedelta(minutes=step_minutes * i))
            .isoformat()
            .replace("+00:00", "Z")
        )
        event = "recording_stop" if i == len(readings) - 1 else "heartbeat"
        # The app's real null rendering is bare `batt=?` (no percent sign).
        field = "?" if batt == "?" else f"{batt}%"
        lines.append(LINE.format(ts=ts, event=event, batt=field, charging=charging))
    return "\n".join(lines) + "\n"


def test_plugged_in_all_night_is_not_zero_drain():
    # Every logged night so far: charging=true on every beat.
    text = log(("100", "true"), ("100", "true"), ("99", "true"), ("100", "true"))
    report = nb.format_report(nb.summarize(nb.parse_log(text)))
    assert "plugged in all night" in report
    assert "gate" not in report  # no invented drain number


def test_unplugged_night_drain_and_gate():
    # ~8 h10m unplugged, 7% drop -> 6.9%/8h, inside the <= 10% gate.
    text = log(("100", "false"), ("93", "false"), step_minutes=490)
    summary = nb.summarize(nb.parse_log(text))
    assert summary.unplugged_all_night
    assert len(summary.stretches) == 1
    assert summary.stretches[0].drain == 7
    report = nb.format_report(summary)
    assert "100% -> 93% (-7%)" in report
    assert "6.9%/8h" in report
    assert "gate (longest stretch" in report and "OK" in report
    assert "partially plugged" not in report


def test_gate_fires_over_10_pct_per_8h():
    # 20% drop in 2 h -> 80%/8h, way over the gate.
    text = log(("100", "false"), ("80", "false"), step_minutes=120)
    report = nb.format_report(nb.summarize(nb.parse_log(text)))
    assert "80.0%/8h" in report and "OVER" in report


def test_mixed_night_is_per_stretch_not_summed():
    # Charged for a while, unplugged, then plugged again: two false readings
    # with a charger contact between them must NOT join or sum into one rate.
    text = log(
        ("100", "true"),
        ("97", "true"),
        ("95", "false"),
        ("90", "false"),
        ("100", "true"),
    )
    summary = nb.summarize(nb.parse_log(text))
    assert len(summary.stretches) == 1
    assert summary.stretches[0].drain == 5
    assert not summary.unplugged_all_night
    report = nb.format_report(summary)
    assert "partially plugged" in report


def test_single_unplugged_reading_has_no_drain():
    text = log(("100", "true"), ("93", "false"), ("100", "true"))
    summary = nb.summarize(nb.parse_log(text))
    assert summary.stretches[0].drain is None
    report = nb.format_report(nb.summarize(nb.parse_log(text)))
    assert "single reading" in report and "gate" not in report


def test_placeholder_readings_are_counted_not_crashed():
    # The app's real null format is `batt=? charging=?` — no percent sign.
    # The helper's log() renders it faithfully for batt="?".
    text = log(("?", "?"), ("?", "?"))
    summary = nb.summarize(nb.parse_log(text))
    assert summary.readings == 2 and summary.skipped_placeholders == 2
    assert summary.stretches == []
    assert "charging state unknown" in nb.format_report(summary)


def test_mixed_known_true_and_unknown_state_is_not_called_plugged():
    text = log(("100", "true"), ("?", "?"))
    summary = nb.summarize(nb.parse_log(text))
    report = nb.format_report(summary)
    assert "no unplugged reading observed" in report
    assert "plugged in all night" not in report


def test_log_without_battery_field_reports_no_telemetry():
    text = "2026-11-05T22:00:00.000000Z [heartbeat] windows=10 skipped=0 clips=0\n"
    summary = nb.summarize(nb.parse_log(text))
    assert not summary.has_telemetry
    assert "no battery telemetry" in nb.format_report(summary)


def test_backwards_timestamp_is_flagged():
    # SessionLogger warns device clock jumps can skew adjacent lines.
    text = log(("100", "false"), ("95", "false"))
    lines = text.splitlines()
    text_backwards = "\n".join([lines[1], lines[0]]) + "\n"
    summary = nb.summarize(nb.parse_log(text_backwards))
    assert summary.clock_backwards
    assert "backwards" in nb.format_report(summary)


def test_garbage_timestamp_line_is_dropped():
    text = log(("100", "false"), ("95", "false"))
    text += "not-a-timestamp [heartbeat] batt=90% charging=false\n"
    summary = nb.summarize(nb.parse_log(text))
    assert summary.readings == 2


def test_readings_sorted_by_time_regardless_of_log_order():
    text = log(("100", "false"), ("95", "false"))
    lines = text.splitlines()
    summary = nb.summarize(nb.parse_log("\n".join([lines[1], lines[0]]) + "\n"))
    stretch = summary.stretches[0]
    assert stretch.readings[0].batt == 100 and stretch.readings[-1].batt == 95
    assert stretch.drain == 5


def test_zero_span_shows_drain_without_rate():
    # Two readings at the same instant: drain is real, a rate is undefined.
    text = log(("100", "false"), ("95", "false"), step_minutes=0)
    report = nb.format_report(nb.summarize(nb.parse_log(text)))
    assert "100% -> 95% (-5%)" in report
    assert "/8h" not in report and "gate" not in report


def test_negative_drain_reported_as_is():
    # Battery rising while unplugged (charger bounce): report, don't clamp.
    text = log(("93", "false"), ("95", "false"))
    report = nb.format_report(nb.summarize(nb.parse_log(text)))
    assert "93% -> 95% (+2%)" in report


def test_main_reads_file(tmp_path, capsys):
    path = tmp_path / "session_14.log"
    path.write_text(log(("100", "true")))
    assert nb.main([str(path)]) == 0
    assert "plugged in all night" in capsys.readouterr().out


def test_main_reads_stdin(monkeypatch, capsys):
    # The documented adb pipe: adb exec-out … | night_battery.py -
    monkeypatch.setattr("sys.stdin", io.StringIO(log(("93", "false"), ("90", "false"))))
    assert nb.main(["-"]) == 0
    assert "(-3%)" in capsys.readouterr().out
