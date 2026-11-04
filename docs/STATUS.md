# Project Status — SleepAnalysis

**As of:** 2026-11-01 (second consolidation) · Phase 1 implemented + Phase 3 first slices + P0-hardening pass · single developer, no device dogfooding yet.

Evidence base for this document: fresh build `BUILD SUCCESSFUL`, **92 unit tests / 0 failures across 13 classes** (2026-11-01, recording-controls pass + review fixes), debug APK ~29.9 MB (2026-11-01 16:51), Room schema history 1→6 with exported JSON and five real migrations.

Related: [Next steps](NEXT_STEPS.md) · [Roadmap](ROADMAP.md) · [MVP scope](MVP_SCOPE.md) (with status amendment) · [README known limitations](../README.md)

---

## 1. Implemented and verified (code + unit tests)

| Area | What exists | Verified by |
|---|---|---|
| Overnight capture | `MonitorService` (FGS `microphone`, persistent notification + chronometer + Stop action), `AudioEngine` (AudioRecord 16 kHz mono, 1 s windows / 0.5 s hop, ring buffer, clip flush ≤ 8 s, top-50/night cap), thermal throttling at MODERATE+, pre-flight checks (storage ≥ 500 MB, permission), partial wake lock, START_STICKY resume, heartbeat watchdog | Compile + code review; **not yet exercised on a device** |
| Interrupted-night handling | `MonitorState.Interrupted` + Home banner showing saved-event count; finalization only on explicit user confirmation (`finalizeOpenAsRecovered`), never silent auto-recovery; guard prevents two open sessions; recovered sessions bound `ended_at` by the last saved event (+1 min) so time-in-bed isn't inflated by the dead gap | Compiled; flows untested on device |
| Detection | `SnoreDetector` interface + `EnergySnoreDetector` v0 (noise-floor-relative SNR + low-band ratio), `EventDetector` shaping (hysteresis 2-in/4-out, 2 s min duration, snore precedence), per-event `features_json` (schema v5) persisting the detector's decision inputs | 16 tests (event shaping, detector, thresholds) |
| Session health & observability | `AudioEngine.NightStats` → `capture_stats` JSON written at finalize (windows processed/skipped, ambient dBFS median/P95, clip count, thermal events; null for recovered sessions), `CaptureStats` tolerant parser, report "Session health" section + derived health warnings (capture gaps, thermal throttling), per-session append-only file logs (`SessionLogger`, no-throw, viewable via `SessionLogScreen`, purged with session retention) | 4 CaptureStats tests + 5 SessionLogger tests; health warnings logic in assembler tests |
| Storage | Room schema v6: sessions (incl. `capture_stats`, `morning_notes`), acoustic events (+ `user_label`, `labeled_at`, `features_json`), audio clips, measurements (full provenance), clinical baseline (immutable: insert-only DAO + SQL triggers + purge-only bypass), `raw_payload` (Garmin JSON), `user_factor`; migrations 1→2→3→4→5→6 all real | Schema exports; migration SQL reviewed; trigger logic in `AppDatabase` |
| Claim discipline | `MeasurementType` carries tier; `MeasurementRecorder` is the only write path and rejects Tier-3 types; `ClaimPolicy` gates display | 9 tests incl. "Tier-3 set is exactly M14–M17" and recorder-refusal |
| Morning report | Assembler (time in bed, snore score/time/share/tiers, hourly buckets, integrity notes, session health, health warnings), Compose UI: headline grid, disclaimer card, interactive timeline (tap→select), hourly bars, clip playback (MediaPlayer), session notes, **morning notes editor** (schema v6; blank clears, user context only — never a measurement) | 12 assembler tests; UI compiled, untested on device |
| Event labeling | Five-way `EventUserLabel` (Snore/Cough/Speech/Movement/Other-not-snore) + `labeledAt`, chips in report, labeled indicator in event list, report reload after labeling | 3 tests (exact set, round-trip, forbidden-term guard) |
| Garmin Personal API (Phase 3 slice) | Keystore-encrypted key (`GarminKeyStore`, AES-GCM), token exchange + 4 endpoints (per-endpoint independent failure, 401 re-auth), tolerant parsers, `raw_payload` verbatim storage, `GARMIN`/`DEVICE_REPORTED` provenance, report "Garmin estimate" card + per-night import, Settings screen with statuses | 9 parser tests + provenance rows in assembler tests; **live API contract unverified** (`?date=` param assumption) |
| Daily factor log (Phase 3 slice) | `user_factor` (7 keys: alcohol w/ units, late meal, caffeine after 14:00, exercise, medication/device, stress 1–5, notes), replace-on-save, Compose log screen, report "Daily factors (self-logged)" card | 3 repo tests + assembler tests |
| Clinical baseline | Structured entry dialog (date, lab, TST, efficiency, arousal index, N3 %, AHI, notes, source doc), immutable, cited in report header context | Compiled; immutability triggers via SQL (not unit-tested — device/DB test gap) |
| Retention & deletion | Daily WorkManager job reading retention prefs (defaults: clips 7 d, sessions 90 d; session logs follow session retention; orphan sweep), per-night delete, delete-all (incl. baseline, with bypass path) | Logic reviewed; retention settings have prefs plumbing but no UI yet; worker untested on device |
| Onboarding & privacy UI | Full disclaimer gate (must accept), mic+notification permission flow, mic-active indicator, battery-optimization hint, placement guidance | Compiled; flows untested on device |
| Detection debug knob | Settings toggle: relax event opening 2→1 consecutive windows for quiet-night testing; takes effect next recording; affected sessions marked with a `+dbg1` model-version suffix | Compiled; no automated test |
| Recording controls | Master "Recording enabled" switch (gates every new start — manual and scheduled — never force-stops an active night) + optional scheduled nightly recording (off by default): exact-alarm bedtime/end scheduling with a ~10-minute inexact fallback, high-priority bedtime notification whose tap/Start action starts the service (the one-tap user-interaction exemption for while-in-use mic access on Android 11+), auto-stop at the end time, skip-tonight marker (naturally expires), re-arming on boot/timezone-clock changes/settings changes/app open, Settings card (start/end TimePicker, next-start note, exact-alarm + battery notes) and Home schedule row | 23 tests (ScheduleTimes, SchedulePolicy); alarm/notification/receiver runtime unverified on device |

## 2. Implemented but UNVERIFIED on a real device

The entire runtime behavior: overnight soak (8 h stability), battery (gate: ≤ 10 %/8 h), thermal path, notification lifecycle, Doze behavior, clip audio quality, report rendering with real data, interrupted-night banner + finalize flow after force-stop, session logs on device, Garmin live API (incl. the `?date=` parameter assumption in GARMIN_INTEGRATION.md §4). **No dogfood night has happened yet.**

## 3. Documented but NOT implemented (deliberate gaps — do not assume they exist)

| Item | Where documented | When it matters |
|---|---|---|
| `model_registry` table, TFLite/ONNX runtime, log-mel `FeatureExtractor` | ARCHITECTURE §4/§6, DATA_MODEL §3.8 (flagged as planned) | Phase 2 model v1 |
| Report snapshot table (reports are computed live from events) | DATA_MODEL §3.7 (flagged as planned) | Phase 4 reprocessing/trends |
| CI pipeline + no-extra-permissions lint + forbidden-terms ("AHI"/"apnea") lint for strings | MVP_SCOPE F10/M0, ARCHITECTURE §6.6 (marked "specified, not yet built"), PRIVACY §5.6 | Before any release beyond personal use |
| Retention-settings UI (prefs plumbing exists: `UserPreferences.setRetention`) | README known limitations | P1 |
| Movement/accelerometer, sleep/wake estimate (M5/M6) | ROADMAP Phase 2 | Phase 2 |
| Experimental breathing indicators + toggle (M7/M8) | ROADMAP Phase 2, MEASUREMENT_RELIABILITY §6 | Phase 2 |
| Health Connect, FIT/CSV Garmin fallback, export (CSV/JSON/PDF), clinical comparison view | ROADMAP Phase 3 | Phase 3 remainder |
| Trends, personal baseline bands, associations, insight rules | ROADMAP Phase 4 | Phase 4 |
| Smart alarm, anti-snore cues, sonar | ROADMAP Phases 3–5 | Later |

## 4. Sequencing deviation (accepted, visible in ROADMAP)

Phase 3 slices (Garmin import, factor log) landed **before** Phase 2 (acoustic intelligence, movement). Consequence: the report already has three data-source sections plus session-health observability, but detection is still the v0 heuristic and there is no sleep/wake estimate. This is coherent — the claim policy holds regardless of data sources — but it means Phase 2 is now the largest open block.

## 5. Consolidations

### 2026-11-01 morning (initial consolidation)

1. README claimed "Schema v1 only" → corrected.
2. `PRIVACY_AND_REGULATORY.md` §3 permission table said "notably no INTERNET" → corrected to show the Phase 3 amendment.
3. ROADMAP Phase 3 didn't mention the shipped Garmin Personal API slice → corrected ("shipped early").
4. DATA_MODEL documented `model_registry` as if implemented → re-labeled "documented, not yet implemented"; `raw_payload` was never in DATA_MODEL → added §3.9.
5. MVP_SCOPE still specified TFLite/YAMNet for F4 and no-INTERNET for F10/A8 → status amendment box added at top (the spec remains the historical Phase 1 contract; STATUS.md is authoritative).
6. ARCHITECTURE §4 describes a `FeatureExtractor` component and log-mel pipeline; the energy detector computes features inline (`AudioEngine`/`EventFeatures`: RMS + low-band ratio — no mel) → accepted; the abstraction arrives with the TFLite model.

### 2026-11-01 evening (after the P0-hardening pass: interrupted-night UX, session health, morning notes, session logs)

1. Evidence refreshed: 54 tests/9 classes → **69 tests/11 classes**; schema v4 → **v6** (features_json, morning notes).
2. README Phase 1 section and known limitations updated for the hardening pass (interrupted-night banner, session health + warnings, per-session logs, morning notes).
3. DATA_MODEL §5.1 `capture_stats` example rewritten to the implemented shape (ambient percentiles, `windows_skipped`, `clip_count`; the earlier `interruptions`/`inference_p50_ms` fields were design placeholders never built).
4. DATA_MODEL §4 corrected: immutability triggers guard the **`clinical_baseline` table** (`trg_clinical_baseline_no_update`/`_no_delete`, recreated on every open), not `measurement` rows; measurement-level clinical protection is enforced by `MeasurementRecorder`/`ClaimPolicy`, not SQL.
5. DATA_MODEL §3.2 annotated to the implemented table: `model_version` (no `model_id`/registry FK yet), no `baseline_id` column (clinical values live in `clinical_baseline`), `source_type` includes `GARMIN`; §3.7 report snapshot additionally flagged "documented, not yet implemented".
6. ARCHITECTURE updated: package map reflects the actual tree (`data/logs`, `data/garmin`, `data/secure`, `data/prefs`; `features`/`ml` marked planned), `SessionLogger` in the component map, Interrupted state in the recovery flow, §5/§7 describe the live-computed report (snapshot deferred), §6.6 CI checks marked "specified, not yet built", factor-log UI marked shipped.
7. PRIVACY §2/§3: session log files added to the data inventory; permission table completed (`WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
8. NEXT_STEPS: P1 gained the retention-settings UI item; night-1 checklist mentions the debug enter-windows toggle as the quiet-night fallback.
