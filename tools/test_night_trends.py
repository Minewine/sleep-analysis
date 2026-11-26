"""Tests for the cross-night snore-vs-vendor trends in night_trends."""

import math
import sqlite3

import night_trends as nt
import pytest

# Reduced live schema (only the columns the tool reads). Real units verified on
# the device: vendor sleep stages arrive as SECONDS, awakenings COUNT, HRV MS.
SCHEMA = """
CREATE TABLE sleep_session (
    id INTEGER PRIMARY KEY, startedAt INTEGER, endedAt INTEGER,
    status TEXT, captureStats TEXT);
CREATE TABLE acoustic_event (
    id INTEGER PRIMARY KEY, sessionId INTEGER, type TEXT,
    startMs INTEGER, endMs INTEGER, userLabel TEXT);
CREATE TABLE measurement (
    id INTEGER PRIMARY KEY, sessionId INTEGER, timestamp INTEGER,
    measurementType TEXT, valueNum REAL, unit TEXT);
"""

DAY_MS = 24 * 3600 * 1000
WINDOW_MS = 8 * 3600 * 1000  # every fixture night captures 8 h
START_MS = 1_793_990_000_000  # fixed epoch; dates render in the local zone


def build_db(path: str = ":memory:", sessions=(), events=(), measurements=()):
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    conn.executemany("INSERT INTO sleep_session VALUES (?,?,?,?,?)", sessions)
    conn.executemany("INSERT INTO acoustic_event VALUES (?,?,?,?,?,?)", events)
    conn.executemany("INSERT INTO measurement VALUES (?,?,?,?,?,?)", measurements)
    conn.commit()
    return conn


def trend_db(
    shares: list[float],
    vendor: dict[str, list[float | None]],
    capture_stats: str | None = None,
    path: str = ":memory:",
):
    """DB of COMPLETED nights: night i snores shares[i] of its 8 h window (one
    event) and carries one measurement per listed metric (None = row absent).
    capture_stats is a literal-JSON template with a {cap} placeholder for the
    capture start (None = no stats at all, the startedAt->endedAt fallback)."""
    sessions, events, measurements = [], [], []
    for i, share in enumerate(shares):
        sid = i + 1
        start = START_MS + i * DAY_MS
        stats = (
            capture_stats.replace("{cap}", str(start + 60_000))
            if capture_stats
            else None
        )
        sessions.append((sid, start, start + WINDOW_MS, "COMPLETED", stats))
        events.append(
            (i + 1, sid, "SNORE", start, start + int(share * WINDOW_MS), None)
        )
        for spec in nt.METRICS:
            values = vendor.get(spec.key)
            if values is None or values[i] is None:
                continue
            unit = (
                "SECONDS"
                if spec.stage_minutes
                else {
                    "awakenings": "COUNT",
                    "hrv": "MS",
                    "score": "INDEX_0_100",
                }[spec.key]
            )
            measurements.append(
                (
                    len(measurements) + 1,
                    sid,
                    start,
                    spec.measurement_type,
                    values[i],
                    unit,
                )
            )
    return build_db(path, sessions, events, measurements)


# ---------------------------------------------------------------- Spearman


def test_spearman_monotone_and_inverted():
    assert nt.spearman([1, 2, 3, 4, 5], [10, 20, 30, 40, 50]) == pytest.approx(1.0)
    assert nt.spearman([1, 2, 3, 4, 5], [50, 40, 30, 20, 10]) == pytest.approx(-1.0)


def test_spearman_ties_get_average_ranks():
    # xs ties at ranks 2/3 -> both 2.5; hand-computed rho = sqrt(0.9).
    rho = nt.spearman([1, 2, 2, 4], [10, 20, 30, 40])
    assert rho == pytest.approx(math.sqrt(0.9))


def test_spearman_two_pairs_is_none():
    assert nt.spearman([1, 2], [2, 1]) is None


def test_spearman_zero_variance_is_none():
    assert nt.spearman([1, 1, 1, 1], [1, 2, 3, 4]) is None


# ------------------------------------------------- SnoreMath label rule


def test_counts_as_snore_replicates_snoremath():
    assert nt.counts_as_snore("SNORE", None)  # no label keeps detector-SNORE
    assert nt.counts_as_snore("SNORE", "SNORE")  # user confirms
    assert nt.counts_as_snore("OTHER_NOISE", "SNORE")  # label overrides detector
    for label in ("COUGH", "OTHER_NOT_SNORE", "SILENCE", "MOVEMENT", "SPEECH"):
        assert not nt.counts_as_snore("SNORE", label)  # labeled out
    assert not nt.counts_as_snore("OTHER_NOISE", None)
    assert not nt.counts_as_snore("SILENCE", None)


def test_labeled_out_events_do_not_count_into_minutes():
    # One night: a 60 s SNORE overlapped by a 120 s SNORE (both kept, union
    # 120 s), a 600 s SNORE labeled COUGH (out), and a 30 s OTHER_NOISE labeled
    # SNORE ten minutes later (in): union 150 s, NOT the ~810 s raw sum.
    sid, start = 1, START_MS
    conn = build_db(
        sessions=[(sid, start, start + WINDOW_MS, "COMPLETED", None)],
        events=[
            (1, sid, "SNORE", start, start + 60_000, None),
            (2, sid, "SNORE", start, start + 600_000, "COUGH"),
            (3, sid, "OTHER_NOISE", start + 600_000, start + 630_000, "SNORE"),
            (4, sid, "SNORE", start, start + 120_000, "SNORE"),
        ],
        measurements=[(1, sid, start, "VENDOR_DEEP_SLEEP", 5400.0, "SECONDS")],
    )
    (night,) = nt.collect_nights(conn)
    assert night.snore_minutes == pytest.approx(150_000 / 60_000)
    assert night.snore_share == pytest.approx(150_000 / WINDOW_MS)
    assert night.metrics["deep"] == pytest.approx(90.0)  # 5400 s -> 90 min


# ----------------------------------------------------- Union minutes


def test_union_merges_overlap_adjacent_contained():
    assert nt.union_duration_ms([]) == 0
    assert (
        nt.union_duration_ms([(20_000, 50_000), (0, 30_000)]) == 50_000
    )  # overlap, unsorted
    assert (
        nt.union_duration_ms([(0, 30_000), (30_000, 60_000)]) == 60_000
    )  # adjacent joins
    assert (
        nt.union_duration_ms([(0, 60_000), (10_000, 20_000)]) == 60_000
    )  # contained adds nothing
    assert nt.union_duration_ms([(10_000, 5_000)]) == 0  # degenerate clamps to a point


# ------------------------------------------------------- Night collection


def test_only_completed_sessions_with_vendor_rows_participate():
    start = START_MS
    sessions = [
        (1, start, start + WINDOW_MS, "COMPLETED", None),  # in
        (2, start + DAY_MS, start + DAY_MS + WINDOW_MS, "RECOVERED", None),  # in
        (
            3,
            start + 2 * DAY_MS,
            start + 2 * DAY_MS + WINDOW_MS,
            "RECORDING",
            None,
        ),  # out: status
        (
            4,
            start + 3 * DAY_MS,
            start + 3 * DAY_MS + WINDOW_MS,
            "COMPLETED",
            None,
        ),  # out: no vendor rows
        (5, start + 4 * DAY_MS, None, "COMPLETED", None),  # out: still open-ended
    ]
    measurements = [
        (1, 1, start, "VENDOR_HRV_RMSSD", 27.0, "MS"),
        (2, 2, start, "VENDOR_HRV_RMSSD", 30.0, "MS"),
        (3, 3, start, "VENDOR_HRV_RMSSD", 30.0, "MS"),
    ]
    nights = nt.collect_nights(build_db(sessions=sessions, measurements=measurements))
    assert [n.session_id for n in nights] == [1, 2]


def test_nights_sorted_by_started_at_not_row_order():
    sessions = [
        (2, START_MS + DAY_MS, START_MS + DAY_MS + WINDOW_MS, "COMPLETED", None),
        (1, START_MS, START_MS + WINDOW_MS, "COMPLETED", None),
    ]
    measurements = [
        (1, 2, 0, "VENDOR_HRV_RMSSD", 25.0, "MS"),
        (2, 1, 0, "VENDOR_HRV_RMSSD", 27.0, "MS"),
    ]
    nights = nt.collect_nights(build_db(sessions=sessions, measurements=measurements))
    assert [n.session_id for n in nights] == [1, 2]
    assert [n.date for n in nights] == sorted(n.date for n in nights)


def test_unattributed_vendor_rows_do_not_create_nights():
    # measurement rows whose sessionId went NULL (outlived their session)
    # attach to nobody and never invent a night.
    measurements = [(1, None, 0, "VENDOR_HRV_RMSSD", 27.0, "MS")]
    assert nt.collect_nights(build_db(measurements=measurements)) == []


def test_duplicate_vendor_rows_latest_wins():
    sessions = [(1, START_MS, START_MS + WINDOW_MS, "COMPLETED", None)]
    measurements = [
        (1, 1, START_MS, "VENDOR_HRV_RMSSD", 10.0, "MS"),
        (2, 1, START_MS + 1000, "VENDOR_HRV_RMSSD", 27.0, "MS"),
    ]
    (night,) = nt.collect_nights(build_db(sessions=sessions, measurements=measurements))
    assert night.metrics["hrv"] == 27.0


def test_quiet_night_is_zero_share_not_missing():
    sessions = [(1, START_MS, START_MS + WINDOW_MS, "COMPLETED", None)]
    measurements = [(1, 1, 0, "VENDOR_HRV_RMSSD", 27.0, "MS")]
    (night,) = nt.collect_nights(build_db(sessions=sessions, measurements=measurements))
    assert night.snore_minutes == 0.0
    assert night.snore_share == 0.0


def test_stage_metric_units_seconds_minutes_or_missing():
    sessions = [(1, START_MS, START_MS + WINDOW_MS, "COMPLETED", None)]
    measurements = [
        (1, 1, 0, "VENDOR_DEEP_SLEEP", 5400.0, "SECONDS"),  # the real unit
        (2, 1, 0, "VENDOR_LIGHT_SLEEP", 300.0, "MINUTES"),
        (3, 1, 0, "VENDOR_REM_SLEEP", 1.0, "HOURS"),  # unknown scale -> missing
    ]
    (night,) = nt.collect_nights(build_db(sessions=sessions, measurements=measurements))
    assert night.metrics["deep"] == pytest.approx(90.0)
    assert night.metrics["light"] == pytest.approx(300.0)
    assert night.metrics["rem"] is None


# ------------------------------------------------- Capture window fallback


def test_capture_window_fallbacks():
    shares = [0.05]
    vendor = {"hrv": [27.0]}
    # No captureStats at all (NULL): fallback startedAt -> endedAt.
    (night,) = nt.collect_nights(trend_db(shares, vendor))
    assert night.snore_share == pytest.approx(0.05)
    # schema v1 stats without capture_started_at: same fallback.
    (night,) = nt.collect_nights(
        trend_db(shares, vendor, capture_stats='{"schema_version":1}')
    )
    assert night.snore_share == pytest.approx(0.05)
    # capture_started_at 60 s in: same union over the shrunken window.
    (night,) = nt.collect_nights(
        trend_db(shares, vendor, capture_stats='{"capture_started_at":{cap}}')
    )
    assert night.snore_share == pytest.approx(0.05 * WINDOW_MS / (WINDOW_MS - 60_000))
    # Garbage stats parse as absent: fallback, not a crash.
    (night,) = nt.collect_nights(trend_db(shares, vendor, capture_stats="not json"))
    assert night.snore_share == pytest.approx(0.05)


def test_corrupt_future_capture_start_falls_back_to_started_at():
    # cap_start >= endedAt is corrupt (morning_report.sh step 4 parity): the
    # window falls back to startedAt -> endedAt instead of going negative —
    # the share is computed, not reported as unknown.
    stats = '{"capture_started_at":' + str(START_MS + WINDOW_MS) + "}"
    (night,) = nt.collect_nights(trend_db([0.05], {"hrv": [27.0]}, capture_stats=stats))
    assert night.snore_share == pytest.approx(0.05)


def test_counts_as_snore_empty_string_label_is_unlabeled():
    # Exported CSVs carry "" for unlabeled (night_aggregates parity): "" must
    # behave like NULL, keeping detector-SNORE events and no others.
    assert nt.counts_as_snore("SNORE", "")
    assert not nt.counts_as_snore("OTHER_NOISE", "")
    assert not nt.counts_as_snore("SNORE", "COUGH")
    assert nt.counts_as_snore("SNORE", None)


def test_empty_label_events_count_into_minutes():
    # Same union as test_labeled_out_events_do_not_count_into_minutes but the
    # kept events carry "" instead of NULL (what a CSV round-trip produces).
    sid, start = 1, START_MS
    conn = build_db(
        sessions=[(sid, start, start + WINDOW_MS, "COMPLETED", None)],
        events=[
            (1, sid, "SNORE", start, start + 60_000, ""),
            (2, sid, "SNORE", start, start + 600_000, "COUGH"),
            (3, sid, "OTHER_NOISE", start + 600_000, start + 630_000, "SNORE"),
            (4, sid, "SNORE", start, start + 120_000, ""),
        ],
        measurements=[(1, sid, start, "VENDOR_DEEP_SLEEP", 5400.0, "SECONDS")],
    )
    (night,) = nt.collect_nights(conn)
    assert night.snore_minutes == pytest.approx(150.0 / 60)


def test_night_date_uses_the_stored_session_time_zone():
    # startedAt 01:30 in Europe/Zurich local time: in a zone one hour behind,
    # the naive local render lands on the wrong "night of" date.
    from datetime import datetime as dt
    from zoneinfo import ZoneInfo

    start = int(dt(2026, 11, 20, 1, 30, tzinfo=ZoneInfo("Europe/Zurich")).timestamp() * 1000)
    assert nt.night_date(start, "Europe/Zurich") == "2026-11-20"
    assert nt.night_date(start, "Europe/London") == "2026-11-20"  # 00:30 local
    # 01:30 Zurich = 00:30 UTC: UTC-8 renders the previous date
    assert nt.night_date(start, "America/Los_Angeles") == "2026-11-19"


def test_night_date_falls_back_to_host_zone_on_bad_tz():
    from datetime import datetime as dt

    start = int(dt(2026, 11, 20, 12, 0).timestamp() * 1000)
    expected = dt.fromtimestamp(start / 1000).strftime("%Y-%m-%d")
    for bad in (None, "", "Not/AZone"):
        assert nt.night_date(start, bad) == expected


def test_collect_nights_renders_dates_in_the_session_time_zone():
    # A reduced schema WITH the timeZone column: dates must come from the
    # stored zone, not the host's.
    import sqlite3
    from datetime import datetime as dt
    from zoneinfo import ZoneInfo

    start = int(dt(2026, 11, 20, 1, 30, tzinfo=ZoneInfo("Europe/Zurich")).timestamp() * 1000)
    conn = sqlite3.connect(":memory:")
    conn.executescript(
        """
        CREATE TABLE sleep_session (
            id INTEGER PRIMARY KEY, startedAt INTEGER, endedAt INTEGER,
            status TEXT, captureStats TEXT, timeZone TEXT);
        CREATE TABLE acoustic_event (
            id INTEGER PRIMARY KEY, sessionId INTEGER, type TEXT,
            startMs INTEGER, endMs INTEGER, userLabel TEXT);
        CREATE TABLE measurement (
            id INTEGER PRIMARY KEY, sessionId INTEGER, timestamp INTEGER,
            measurementType TEXT, valueNum REAL, unit TEXT);
        """
    )
    conn.execute(
        "INSERT INTO sleep_session VALUES (?,?,?,?,?,?)",
        (1, start, start + WINDOW_MS, "COMPLETED", None, "America/Los_Angeles"),
    )
    conn.execute(
        "INSERT INTO measurement VALUES (?,?,?,?,?,?)",
        (1, 1, start, "VENDOR_HRV_RMSSD", 27.0, "MS"),
    )
    (night,) = nt.collect_nights(conn)
    assert night.date == "2026-11-19"


# ----------------------------------------------------- Report formatting


def test_missing_metric_is_dash_and_its_correlation_skipped():
    nights = nt.collect_nights(
        trend_db(
            [0.01, 0.02, 0.03, 0.04, 0.05],
            {"deep": [3000.0, 3100.0, 3200.0, 3300.0, 3400.0]},  # monotone fixture
        )
    )
    table = nt.format_nights(nights)
    assert "—" in table  # awakenings/HRV columns never carried vendor rows
    by_key = {spec.key: (rho, n) for spec, rho, n in nt.correlations(nights)}
    assert by_key["deep"] == (pytest.approx(1.0), 5)  # perfectly monotone
    assert by_key["awakenings"] == (None, 0)  # no pairs -> skipped
    section, verdicts = nt.format_correlations(nt.correlations(nights))
    assert "skipped" in section and "awakenings" in section
    assert verdicts == []


def test_night_missing_one_metric_only_drops_out_of_that_correlation():
    # 5 nights; night 3 lacks deep but has HRV.
    vendor = {"deep": [3000.0, 3100.0, None, 3300.0, 3400.0], "hrv": [27.0] * 5}
    by_key = {
        spec.key: n
        for spec, _, n in nt.correlations(
            nt.collect_nights(trend_db([0.01, 0.02, 0.03, 0.04, 0.05], vendor))
        )
    }
    assert by_key["deep"] == 4  # night 3 excluded from deep only
    assert by_key["hrv"] == 5


def strong_negative_db(path: str = ":memory:", n: int = 8):
    """n nights of perfectly inverse snoring vs deep/score and positive vs
    awakenings (deep/score in SECONDS/INDEX, the DB's real units)."""
    shares = [0.01 * (i + 1) for i in range(n)]
    vendor = {
        "deep": [3600.0 - 100.0 * i for i in range(n)],
        "awakenings": [float(i) for i in range(n)],
        "score": [90.0 - float(i) for i in range(n)],
    }
    return trend_db(shares, vendor, path=path)


def test_verdict_lines_when_strong_and_enough_nights():
    report = nt.format_report(nt.collect_nights(strong_negative_db()))
    assert (
        "snore share vs deep sleep: rho -1.00 over 8 nights"
        " — strong negative association worth an experiment"
    ) in report
    assert (
        "snore share vs awakenings: rho +1.00 over 8 nights"
        " — strong positive association worth an experiment"
    ) in report
    assert "note: N < 10" in report  # honest small-sample caveat


def test_weak_association_gets_summary_line_not_verdicts():
    shares = [0.01, 0.02, 0.03, 0.04, 0.05, 0.06]
    vendor = {"deep": [3000.0, 3600.0, 3000.0, 3600.0, 3000.0, 3600.0]}  # zigzag
    report = nt.format_report(nt.collect_nights(trend_db(shares, vendor)))
    assert "worth an experiment" not in report
    assert "not enough data yet" in report


def test_report_gates_on_min_nights():
    report = nt.format_report(nt.collect_nights(strong_negative_db(n=4)), min_nights=5)
    assert "not enough nights" in report
    assert "worth an experiment" not in report


# ------------------------------------------------------------------ main


def test_main_reads_db_file(tmp_path, capsys):
    db_path = tmp_path / "sleepanalysis.db"
    strong_negative_db(path=str(db_path)).close()
    assert nt.main(["--db", str(db_path)]) == 0
    out = capsys.readouterr().out
    assert "snore share vs deep sleep" in out and "rho -1.00" in out


def test_main_min_nights_flag(tmp_path, capsys):
    db_path = tmp_path / "sleepanalysis.db"
    strong_negative_db(path=str(db_path), n=2).close()
    assert nt.main(["--db", str(db_path)]) == 0
    assert "not enough nights" in capsys.readouterr().out


def test_main_missing_db_file_errors(tmp_path, capsys):
    assert nt.main(["--db", str(tmp_path / "absent.db")]) == 1
    assert "not found" in capsys.readouterr().err
