# 10. Python tooling and CI

**Scope:** `analysis/` (night_report.py, night_aggregates.py, night_validity.py and tests) · `tools/` (backup_db.sh, morning_report.sh, export_night_csv.py, export_fixtures.py, night_battery.py, night_loudest.py, night_trends.py and tests) · `fixtures/` · `pyproject.toml`, `uv.lock`, `.github/workflows/ci.yml` (Python job). The "user" is the owner running scripts on a laptop; "persisted data" is what scripts read from the phone (via `adb run-as`) or from exported files.
**Requirement prefix:** PYT — rows in N.3 are numbered PYT-01, PYT-02, …
**Status values:** `tested` = a unit/instrumented test asserts it (cite it) · `device-verified` = evidence from a real night or device run exists (cite where) · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

Live local run (this session, from the repo root): `uv run pytest` → `116 passed in 0.43s`; `uv run ruff check .` → `All checks passed!`. No script was run in device mode (a real phone with irreplaceable recordings was attached).

## N.1 What the user gets

1. One command snapshots the phone's database (db + wal + shm) into a timestamped folder, validates the copy, and keeps the newest 30 snapshots [PYT-01, PYT-02].
2. One command produces the full morning report: pulls the latest night and the previous full night, exports CSVs + clips + the DB, prints metrics, validity grade, charts, battery drain and cross-night trends into a fresh directory outside the repo [PYT-03 – PYT-05, PYT-09, PYT-13, PYT-16, PYT-19].
3. Any night's session data can be exported as fixed-header CSVs (events, session + flattened captureStats, measurements, quiet gaps, sleep stages, Garmin sleep window) for offline analysis [PYT-06 – PYT-08].
4. Labeled clip WAVs can be exported as a fixture set (fixtures/) that the in-app fixture eval replays offline to score the detector's precision/recall against user labels [PYT-10].
5. Every binary pull from the phone is verified (on-device byte count + RIFF magic) so a mangled USB transfer can never silently corrupt data [PYT-11, PYT-12].
6. A session log can be turned into a per-stretch battery-drain report with an explicit plugged-in and unreadable-telemetry story [PYT-13, PYT-14].
7. The loudest events of a night can be ranked with per-hour noise profile and optional clip export [PYT-15].
8. Cross-night Spearman correlations of label-aware snore share vs Garmin vendor sleep metrics, with honest small-N gates [PYT-16].
9. Every PR and push to main runs `ruff check .` and the whole pytest suite under uv in CI [PYT-20].

## N.2 How it works today

### N.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| backup_db.sh | `tools/backup_db.sh` | Snapshot db + wal + shm off the phone; validate; prune to newest 30 |
| morning_report.sh | `tools/morning_report.sh` | 7-step orchestration of the whole morning report |
| export_night_csv.py | `tools/export_night_csv.py` | Pull DB, write per-session CSVs + clip manifest; Garmin payload export |
| export_fixtures.py | `tools/export_fixtures.py` | Pull DB + clip WAVs, write fixture set + manifest.json; owns shared adb helpers |
| night_battery.py | `tools/night_battery.py` | Battery drain per unplugged stretch from a session log |
| night_loudest.py | `tools/night_loudest.py` | Rank loudest events, hourly profile, optional clip export |
| night_trends.py | `tools/night_trends.py` | Spearman snore share vs Garmin metrics over completed nights |
| night_report.py | `analysis/night_report.py` | Env-configured per-night analysis, validation, validity grade, 4 PNG charts |
| night_aggregates.py | `analysis/night_aggregates.py` | Pure label-aware snore rules + clip-manifest matching (mirror of app) |
| night_validity.py | `analysis/night_validity.py` | Pure night-validity grade mirror of the app's NightValidity.kt |
| FixtureEvaluator | `app/src/main/java/com/sleepanalysis/monitoring/eval/FixtureEvaluator.kt` | In-app consumer of the exported fixture set (replay + precision/recall) |

All Python tools are stdlib-only at runtime; `analysis/night_report.py` additionally needs matplotlib + numpy, supplied at the call sites (`tools/morning_report.sh` step 5 and the `analysis/night_report.py` module docstring, both `uv run --with matplotlib --with numpy`), not declared in `pyproject.toml`.

### N.2.2 Flows

#### Flow: database snapshot (tools/backup_db.sh)

1. SERIAL = argv[1]; the adb invocation array is built as `ADB=(adb)` or `ADB=(adb -s SERIAL)`; the literal `adb` stays inside the replacement and the trailing literal argument stays outside `"${ADB[@]}"` (two comment blocks record this exact bash pitfall — an earlier version replaced the whole array and later appended get-state to the `-s` element, so the guard always said "no device").
2. `adb get-state` fails → print "backup: no device attached — skipped", exit 0.
3. For each of sleepanalysis.db, sleepanalysis.db-wal, sleepanalysis.db-shm: `adb exec-out run-as com.sleepanalysis cat databases/<f>` into SLEEP_BACKUP_ROOT (default ~/sleep-local/backups)/\<timestamp\>/; per-file failure tolerated (`|| true`).
4. Main db missing or empty (`! -s`) → exit 1 with a debug-build/locked-device hint.
5. Embedded `python3 -I` heredoc runs `PRAGMA quick_check` and `SELECT COUNT(*) FROM sleep_session`; check != "ok" or 0 sessions (the truncated-pull signature) → exit 1.
6. Prune: `ls -1dt "$ROOT"/*/ | tail -n +31 | xargs -r rm -rf` — newest 30 kept.

#### Flow: morning report (tools/morning_report.sh, `set -euo pipefail`)

1. NIGHT_ROOT (default ~/sleep-local/morning)/\<timestamp\> is the output dir; arg 1 is the adb serial, split into SERIAL_ARGS=(--serial …) for the Python tools and ADB_ARGS=(-s …) for plain adb.
2. Step 1: `uv run tools/export_night_csv.py --out $OUT --keep-db --with-clips $OUT/clips` — latest session, CSVs + clips + DB copy.
3. Step 2: sqlite3 picks the latest session by `startedAt DESC`; empty → exit 1. Then the previous night is picked by SQL that mirrors the app-side pick (app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt, object PreviousNight, fun pick) exactly: `endedAt IS NOT NULL AND status IN ('COMPLETED','RECOVERED') AND startedAt < current`, `ORDER BY (endedAt - startedAt >= 14400000) DESC, startedAt DESC, id DESC` (night-like >= 4 h first). No previous session → exit 1.
4. Step 3: previous night's CSVs exported into the same directory.
5. Step 4: reads startedAt, endedAt, `COALESCE(captureStats.$.capture_started_at, startedAt)` and the loudest-SNORE clip (subquery joining `audio_clip c ON c.eventId = e.id OR c.id = e.clipId`, `ORDER BY e.peakDbfs DESC LIMIT 1`; empty clip = quiet night). No endedAt → exit 1 ("stop the night first").
6. Step 4b: derives the four validity inputs (window_seconds from the capture-start-aware window, thermal_throttle_events, `SUM($[2])` over thermal_skipped_hops spans, windows_processed) and exports the NIGHT_VALIDITY_* env vars only when windows_processed is non-NULL; NULL captureStats leaves them unset so a genuine stats_missing still fires — deliberately stricter than the app (script comment: "do not 'fix' this back"). The gate column is read LAST because `read` collapses runs of the space separator.
7. Step 5: `uv run --with matplotlib --with numpy -- python -I analysis/night_report.py` with NIGHT_DATA, NIGHT_REPORT_OUT, NIGHT_EVENTS_CSV, NIGHT_PREV_EVENTS_CSV, NIGHT_REPORT_CLIP, NIGHT_CAPTURE_START_MS/END_MS, NIGHT_START_MS, NIGHT_PREV_START_MS.
8. Step 6: pulls `files/logs/session_<sid>.log` via `run-as cat`; on success runs night_battery.py, else removes the file and prints a skip note.
9. Step 7: night_trends.py `--db $DB` piped through tee into night_trends.txt; a failing trends tool is skipped without losing earlier artifacts.

#### Flow: night export (tools/export_night_csv.py)

1. `main` resolves `--out` and `--with-clips` through `resolve_out_dir` before any adb traffic — a path inside the repository is refused with exit 1 (privacy guard, docs/PRIVACY_AND_REGULATORY.md).
2. `pull_db` (imported from export_fixtures) copies db + WAL into a temp dir; `detect_serial` (local copy) picks the device.
3. `export_csvs` writes events_\<sid\>.csv, session_\<sid\>.csv (fixed SESSION_COLUMNS + flattened captureStats keys), measurements_\<sid\>.csv, quiet_gaps_\<sid\>.csv, sleep_stage_\<sid\>.csv; unknown session → ValueError → exit 1.
4. `fetch_sleep_stages` reads `PRAGMA table_info(sleep_stage)` and emits source as literal GARMIN on pre-v14 schemas lacking the column.
5. `fetch_garmin_payload` looks up the raw_payload table by (source='GARMIN', date=\<wake date\>, endpoint='/sleep-service/sleep/dailySleep'); `garmin_wake_date` = local date of endedAt, or startedAt + 12 h for open sessions; no payload → the Garmin CSV is omitted with a note; payload present but unusable → note, no CSV.
6. `--with-clips`: `export_clips` joins events to clips (`c.eventId = e.id OR c.id = e.clipId`), pulls each WAV via the verified `pull_clip`, records every entry with exported/error in manifest_\<sid\>.json.
7. `--keep-db`: `PRAGMA wal_checkpoint(TRUNCATE)` then copies the DB next to the CSVs.

#### Flow: fixture export (tools/export_fixtures.py)

1. `pull_db`: 3 attempts (2 s apart) of `exec-out run-as … cat` for db then WAL; an empty WAL copy fails the attempt (Room runs WAL mode — the WAL holds most recent writes); `_validate_db` requires quick_check to pass (tolerating exactly the benign `NULL value in audio_clip.appliedGain` false positive that SQLite >= 3.44 raises on pre-v11 rows written before the `ALTER TABLE … NOT NULL DEFAULT 2.5` migration, cross-checked with a `typeof(appliedGain)='null'` scan) and `COUNT(*) FROM sleep_session >= 1`; exhaustion raises RuntimeError.
2. The QUERY joins `audio_clip c ON c.eventId = e.id` — valid because the app back-fills audio_clip.eventId after inserting both rows (`app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `insertEvent` calls `clipDao.setEventId(clipId, eventId)`).
3. Each clip: `remote_size` (`run-as … wc -c`, count read from the first whitespace token) → `exec-out run-as … cat` → byte-count comparison (mismatch: delete + ValueError) → RIFF magic check (failure: delete + ValueError). A failed clip is skipped with a warning and simply absent from the manifest.
4. `clip_name` builds the fixture-relative path session_\<sid\>/evt_\<start_ms\>.wav; `build_entry` embeds featuresJson parsed or null; `write_manifest` writes manifest.json with sample_rate=16000, clip_ms=8000, exported_at, source_session, floor_dbfs (median of the entries' features.noise_floor_dbfs, the ReplayEngine pre-roll), entries[].
5. `--min-labels N` keeps only user-labeled clips; no clip-backed events → exit 1.

#### Flow: fixture eval (app side)

1. `app/src/test/java/com/sleepanalysis/monitoring/eval/FixtureEvalRunTest.kt` reads fixtures/manifest.json, skips silently (assumeTrue) when absent; per entry it takes clip (path), type, user_label (JSON null → null) and the top-level floor_dbfs.
2. FixtureEvaluator.evaluate (in app/src/main/java/com/sleepanalysis/monitoring/eval/FixtureEvaluator.kt) decodes each WAV (WavReader.readPcm16Mono), rejects rate != DetectorConfig.SAMPLE_RATE, resets the detector per clip, replays via ReplayEngine, computes snore-window ratio >= clipPositiveThreshold (default 0.5) as the clip verdict, and reduces labels to one-vs-rest precision/recall. DETECTOR_VERSION = energy-heuristic-v3-peakgate, kept in manual sync with SessionRepository.MODEL_VERSION.

#### Flow: battery report (tools/night_battery.py)

1. `parse_log` regex `batt=(\d+|\?)%? charging=(true|false|\?)` — note no % after ?, matching the app's format (`app/src/main/java/com/sleepanalysis/monitoring/service/BatteryStatus.kt` · `format`); ISO timestamp from the first field, unparseable lines dropped.
2. `summarize`: clock-jump detection on log order, drain math on time-sorted readings; a run of charging=false readings forms a stretch; true/? ends it.
3. `format_report`: no telemetry at all → "build predates the batt= field"; all-unreadable → "charging state unknown"; no unplugged reading → "plugged in all night"; per-stretch lines with %→% delta and rate; longest stretch compared against the device-drain tripwire GATE_PCT_PER_8H (explicitly NOT the app's <=10%/8h attributed-usage gate; `--gate` overrides). Exit code always 0.

#### Flow: trends (tools/night_trends.py)

1. `--db` reads an already-pulled DB; otherwise `pull_db` from the phone. Missing `--db` file → exit 1.
2. `collect_nights`: latest `VENDOR\_%` measurement rows per session (sessionId IS NOT NULL, later timestamp wins), joined to COMPLETED/RECOVERED sessions; nights without vendor rows never exist; `capture_window_ms` re-uses the capture_started_at-else-startedAt rule, but unlike morning_report.sh step 4 it takes capture_started_at unconditionally (no < endedAt guard) — a corrupt capture_started_at >= endedAt yields a negative window and thus share None (unknown), not the startedAt fallback the script applies; snore share = `union_duration_ms` of label-aware events / window (0 for a quiet night, None only when the window is unknown).
3. `spearman` (tie-corrected average ranks, Pearson on ranks) returns None under the MIN_PAIRS floor or zero variance; `format_correlations` emits a verdict line only when abs(rho) >= VERDICT_ABS_RHO with >= VERDICT_MIN_N nights, plus a small-N caveat under SMALL_N; `format_report` gates on MIN_NIGHTS.

#### Flow: night report (analysis/night_report.py)

1. Module-level env reads (missing required var → KeyError, non-zero exit): NIGHT_DATA, NIGHT_REPORT_OUT, NIGHT_EVENTS_CSV, NIGHT_PREV_EVENTS_CSV, NIGHT_CAPTURE_START_MS, NIGHT_CAPTURE_END_MS, NIGHT_START_MS, NIGHT_PREV_START_MS; labels derive from the CSV filenames.
2. `load_events` reads the export CSVs as untrusted input; snore set via `night_aggregates.events_counting_as_snore`, noise = detector type OTHER_NOISE, detector-SNORE events with a non-SNORE label are counted and reported as excluded.
3. Validation section: duration mismatches (>1 ms), events outside the capture window, confidence < 0.5, snores > 30 s, overlapping adjacent SNORE pairs, quiet gaps > 10 min.
4. Validity: `night_validity.assess` + `capture_from_env` (the NIGHT_VALIDITY_* env block) + optional NIGHT_VALIDITY_UNCAPTURED_MIN, _SESSION_STATUS, _RESUMED, _SNORE_SHARE, _AMBIENT_Z, NIGHT_VALIDITY_GARMIN_WARNINGS (JSON array).
5. Core metrics, bouts at gap <= 1/2/5/10 s, hourly distribution (union-aware, split across hour boundaries), first/second-half trend, 15-min share/peak slopes.
6. WAV cross-check: decodes clips/\<NIGHT_REPORT_CLIP\> (16 kHz int16), compares decoded PCM peak to the DB peak; the DB event is found via manifest_\<sid\>.json (`night_aggregates.match_clip_entry`, only exported:true entries) with fallback to the legacy filename regex evt_\<type\>_\<start_ms\>_\<seq\>.wav (±3 s window) for pre-manifest exports.
7. Charts: chart_timeline.png, chart_hourly.png, chart_durations.png, chart_trend.png into NIGHT_REPORT_OUT. Time zone: hardcoded Europe/Zurich (module constant TZ).

### N.2.3 State and stored data

- **Database tables read** (written by the app, `app/src/main/java/com/sleepanalysis/data/db/entity/Entities.kt`; the lists name the contract surface the tooling selects — export CSV headers and QUERY columns — not every schema column): sleep_session (id, startedAt, endedAt, status, timeZone, deviceModel, osVersion, appVersion, audioBytesTotal, morningNotes, resumedAfterInterruption, createdAt, captureStats) · acoustic_event (id, sessionId, type, startMs, offsetMs, endMs, durationMs, peakDbfs, meanDbfs, intensityTier, confidence, userLabel, labeledAt, featuresJson, clipId) · audio_clip (id, sessionId, filePath, durationMs, sizeBytes, eventId unique index, appliedGain, filePeakDbfs) · measurement (id, sessionId, timestamp, sourceType, method, isClinical, measurementType VENDOR_*, tier, valueNum, valueText, unit, confidence, modelVersion, periodStart, periodEnd, notes, createdAt) · quiet_gap (id, sessionId, startMs, endMs, durationMs, kind, meanDbfs) · sleep_stage (id, sessionId, startMs, endMs, stage, source from schema v14, createdAt) · raw_payload (source, date, endpoint, payload).
- **captureStats JSON** written by `app/src/main/java/com/sleepanalysis/monitoring/audio/NightStatsMerge.kt` · `captureStatsJson`, schema_version 6: windows_processed, windows_skipped, ambient_dbfs_median, ambient_dbfs_p95, clip_count, thermal_throttle_events, level_gate_skipped, spectral_skipped, optional saved_at_ms, capture_started_at, thermal_skipped_hops as [startEpochMs, endEpochMs, hops] spans, client_silenced_spans, ambient_level_buckets. Scripts consume capture_started_at, thermal_throttle_events, thermal_skipped_hops ($[2] of each span, never level_gate_skipped) and windows_processed; `tools/export_night_csv.py` · `flatten_capture_stats` keeps a malformed payload verbatim under capture_stats_raw.
- **Session log** files/logs/session_\<id\>.log, appended by `app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt` · `startHeartbeat` (every HEARTBEAT_INTERVAL_MS = 15 min) and the recording_stop line; both end with `app/src/main/java/com/sleepanalysis/monitoring/service/BatteryStatus.kt` · `format` → batt=NN% charging=true|false, or batt=? charging=? when unavailable. Parsed by the module regex BATT_RE in `tools/night_battery.py` (parse_log).
- **Fixture format** (the one contract both sides agree on): directory fixtures/session_\<sid\>/evt_\<start_ms\>.wav (16 kHz, PCM16 mono, <= 8 s clips) + fixtures/manifest.json with top-level sample_rate (16000), clip_ms (8000), exported_at (ISO), source_session (int), floor_dbfs (float or null) and entries[] of {event_id, session_id, type, user_label (string or null), start_ms, offset_ms, duration_ms, peak_dbfs, mean_dbfs, features (parsed object or null), clip ("session_\<sid\>/evt_\<start_ms\>.wav")}. Writer: `tools/export_fixtures.py` · `write_manifest` / `build_entry`; consumer: FixtureEvalRunTest.kt (clip, type, user_label, floor_dbfs) feeding app/src/main/java/com/sleepanalysis/monitoring/eval/FixtureEvaluator.kt. The manifest does NOT record the on-device path; the fixture clip name is exporter-chosen and does not equal the on-device name (`app/src/main/java/com/sleepanalysis/data/files/ClipStore.kt` · `writeClip` writes evt_\<type\>_\<startMs\>_\<n\>.wav) — only the manifest join, not the filename, is the contract.
- **Export manifest** manifest_\<sid\>.json (`tools/export_night_csv.py` · `export_clips`): {"source_session", "clips": [{event_id, event_type, start_ms, end_ms, peak_dbfs, confidence, path (basename), exported (bool), error (string or null)}]}. Consumed by `analysis/night_report.py` via `match_clip_entry` (only exported:true entries).
- **CSV formats**: fixed headers per SESSION_COLUMNS, EVENTS_COLUMNS (id,start_ms,end_ms,duration_ms,type,user_label,peak_dbfs,mean_dbfs,tier,confidence,clip_id), MEASUREMENTS_COLUMNS, QUIET_GAPS_COLUMNS, SLEEP_STAGE_COLUMNS, GARMIN_COLUMNS in `tools/export_night_csv.py`; None cells empty; flattened captureStats keys appended to the session CSV (malformed payload kept as capture_stats_raw).
- **Files written**: backup ~/sleep-local/backups/\<stamp\>/{sleepanalysis.db, -wal, -shm} (SLEEP_BACKUP_ROOT overrides the root); morning report NIGHT_ROOT/\<stamp\>/ with events_\<sid\>.csv, session_\<sid\>.csv, measurements_\<sid\>.csv, quiet_gaps_\<sid\>.csv, sleep_stage_\<sid\>.csv, garmin_sleep_window_\<sid\>.csv, clips/ + clips/manifest_\<sid\>.json, sleepanalysis.db, session_\<sid\>.log, night_trends.txt, chart_*.png.

## N.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| `PYT-01` | backup_db.sh snapshots db + wal + shm into $SLEEP_BACKUP_ROOT/\<timestamp\>, exits 0 with a skip note when no device answers, exits 1 when the main db is empty or `PRAGMA quick_check` != ok or the sleep_session count is 0, and keeps the newest 30 snapshots | `tools/backup_db.sh` | — | `unverified` |
| `PYT-02` | With a serial argument, adb is invoked as `adb -s <serial>` via an array whose adb literal survives replacement and whose trailing literal stays outside the quoted array expansion | `tools/backup_db.sh` | — | `unverified` |
| `PYT-03` | The previous-night pick prefers sessions with >= 4 h in bed (14400000 ms), then latest startedAt, then highest id, over COMPLETED/RECOVERED sessions strictly earlier than the current one | `tools/morning_report.sh`; `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `PreviousNight.pick` | `test_morning_report_sql.test_latest_full_night_wins_over_equal_length_older`, `test_morning_report_sql.test_short_evening_session_never_becomes_the_anchor`, `test_morning_report_sql.test_interrupted_and_open_sessions_are_ineligible`, `test_morning_report_sql.test_recovered_nights_are_eligible` | `tested` |
| `PYT-04` | The NIGHT_VALIDITY_* env vars are exported only when captureStats.windows_processed is non-NULL; window seconds come from capture_started_at when < endedAt (integer-division truncation), thermal events default 0, skipped hops = sum of thermal_skipped_hops $[2] | `tools/morning_report.sh` | `test_morning_report_sql.test_full_capture_stats_map_to_the_four_validity_values`, `test_morning_report_sql.test_null_or_keyless_capture_stats_leave_the_vars_unset`, `test_morning_report_sql.test_stats_without_thermal_spans_still_get_zero_hops` (the tests extract the SQL live from the script via `test_morning_report_sql.script_validity_sql`) | `tested` |
| `PYT-05` | Export output paths inside the repository are refused before any adb traffic, with exit 1 | `tools/export_night_csv.py` · `resolve_out_dir`, `main` | `test_export_night_csv.test_main_refuses_repo_out_before_touching_adb`, `test_export_night_csv.test_resolve_out_dir_refuses_repo_paths` | `tested` |
| `PYT-06` | One export writes the five fixed-header per-session CSVs; the sleep-stage CSV emits source as GARMIN on pre-v14 schemas without the column; unknown session → exit 1 | `tools/export_night_csv.py` · `export_csvs`, `fetch_sleep_stages` | `test_export_night_csv.test_export_csv_contents`, `test_export_night_csv.test_sleep_stage_v14_source_column_passed_through`, `test_export_night_csv.test_export_csvs_unknown_session` | `tested` |
| `PYT-07` | The Garmin sleep-window CSV is keyed by the session's local wake date (endedAt, open sessions startedAt + 12 h) in the session time zone and omitted with a note when no usable payload exists | `tools/export_night_csv.py` · `garmin_wake_date`, `fetch_garmin_payload` | `test_export_night_csv.test_garmin_payload_selected_by_wake_date`, `test_export_night_csv.test_garmin_wake_date_open_session_falls_forward_12h`, `test_export_night_csv.test_export_without_vendor_payload_omits_garmin_csv` | `tested` |
| `PYT-08` | Garmin JSON parsing mirrors the app's bounded search (depth 5, GMT keys with Local fallback, booleans are not numbers, degenerate windows rejected) | `tools/export_night_csv.py` · `find_number`, `parse_garmin_sleep_row` | `test_export_night_csv.test_parse_garmin_sleep_row_tolerates_shapes_and_rejects_degenerate` | `tested` |
| `PYT-09` | --keep-db merges the WAL into the main file (`PRAGMA wal_checkpoint(TRUNCATE)`) and copies the DB next to the CSVs | `tools/export_night_csv.py` · `main` | — | `unverified` |
| `PYT-10` | The fixture manifest has exactly the shape both sides agree on (see N.2.3), with floor_dbfs as the median event noise floor or null | `tools/export_fixtures.py` · `write_manifest`, `build_entry`, `clip_name` | `test_export_fixtures.test_write_manifest_shape`, `test_export_fixtures.test_write_manifest_floors_median`, `test_export_fixtures.test_write_manifest_entries_without_features`; consumer `app/src/test/java/com/sleepanalysis/monitoring/eval/FixtureEvalRunTest.kt` | `tested` |
| `PYT-11` | A DB pull copies the WAL mandatorily, retries 3 times, and rejects copies failing quick_check (tolerating exactly the benign audio_clip.appliedGain false positive), copies with a genuine NULL appliedGain row, and copies with zero sleep_session rows | `tools/export_fixtures.py` · `pull_db`, `_validate_db`, `BENIGN_QUICK_CHECK` | `test_export_fixtures.test_validate_db_accepts_alter_added_column_false_positive`, `test_export_fixtures.test_validate_db_rejects_genuine_applied_gain_nulls`, `test_export_fixtures.test_validate_db_still_requires_session_rows` | `tested` |
| `PYT-12` | Each clip pull verifies the on-device byte count (run-as wc -c, count first token) and the RIFF magic; a mismatch or non-RIFF result deletes the file and raises (caller records the clip as failed) | `tools/export_fixtures.py` · `pull_clip`, `remote_size` | `test_export_fixtures.test_pull_clip_deletes_and_fails_on_size_mismatch`, `test_export_fixtures.test_pull_clip_validates_riff_and_cleans_up`, `test_export_fixtures.test_remote_size_reads_the_count_in_the_app_sandbox` | `tested` |
| `PYT-13` | Battery drain is computed only over charging=false stretches (>= 2 readings); a plugged-in night is never reported as 0% drain; partially-plugged nights report per-stretch rates; the longest stretch is judged against the 25%/8h device-drain tripwire (overridable via --gate) | `tools/night_battery.py` · `summarize`, `format_report`, `GATE_PCT_PER_8H` | `test_night_battery.test_plugged_in_all_night_is_not_zero_drain`, `test_night_battery.test_mixed_night_is_per_stretch_not_summed`, `test_night_battery.test_tripwire_flags_over`, `test_night_battery.test_gate_threshold_overridable` | `tested` |
| `PYT-14` | Battery readings parse batt=NN% charging=bool with the placeholder form batt=? charging=? (no % after ?); unreadable placeholders are counted, not crashed; backwards timestamps are flagged; drain math uses time-sorted readings | `tools/night_battery.py` · `parse_log`, `summarize`; app writer `app/src/main/java/com/sleepanalysis/monitoring/service/BatteryStatus.kt` · `format` | `test_night_battery.test_placeholder_readings_are_counted_not_crashed`, `test_night_battery.test_backwards_timestamp_is_flagged`, `test_night_battery.test_readings_sorted_by_time_regardless_of_log_order` | `tested` |
| `PYT-15` | Loudest ranking sorts by peak dBFS descending and clip export goes through the verified shared pull (exec-out + size check + RIFF), skipping failures with a warning | `tools/night_loudest.py` · `rank_loudest`, `export_clips` | `test_night_loudest.test_rank_loudest_orders_by_peak_loudest_first`, `test_night_loudest.test_export_clips_pulls_via_exec_out_with_size_check`, `test_night_loudest.test_export_clips_skips_size_mismatched_clip`, `test_night_loudest.test_export_clips_skips_non_riff_clip` | `tested` |
| `PYT-16` | Trends gate on data volume: Spearman is None under 3 pairs or zero variance; the report needs >= 5 usable nights; verdict lines require abs(rho) >= 0.6 over >= 7 nights; an N < 10 caveat is attached otherwise | `tools/night_trends.py` · `spearman`, `format_correlations`, `format_report` | `test_night_trends.test_spearman_two_pairs_is_none`, `test_night_trends.test_report_gates_on_min_nights`, `test_night_trends.test_verdict_lines_when_strong_and_enough_nights`, `test_night_trends.test_weak_association_gets_summary_line_not_verdicts` | `tested` |
| `PYT-17` | Label-aware snore counting: a user label overrides the detector type (only SNORE counts); no/empty label keeps only detector-SNORE events — mirrored identically in two tools | `analysis/night_aggregates.py` · `events_counting_as_snore`; `tools/night_trends.py` · `counts_as_snore` | `test_night_aggregates.test_non_snore_labels_exclude_detector_snores`, `test_night_aggregates.test_none_label_behaves_like_empty_cell`, `test_night_trends.test_counts_as_snore_replicates_snoremath` | `tested` |
| `PYT-18` | Night validity grading mirrors the app: no capture stats → stats_missing grade D; 0 windows in a > 30 s window → capture_failed D; shortfall > 40% CRITICAL / > 10% WARNING; 0–1 warnings A, 2–3 B, 4+ C, any critical D; Garmin warnings pass through verbatim | `analysis/night_validity.py` · `assess`, `capture_from_env` | `test_night_validity.test_clean_night_grades_a`, `test_night_validity.test_missing_capture_stats_grade_d`, `test_night_validity.test_zero_windows_grade_d`, `test_night_validity.test_twenty_percent_shortfall`, `test_night_validity.test_four_warnings_grade_c`, `test_night_validity.test_implausible_hr_dropped_passes_through_as_warning` | `tested` |
| `PYT-19` | The night report is configured entirely through environment variables with no session identity baked in, and reads exported CSVs as untrusted input under `python -I` | `analysis/night_report.py` · `main` | — | `unverified` |
| `PYT-20` | CI runs `uv run ruff check .` then `uv run pytest` (testpaths tools + analysis) on every PR and push to main, with the uv cache enabled | `.github/workflows/ci.yml` (python job) | — | `unverified` |

## N.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| GATE_PCT_PER_8H | 25.0 %/8h | `tools/night_battery.py` · `GATE_PCT_PER_8H` | Device-drain tripwire verdict (NOT the app's <=10%/8h attributed-usage gate); --gate overrides |
| HEARTBEAT_INTERVAL_MS | 15 min | `app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt` · `HEARTBEAT_INTERVAL_MS` | Cadence of the session-log battery readings night_battery.py consumes |
| NIGHT_LIKE_MIN_IN_BED_MS | 14400000 (4 h) | `app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt` · `NIGHT_LIKE_MIN_IN_BED_MS` | Mirrored as `>= 14400000` in morning_report.sh's previous-night SQL |
| OPEN_SESSION_WAKE_OFFSET_MS | 12 h | `tools/export_night_csv.py` · `OPEN_SESSION_WAKE_OFFSET_MS` | Wake-date anchor for open sessions in the Garmin payload lookup |
| MAX_SEARCH_DEPTH | 5 | `tools/export_night_csv.py` · `MAX_SEARCH_DEPTH` | Bounded Garmin JSON search, mirrors GarminJsonParsers |
| MIN_NIGHTS / MIN_PAIRS / SMALL_N | 5 / 3 / 10 | `tools/night_trends.py` | Report gate / Spearman floor / small-N caveat threshold |
| VERDICT_ABS_RHO / VERDICT_MIN_N | 0.6 / 7 | `tools/night_trends.py` | Thresholds for a metric to earn a verdict line |
| CAPTURE_FAILED_MIN_WINDOW_S | 30 s | `analysis/night_validity.py` · `CAPTURE_FAILED_MIN_WINDOW_S` | 0 processed windows beyond this → capture_failed CRITICAL |
| SHORTFALL_WARN / SHORTFALL_CRITICAL | 0.10 / 0.40 | `analysis/night_validity.py` | Processed-vs-expected window shortfall severities |
| UNEXPLAINED_GAP_WARN_MIN / _CRITICAL_MIN | 15 / 60 min | `analysis/night_validity.py` | Uncaptured-gap severities |
| CLIPPED_WARN / CLIPPED_CRITICAL | 0.10 / 0.30 | `analysis/night_validity.py` | Clipped-clip rate severities |
| AMBIENT_Z_TRIGGER | 2.5 | `analysis/night_validity.py` | Relative ambient z-score warning trigger |
| LOW_SNORE_SHARE | 0.01 | `analysis/night_validity.py` | low_snore_signal INFO floor |
| HOP_MS | 500 | `analysis/night_validity.py` | Expected windows = window_seconds * 2 |
| VERSION | night-validity-v1 | `analysis/night_validity.py` · `VERSION` | Stored in the validity result, mirrored from the app |
| retention | newest 30 snapshots | `tools/backup_db.sh` (tail -n +31) | Backup pruning |
| retry / backoff | 3 attempts, 2 s sleep | `tools/export_fixtures.py` · `pull_db` | DB pull resilience against USB flaps |
| sample_rate / clip_ms | 16000 / 8000 | `tools/export_fixtures.py` · `write_manifest` | Fixture replay facts (informational for the replay engine) |
| TZ | Europe/Zurich (hardcoded) | `analysis/night_report.py` · `TZ` | All report/chart local times |
| DETECTOR_VERSION | energy-heuristic-v3-peakgate | `app/src/main/java/com/sleepanalysis/monitoring/eval/FixtureEvaluator.kt` · `DETECTOR_VERSION` | Detector identity in eval reports (manual sync with SessionRepository.MODEL_VERSION) |

## N.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| No device attached (backup) | Exit 0, "no device attached — skipped" | `tools/backup_db.sh` |
| Main db file missing or empty after pull | Exit 1 with debug-build/locked-device hint | `tools/backup_db.sh` |
| quick_check not ok or 0 sessions in the backup copy | Exit 1 "pulled database failed validation" | `tools/backup_db.sh` (embedded python3 -I) |
| Empty WAL copy during pull_db | Whole pull attempt fails and retries (WAL is not optional under Room WAL mode) | `tools/export_fixtures.py` · `pull_db` |
| quick_check flags `NULL value in audio_clip.appliedGain` | Accepted as benign (SQLite >= 3.44 vs pre-v11 rows), but only after a typeof scan confirms zero real NULLs; any second problem fails the pull | `tools/export_fixtures.py` · `_validate_db`, `BENIGN_QUICK_CHECK` |
| Clip transfer mangled (size mismatch) or non-RIFF | Partial file deleted, ValueError raised, clip skipped with a stderr warning, absent from the manifest | `tools/export_fixtures.py` · `pull_clip`; `tools/night_loudest.py` · `export_clips`; `tools/export_night_csv.py` · `export_clips` |
| `--out` inside the repository | Refused with exit 1 before any adb traffic | `tools/export_night_csv.py` · `resolve_out_dir`, `main` |
| Unknown session id requested | ValueError → exit 1 | `tools/export_night_csv.py` · `export_csvs`, `main`; `tools/night_loudest.py` · `main`; `tools/night_trends.py` · `main` (missing --db file) |
| Session with no clip-backed events (fixtures) | Exit 1 | `tools/export_fixtures.py` · `main` |
| Open (still-recording) session in morning_report | Exit 1 "no complete capture window — stop the night first" | `tools/morning_report.sh` step 4 |
| No sessions / no previous session in morning_report | Exit 1 with message | `tools/morning_report.sh` steps 2–3 |
| NULL or keyless captureStats | NIGHT_VALIDITY_* vars left unset → stats_missing grade D (deliberately stricter than the app; script says do not "fix") | `tools/morning_report.sh` step 4b; `analysis/night_validity.py` · `capture_from_env` |
| Malformed captureStats payload in export | Kept verbatim under capture_stats_raw — never silently dropped | `tools/export_night_csv.py` · `flatten_capture_stats` |
| Malformed featuresJson in fixture export | features null in the manifest entry | `tools/export_fixtures.py` · `build_entry` |
| Session log pull fails (absent, offline, run-as denied) | Battery section skipped, partial file removed, report continues | `tools/morning_report.sh` step 6 |
| night_trends.py fails | Trends skipped with a note; earlier artifacts intact | `tools/morning_report.sh` step 7 |
| Log without battery fields / garbage timestamps / clock jumps | "no battery telemetry", line dropped, backwards flag respectively | `tools/night_battery.py` · `parse_log`, `summarize`, `format_report` |
| Quiet night (no clips) | WAV cross-check skipped; empty clip column is valid | `analysis/night_report.py` · `main` |
| Manifest event id not present in the CSVs | Falls through to the legacy filename-scheme match | `analysis/night_report.py` · `main` |
| Fewer than 5 usable nights | Trends prints a count, not an analysis | `tools/night_trends.py` · `format_report` |

## N.6 Contract boundaries — what a refactor must not break

- **Persisted formats**: the CSV headers (SESSION_COLUMNS, EVENTS_COLUMNS, MEASUREMENTS_COLUMNS, QUIET_GAPS_COLUMNS, SLEEP_STAGE_COLUMNS, GARMIN_COLUMNS in `tools/export_night_csv.py` — `analysis/night_report.py` · `load_events` consumes the events header); the fixtures/manifest.json and clips/manifest_\<sid\>.json shapes (N.2.3); the captureStats JSON keys and schema_version 6 written by `app/src/main/java/com/sleepanalysis/monitoring/audio/NightStatsMerge.kt` · `captureStatsJson`; the session-log battery field batt=NN% charging=bool / batt=? charging=?; backup and morning-report directory layouts; the NIGHT_* and NIGHT_VALIDITY_* environment-variable names; output file names (events_\<sid\>.csv, chart_timeline.png, night_trends.txt, …).
- **Version strings stored with data**: captureStats.schema_version (6); night-validity-v1; DETECTOR_VERSION = energy-heuristic-v3-peakgate (kept in sync with SessionRepository.MODEL_VERSION); the benign-quick-check string is tied to the audio_clip.appliedGain REAL NOT NULL DEFAULT 2.5 migration (AppDatabase.kt ALTER TABLE).
- **Platform contracts**: all device access goes through `adb exec-out run-as com.sleepanalysis cat <path>` (raw stdout, no pty) for binaries — plain `adb shell cat` CRLF-mangles bytes (recorded as a fixed defect in docs/REVIEW_2026-11-04.md); paths databases/sleepanalysis.db(-wal/-shm), files/clips/…, files/logs/session_\<id\>.log; debug build required for run-as; on-device byte verification uses `run-as … wc -c <path>` with NO sh -c redirect (the on-device shell re-parses and loses quoting).
- **Cross-area interfaces**: the app side that scripts depend on — `app/src/main/java/com/sleepanalysis/data/db/entity/Entities.kt` tables/columns listed in N.2.3; `app/src/main/java/com/sleepanalysis/monitoring/audio/NightStatsMerge.kt` · `captureStatsJson`; `app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt` · `startHeartbeat` with `app/src/main/java/com/sleepanalysis/monitoring/service/BatteryStatus.kt` · `format`; `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `insertEvent` (clip/event back-link: both acoustic_event.clipId and audio_clip.eventId are written — the OR join in morning_report.sh and export_night_csv, and the single-direction join in export_fixtures, both rely on this); app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt (object PreviousNight, fun pick); `app/src/main/java/com/sleepanalysis/data/files/ClipStore.kt` · `writeClip` (on-device WAV naming); `app/src/test/java/com/sleepanalysis/monitoring/eval/FixtureEvalRunTest.kt` with `app/src/main/java/com/sleepanalysis/monitoring/eval/FixtureEvaluator.kt` consuming the fixture manifest; `analysis/night_validity.py` mirrors `app/src/main/java/com/sleepanalysis/domain/validity/NightValidity.kt` thresholds (change one side only together with the other); `tools/night_trends.py` reads the VENDOR_* measurement rows written by the Garmin import.
- **Safety-net tests**: `tools/test_morning_report_sql.py` (extracts the SQL live from the .sh — the strongest drift tripwire), `tools/test_export_fixtures.py`, `tools/test_export_night_csv.py`, `tools/test_night_battery.py`, `tools/test_night_trends.py`, `tools/test_night_loudest.py`, `tools/test_night_aggregates.py`, `analysis/test_night_validity.py`; Kotlin side `app/src/test/java/com/sleepanalysis/monitoring/eval/FixtureEvalRunTest.kt`.

## N.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| `tools/backup_db.sh` | `dead` | grep over README.md, docs/, tools/, .github/: zero references anywhere in the repository | `low` — but it is the only automated DB backup; document it (README "Python tooling") rather than delete | Owner-run utility, self-documented header |
| `tools/export_night_csv.py` · `detect_serial`; `tools/night_loudest.py` · `detect_serial` | `duplicate` | Three identical bodies; `tools/export_fixtures.py` · `detect_serial` is already the shared source (its pull_db/pull_clip are imported by both) | `low` — pure function, tested indirectly only via the fixtures copy | night_loudest already imports from export_fixtures but re-declares this one function |
| `tools/night_loudest.py` · `latest_session_id`; `tools/export_night_csv.py` · `latest_session_id`; inline SELECT id … ORDER BY startedAt DESC LIMIT 1 in `tools/export_fixtures.py` · `main` and morning_report.sh step 2 | `duplicate` | The same one-line query in four places | `low` | Exporter adds a no-sessions error the others lack |
| Snore union duration: `analysis/night_report.py` · `union_time` vs `tools/night_trends.py` · `union_duration_ms` | `duplicate` | Two overlap-merge implementations; the trends one claims exact SnoreMath.unionDurationMs parity (degenerate-interval clamp), the report one is simpler | `medium` — outputs must stay identical; unify on the SnoreMath-parity port after comparing behaviour | `analysis/night_report.py` · `hourly` re-implements the merge inline as well |
| `tools/night_trends.py` · `counts_as_snore` vs `analysis/night_aggregates.py` · `events_counting_as_snore` | `duplicate` | Same rule implemented twice (dict-based vs param-based); trends reads NULL labels from the DB, aggregates reads empty cells from CSV | `low` — unify behind night_aggregates (importable from tools/ via sys.path) | Semantic parity is pinned by tests on both sides |
| `analysis/night_report.py` · `main` legacy clip-filename fallback regex evt_…_(\d+)_\d+\.wav | `legacy-compat` | Matches only pre-manifest exports named like the on-device scheme (`app/src/main/java/com/sleepanalysis/data/files/ClipStore.kt` · `writeClip` writes evt_\<type\>_\<startMs\>_\<n\>.wav); current exports carry manifest_\<sid\>.json and rename clips to evt_\<start_ms\>.wav | `low` — keep while old export dirs exist on the laptop | Manifest join is primary; fallback is dead code for new exports |
| `tools/export_fixtures.py` · `QUERY` | `over-general` | Joins only `c.eventId = e.id`, while export_night_csv/morning_report use the belt-and-braces `c.eventId = e.id OR c.id = e.clipId`; valid only because `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `insertEvent` back-fills setEventId | `low` — but note: if the app ever drops the audio_clip.eventId back-fill, this exporter silently finds no clips on new data | The two join styles should be one shared helper |
| matplotlib + numpy imports without declaration | `dependency` | `analysis/night_report.py` imports both; `pyproject.toml` declares dependencies = [] and they are supplied only via `uv run --with matplotlib --with numpy` at the two call sites (`tools/morning_report.sh` step 5 and the `analysis/night_report.py` module docstring) | `low` — running analysis/night_report.py bare under uv fails with ImportError | Either declare an analysis extra or keep the --with convention documented |
| `tools/export_night_csv.py` · `fetch_session` vs `tools/night_loudest.py` · `fetch_session` | `duplicate` | Same table, different column subsets and shapes | `low` | Different consumers; merging is optional |

Duplication across areas: the validity thresholds, the SnoreMath counting/union rules and the capture_started_at window rule each exist in the Kotlin app AND the Python tooling by design (declared mirrors with tests on both sides — see the `analysis/night_validity.py` module docstring and `tools/night_trends.py` · `union_duration_ms`). These are consolidation candidates only as a pair: a refactor must change both or break a mirror test. morning_report.sh's previous-night SQL is additionally pinned against the script text itself by `test_morning_report_sql.script_validity_sql`.

## N.8 Open questions for the owner

1. `tools/backup_db.sh` is referenced nowhere in the repository (no README, docs, CI or script caller). Keep-and-document or delete? (Its retention and validation behaviour are verified only by reading — it was not run: it uses adb.) UNVERIFIED — no device run of any adb path was possible.
2. CI has no Python version pin (the python job relies on uv's default resolving requires-python = ">=3.12"). Pin explicitly or accept drift? UNVERIFIED — actual CI-run behaviour not observed.
3. `analysis/night_report.py` hardcodes the time zone to Europe/Zurich while `tools/export_night_csv.py` uses the session's stored timeZone; intentional for this single-user deployment?
4. PYT-09 (--keep-db WAL merge), PYT-19 and PYT-20 have no test and no device evidence: unverified rows above. The morning_report.sh orchestration (steps 1, 3–7) and backup_db.sh have no tests at all — only their embedded SQL is pinned; is shell-level testing wanted?
5. `tools/test_export_fixtures.py` · `test_clip_name_matches_on_device_scheme` only asserts the fixture-relative shape session_3/evt_45000.wav, while `app/src/main/java/com/sleepanalysis/data/files/ClipStore.kt` · `writeClip` writes evt_\<type\>_\<startMs\>_\<n\>.wav on-device and the export_fixtures.py docstring claims "same name scheme the app writes on-device". The manifest join makes the claim harmless today, but the docstring and test name are misleading (doc-drift). UNVERIFIED — whether any historical exporter actually relied on name equality.
6. docs/NIGHT_REPORT.md is cited by morning_report.sh's header but contains no script invocations (grep: zero tool names); docs/STATUS.md mentions night_loudest.py only in a historical fix note. Confirm the intended source of truth for run instructions. UNVERIFIED — full docs not read (grep-only per budget).
