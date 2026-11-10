"""Parity pin for the previous-night pick SQL in tools/morning_report.sh.

The bash script hand-copies the app's PreviousNight.pick rule (domain/report/
ReportAssembler.kt) because it runs before any Python is involved. This test runs
that exact SQL against a synthetic sleep_session table so any drift between the two
copies — or from the Kotlin rule — fails loudly. Keep the SQL text identical to the
script's (the two WHERE/ORDER BY clauses below).

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
