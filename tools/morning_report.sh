#!/usr/bin/env bash
# One-command morning report (docs/NIGHT_REPORT.md): pulls the latest night and the
# previous FULL night from the phone, exports CSVs + clips + manifest, and runs
# analysis/night_report.py — everything derived from the pulled DB, no session
# identity baked in.
#
# Output lands OUTSIDE this repo and outside /tmp (both are where night data goes to
# die: repo = privacy guard refuses; tmp = wiped overnight): each run gets a fresh
# directory under NIGHT_ROOT (default ~/sleep-local/morning).
#
# The previous-night pick mirrors the app's PreviousNight.pick: night-like sessions
# (>= 4 h in bed) win over later short ones; latest-eligible is the fallback.
#
# Needs: adb + authorized phone, sqlite3 CLI (json1), uv. Run from anywhere:
#   tools/morning_report.sh [adb-serial]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NIGHT_ROOT="${NIGHT_ROOT:-$HOME/sleep-local/morning}"
OUT="$NIGHT_ROOT/$(date +%Y-%m-%d_%H%M%S)"
SERIAL_ARGS=()
[ $# -gt 0 ] && SERIAL_ARGS=(--serial "$1")
DB="$OUT/sleepanalysis.db"

echo "== morning report -> $OUT =="
mkdir -p "$OUT"

# 1. Latest session: CSVs, clips (+ manifest), and the DB (for the picks below).
uv run --directory "$REPO_ROOT" tools/export_night_csv.py \
    "${SERIAL_ARGS[@]}" --out "$OUT" --keep-db --with-clips "$OUT/clips"

sid="$(sqlite3 "$DB" "SELECT id FROM sleep_session ORDER BY startedAt DESC LIMIT 1;")"
if [ -z "$sid" ]; then
    echo "error: no sessions in the database" >&2
    exit 1
fi

# 2. Previous full night (PreviousNight.pick semantics: prefer >= 4 h in bed,
#    then latest startedAt, then highest id — all COMPLETED/RECOVERED, strictly earlier).
prev="$(sqlite3 "$DB" "
    SELECT id FROM sleep_session
    WHERE id != $sid AND endedAt IS NOT NULL
      AND status IN ('COMPLETED', 'RECOVERED')
      AND startedAt < (SELECT startedAt FROM sleep_session WHERE id = $sid)
    ORDER BY (endedAt - startedAt >= 14400000) DESC, startedAt DESC, id DESC
    LIMIT 1;")"
if [ -z "$prev" ]; then
    echo "error: no previous session to compare session $sid against" >&2
    exit 1
fi
echo "session $sid vs previous $prev"

# 3. Previous night's CSVs into the same directory (no clips needed there).
uv run --directory "$REPO_ROOT" tools/export_night_csv.py \
    "${SERIAL_ARGS[@]}" --session "$prev" --out "$OUT"

# 4. Epochs for the night under analysis: tap start, capture window (capture_started_at
#    when the stats carry it, session start otherwise), loudest snore clip (if any).
#    -separator ' ' so `read` can split the row; an empty clip column is a quiet night.
read -r start end cap_start clip_rel < <(sqlite3 -separator ' ' "$DB" "
    SELECT s.startedAt, s.endedAt,
           COALESCE(json_extract(s.captureStats, '\$.capture_started_at'), s.startedAt),
           COALESCE(
               (SELECT c.filePath FROM acoustic_event e
                JOIN audio_clip c ON c.eventId = e.id OR c.id = e.clipId
                WHERE e.sessionId = $sid AND e.type = 'SNORE'
                ORDER BY e.peakDbfs DESC LIMIT 1),
               '')
    FROM sleep_session s WHERE s.id = $sid;")
clip=""
[ -n "$clip_rel" ] && clip="$(basename "$clip_rel")"
if [ -z "$start" ] || [ -z "$end" ]; then
    echo "error: session $sid has no complete capture window (open or still recording?) — stop the night first" >&2
    exit 1
fi

# 4b. Capture stats for night validity (NightValidity.captureSummary semantics, consumed
#     by night_report.py via the NIGHT_VALIDITY_* env): capture_started_at-aware window
#     seconds (used only when < endedAt, else session start — the app's window rule,
#     NightValidity.kt/ValidityCalculator.kt), thermal events, and the thermal-span hop
#     sum ('$[2]' of each span, like the Kotlin skippedHopSpans.sumOf — never
#     level_gate_skipped). windows_processed gates the export: NULL captureStats (or
#     missing keys) leaves every NIGHT_VALIDITY_* var unset so genuine stats_missing
#     still fires — deliberately stricter than the app, which builds a non-null summary
#     for the same row (null windowsProcessed invents no failure, possibly grade A);
#     do not 'fix' this back. The gate column is read LAST because `read` collapses runs
#     of the ' ' separator, so the row's only nullable column must not sit mid-row.
read -r nv_secs nv_thermal nv_hops nv_windows < <(sqlite3 -separator ' ' "$DB" "
    SELECT (s.endedAt - CASE WHEN json_extract(s.captureStats, '\$.capture_started_at') < s.endedAt THEN json_extract(s.captureStats, '\$.capture_started_at') ELSE s.startedAt END) / 1000,
           COALESCE(json_extract(s.captureStats, '\$.thermal_throttle_events'), 0),
           COALESCE((SELECT SUM(json_extract(v.value, '\$[2]')) FROM json_each(s.captureStats, '\$.thermal_skipped_hops') v), 0),
           json_extract(s.captureStats, '\$.windows_processed')
    FROM sleep_session s WHERE s.id = $sid;")
if [ -n "$nv_windows" ]; then
    export NIGHT_VALIDITY_WINDOWS_PROCESSED="$nv_windows" \
        NIGHT_VALIDITY_WINDOW_SECONDS="$nv_secs" \
        NIGHT_VALIDITY_THERMAL_EVENTS="$nv_thermal" \
        NIGHT_VALIDITY_SKIPPED_HOPS="$nv_hops"
else
    unset NIGHT_VALIDITY_WINDOWS_PROCESSED NIGHT_VALIDITY_WINDOW_SECONDS \
        NIGHT_VALIDITY_THERMAL_EVENTS NIGHT_VALIDITY_SKIPPED_HOPS
fi

# 5. Report + charts.
NIGHT_DATA="$OUT" \
NIGHT_REPORT_OUT="$OUT" \
NIGHT_EVENTS_CSV="events_$sid.csv" \
NIGHT_PREV_EVENTS_CSV="events_$prev.csv" \
NIGHT_REPORT_CLIP="$clip" \
NIGHT_CAPTURE_START_MS="$cap_start" \
NIGHT_CAPTURE_END_MS="$end" \
NIGHT_START_MS="$start" \
NIGHT_PREV_START_MS="$(sqlite3 "$DB" "SELECT startedAt FROM sleep_session WHERE id = $prev;")" \
    uv run --directory "$REPO_ROOT" --with matplotlib --with numpy \
    -- python -I "$REPO_ROOT/analysis/night_report.py"

# 6. Battery drain from the session log (heartbeat + stop lines carry
#    batt=/charging=). Drain is only meaningful over charging=false stretches —
#    the tool says "plugged in all night" rather than inventing a 0% rate.
session_log="$OUT/session_${sid}.log"
if adb "${SERIAL_ARGS[@]}" exec-out run-as com.sleepanalysis \
        cat "files/logs/session_${sid}.log" > "$session_log" &&
   [ -s "$session_log" ]; then
    echo "== BATTERY (session $sid, from its session log) =="
    uv run --directory "$REPO_ROOT" tools/night_battery.py "$session_log"
else
    rm -f "$session_log"
    echo "== BATTERY: skipped — log pull failed (absent, device offline, or run-as denied) =="
fi

echo "== done: $OUT (charts: $OUT/chart_*.png) =="
