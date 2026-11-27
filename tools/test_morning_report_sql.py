"""Parity pin for the previous-night pick SQL in tools/morning_report.sh.

The bash script hand-copies the app's PreviousNight.pick rule (domain/report/
ReportAssembler.kt) because it runs before any Python is involved. This test runs
that exact SQL against a synthetic sleep_session table so any drift between the two
copies — or from the Kotlin rule — fails loudly. Keep the SQL text identical to the
script's (the two WHERE/ORDER BY clauses below).

Also pins the step-4 capture-stats SQL that feeds the NIGHT_VALIDITY_* env (the app's
NightValidity.captureSummary semantics: capture_started_at-aware window seconds,
thermal-span hop sum). windows_processed is the bash export gate and is read LAST —
`read` collapses runs of the ' ' separator, so the row's only nullable column must
not sit mid-row.

Fixture: the real dogfood history that motivated the rule — session 8 is a short
evening test recording that sits between the last full night and the current one.
"""

import sqlite3
from pathlib import Path

PICK_SQL = """
    SELECT id FROM sleep_session
    WHERE id != {sid} AND endedAt IS NOT NULL
      AND status IN ('COMPLETED', 'RECOVERED')
      AND startedAt < (SELECT startedAt FROM sleep_session WHERE id = {sid})
    ORDER BY (endedAt - startedAt >= 14400000) DESC, startedAt DESC, id DESC
    LIMIT 1;
"""

H = 3_600_000

# Pinned copy of the script's step-4b query: bash '\$' escapes resolved to plain '$'
# (the SQL sqlite3 actually runs) and $sid parameterized. validity_stats() re-reads
# tools/morning_report.sh at test time and asserts this text still matches it, so
# script-side drift fails loudly instead of staying green behind this copy.
VALIDITY_SQL = """
    SELECT (s.endedAt - CASE WHEN json_extract(s.captureStats, '$.capture_started_at') < s.endedAt THEN json_extract(s.captureStats, '$.capture_started_at') ELSE s.startedAt END) / 1000,
           COALESCE(json_extract(s.captureStats, '$.thermal_throttle_events'), 0),
           COALESCE((SELECT SUM(json_extract(v.value, '$[2]')) FROM json_each(s.captureStats, '$.thermal_skipped_hops') v), 0),
           json_extract(s.captureStats, '$.windows_processed')
    FROM sleep_session s WHERE s.id = {sid};
"""


def script_validity_sql() -> str:
    """The script's step-4b query between the '# 4b.' marker and 'WHERE s.id = $sid;',
    with bash '\\$' resolved to '$' and $sid parameterized to {sid}."""
    script = Path(__file__).resolve().parent / "morning_report.sh"
    text = script.read_text(encoding="utf-8")
    marker = text.index("# 4b.")
    start = text.index("SELECT", marker)
    end = text.index("$sid;", marker) + len("$sid;")
    return text[start:end].replace("\\$", "$").replace("$sid", "{sid}")


def build_sessions(
    conn: sqlite3.Connection, rows: list[tuple[int, int, int | None, str]]
) -> None:
    conn.execute(
        "CREATE TABLE sleep_session (id INTEGER PRIMARY KEY, startedAt INTEGER,"
        " endedAt INTEGER, status TEXT)"
    )
    conn.executemany(
        "INSERT INTO sleep_session (id, startedAt, endedAt, status) VALUES (?,?,?,?)",
        rows,
    )


def pick(conn: sqlite3.Connection, sid: int) -> int | None:
    row = conn.execute(PICK_SQL.format(sid=sid)).fetchone()
    return row[0] if row else None


def test_short_evening_session_never_becomes_the_anchor():
    """Sessions 5/8/9 of the dogfood DB: the 1 h evening recording (8) must lose to
    the full night (5) as the anchor of night 9's comparison."""
    conn = sqlite3.connect(":memory:")
    build_sessions(
        conn,
        [
            (5, 0 * H, 7 * H + 28 * 60_000, "COMPLETED"),  # full night
            (8, 10 * H, 11 * H + 8 * 60_000, "COMPLETED"),  # 1h08m evening stray
            (9, 12 * H, 19 * H + 33 * 60_000, "COMPLETED"),  # current
        ],
    )
    assert pick(conn, 9) == 5


def test_falls_back_to_latest_short_session_without_a_full_night():
    conn = sqlite3.connect(":memory:")
    build_sessions(
        conn,
        [
            (2, 0 * H, 1 * H, "COMPLETED"),
            (3, 5 * H, 6 * H, "COMPLETED"),
            (4, 10 * H, 17 * H, "COMPLETED"),  # current
        ],
    )
    assert pick(conn, 4) == 3


def test_interrupted_and_open_sessions_are_ineligible():
    conn = sqlite3.connect(":memory:")
    build_sessions(
        conn,
        [
            (1, 0 * H, 8 * H, "INTERRUPTED"),
            (2, 10 * H, None, "RECORDING"),  # open
            (3, 20 * H, 21 * H, "COMPLETED"),
            (4, 30 * H, 37 * H, "COMPLETED"),  # current
        ],
    )
    assert pick(conn, 4) == 3


def test_recovered_nights_are_eligible():
    conn = sqlite3.connect(":memory:")
    build_sessions(
        conn,
        [
            (1, 0 * H, 8 * H, "RECOVERED"),
            (2, 10 * H, 17 * H, "COMPLETED"),  # current
        ],
    )
    assert pick(conn, 2) == 1


def test_latest_full_night_wins_over_equal_length_older(tmp_path: Path):
    conn = sqlite3.connect(":memory:")
    build_sessions(
        conn,
        [
            (1, 0 * H, 7 * H, "COMPLETED"),
            (2, 10 * H, 17 * H, "COMPLETED"),
            (3, 20 * H, 27 * H, "COMPLETED"),  # current
        ],
    )
    assert pick(conn, 3) == 2


def build_sessions_with_stats(
    conn: sqlite3.Connection, rows: list[tuple[int, int, int, str, str | None]]
) -> None:
    conn.execute(
        "CREATE TABLE sleep_session (id INTEGER PRIMARY KEY, startedAt INTEGER,"
        " endedAt INTEGER, status TEXT, captureStats TEXT)"
    )
    conn.executemany(
        "INSERT INTO sleep_session (id, startedAt, endedAt, status, captureStats)"
        " VALUES (?,?,?,?,?)",
        rows,
    )


def validity_stats(
    conn: sqlite3.Connection, sid: int
) -> tuple[int, int, int, int | None]:
    """The row the script `read`s: window seconds, thermal events, skipped hops, windows.

    First asserts the pinned VALIDITY_SQL still equals the script's step-4b query
    (whitespace-collapsed), so every fixture test fails loudly if the two drift.
    The bash gate exports NIGHT_VALIDITY_* only when windows_processed (last column,
    the query's single nullable one) is non-NULL — see the module docstring.
    """
    assert " ".join(script_validity_sql().split()) == " ".join(VALIDITY_SQL.split())
    row = conn.execute(VALIDITY_SQL.format(sid=sid)).fetchone()
    assert row is not None
    return row


def test_full_capture_stats_map_to_the_four_validity_values():
    """Delayed capture start + thermal spans: window seconds count from
    capture_started_at (truncated to whole seconds, like the Kotlin Long division) and
    hops sum the '$[2]' of each thermal_skipped_hops span."""
    conn = sqlite3.connect(":memory:")
    build_sessions_with_stats(
        conn,
        [
            (
                9,
                0 * H,
                8 * H,
                "COMPLETED",
                (
                    '{"schema_version":3,"windows_processed":5407,"windows_skipped":3,'
                    '"ambient_dbfs_median":-38.5,"clip_count":44,'
                    '"thermal_throttle_events":2,'
                    f'"capture_started_at":{H + 500},'
                    f'"thermal_skipped_hops":[[{H},{2 * H},3],[{3 * H},{4 * H},1]]}}'
                ),
            )
        ],
    )
    # (8h - 1h000.5s) / 1000 = 25199.5, truncated like Kotlin Long division.
    assert validity_stats(conn, 9) == (25199, 2, 4, 5407)


def test_null_or_keyless_capture_stats_leave_the_vars_unset():
    """NULL captureStats (pre-stats rows) and stats without windows_processed both
    keep the gate off: the script exports NIGHT_VALIDITY_* only when the last column
    is non-NULL, so capture_from_env keeps returning None → genuine stats_missing."""
    conn = sqlite3.connect(":memory:")
    build_sessions_with_stats(
        conn,
        [
            (1, 0 * H, 8 * H, "COMPLETED", None),
            (2, 10 * H, 18 * H, "COMPLETED", '{"schema_version":1}'),
        ],
    )
    # Window falls back to tap-to-stop time in bed; thermal/hops COALESCE to 0;
    # the gate column stays NULL in both rows.
    assert validity_stats(conn, 1) == (28800, 0, 0, None)
    assert validity_stats(conn, 2) == (28800, 0, 0, None)


def test_stats_without_thermal_spans_still_get_zero_hops():
    conn = sqlite3.connect(":memory:")
    build_sessions_with_stats(
        conn,
        [
            (
                3,
                0 * H,
                8 * H,
                "COMPLETED",
                (
                    '{"schema_version":1,"windows_processed":5407,"clip_count":44,'
                    f'"thermal_throttle_events":0,"capture_started_at":{H}}}'
                ),
            )
        ],
    )
    assert validity_stats(conn, 3) == (25200, 0, 0, 5407)
