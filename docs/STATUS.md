# Project Status — SleepAnalysis

**As of:** 2026-11-04 (fourth revision: review-fix wave + docs-consistency pass) · Phase 1 implemented + Phase 3 first slices + hardening passes · single developer · **3 dogfood nights run** (2026-11-01 → 04, Zurich).

Evidence base for this document: fresh build `BUILD SUCCESSFUL`, **318 unit tests / 0 failures across 40 classes** (2026-11-04, after the review-fix wave), **17 Python tool tests passing** (`uv run pytest`, now uv-managed), Room schema history 1→8 with exported JSON and seven real migrations (v8 declaration-only). For the earlier audit and what it found/fixed, see [REVIEW_2026-11-04](REVIEW_2026-11-04.md); for the dogfood record see §2.

Related: [Next steps](NEXT_STEPS.md) · [Roadmap](ROADMAP.md) · [MVP scope](MVP_SCOPE.md) (with status amendment) · [README known limitations](../README.md)

---

## 1. Implemented and verified (code + unit tests)

| Area | What exists | Verified by |
|---|---|---|
| Overnight capture | `MonitorService` (FGS `microphone`, persistent notification + chronometer + Stop action), `AudioEngine` (AudioRecord 16 kHz mono, 1 s windows / 0.5 s hop, ring buffer, clip flush ≤ 8 s, top-150/night cap + loudness reserve), thermal throttling at MODERATE+, pre-flight checks (storage ≥ 500 MB, permission), partial wake lock, sticky-restart guard (no silent resume — `StartIntentPolicy`), heartbeat watchdog | Compile + unit tests + **3 real dogfood nights** (§2) |
| Interrupted-night handling | `MonitorState.Interrupted` + Home banner showing saved-event count; finalization only on explicit user confirmation (`finalizeOpenAsRecovered`), never silent auto-recovery; guard prevents two open sessions; recovered sessions bound `ended_at` by the later of the last saved event or the last heartbeat (+1 min) so time-in-bed isn't inflated by the dead gap | Finalize logic unit-tested; the RECOVERED-after-real-death path is **not yet exercised** (all three dogfood nights ran to completion) |
| Detection | `SnoreDetector` interface + `energy-heuristic-v1-spectral` (noise-floor-relative SNR + low-band ratio, plus spectral flatness/harmonicity gates from `monitoring/features/` and a breath-cycle rhythm gate; null-feature inputs fall back to v0 behavior), `EventDetector` shaping (hysteresis 2-in/4-out windows, 2 s min duration, snore precedence), per-event `features_json` (schema v5) persisting the detector's decision inputs; thresholds tuned offline via `FixtureEvaluator` on labeled dogfood clips | Detector, shaping and threshold tests |
| Session health & observability | `AudioEngine.NightStats` → `capture_stats` JSON written at finalize **and at every 15-min heartbeat** (windows processed/skipped, ambient dBFS median/P95, clip count, thermal events, `saved_at_ms`; recovered sessions keep heartbeat-saved progress), `CaptureStats` tolerant parser, snore share/score denominator = captured analysis time (docs/DATA_MODEL.md §5.1), report "Session health" section + derived health warnings (capture gaps, thermal throttling), per-session append-only file logs (`SessionLogger`, no-throw, viewable via `SessionLogScreen`, purged with session retention) | CaptureStats + SessionLogger tests; health warnings logic in assembler tests |
| Storage | Room schema v8: sessions (incl. `capture_stats`, `morning_notes`, `resumedAfterInterruption` with declared default), acoustic events (+ `user_label`, `labeled_at`, `features_json`), audio clips, measurements (full provenance), clinical baseline (immutable: insert-only DAO + SQL triggers + purge-only bypass), `raw_payload` (Garmin JSON), `user_factor`; migrations 1→2→…→8 all real | Schema exports; instrumented `MigrationTest` replays v1→latest on device (rows survive, triggers enforce); migration SQL reviewed |
| CI & policy gates | GitHub Actions (`.github/workflows/ci.yml`) on every PR: assemble + unit tests + Android lint + four Gradle gates (`checkPermissionAllowlist` vs `ci/permission-allowlist.txt`, `checkForbiddenTerms`, `checkRepoHygiene`, `checkNetworkHosts`) + ruff/pytest for the Python tooling | All gates negative-tested locally (a staged violation fails each one) |
| Claim discipline | `MeasurementType` carries tier; `MeasurementRecorder` is the only write path and rejects Tier-3 types; `ClaimPolicy` gates display | 9 tests incl. "Tier-3 set is exactly M14–M17" and recorder-refusal |
| Morning report | Assembler (time in bed, snore score/time/share/tiers, hourly buckets, integrity notes, session health, health warnings), Compose UI: headline grid, disclaimer card, interactive timeline (tap→select), hourly bars, clip playback (MediaPlayer), session notes, **morning notes editor** (schema v6; blank clears, user context only — never a measurement) | 12 assembler tests; UI compiled, untested on device |
| Event labeling | Five-way `EventUserLabel` (Snore/Cough/Speech/Movement/Other-not-snore) + `labeledAt`, chips in report, labeled indicator in event list, report reload after labeling | 3 tests (exact set, round-trip, forbidden-term guard) |
| Garmin Personal API (Phase 3 slice) | Keystore-encrypted key (`GarminKeyStore`, AES-GCM), token exchange + 5 endpoints (per-endpoint independent failure, 401 re-auth, bounded 429/503 backoff honoring Retry-After), tolerant parsers, `raw_payload` verbatim storage, `GARMIN`/`DEVICE_REPORTED` provenance, report "Garmin estimate" card + per-night wake-date import, Settings screen with statuses | 9 parser tests + provenance rows in assembler tests; **live API exercised on dogfood night 2** (found + fixed the HTTP 406 token-exchange bug); five-endpoint surface verified against real payloads |
| Daily factor log (Phase 3 slice) | `user_factor` (7 keys: alcohol w/ units, late meal, caffeine after 14:00, exercise, medication/device, stress 1–5, notes), replace-on-save, Compose log screen, report "Daily factors (self-logged)" card | 3 repo tests + assembler tests |
| Clinical baseline | Structured entry dialog (date, lab, TST, efficiency, arousal index, N3 %, AHI, notes, source doc), immutable, cited in report header context | Compiled; immutability triggers via SQL (not unit-tested — device/DB test gap) |
| Retention & deletion | Daily WorkManager job reading retention prefs (defaults: clips 7 d, sessions 90 d; session logs and raw Garmin payloads follow session retention; orphan sweep), per-night delete, delete-all (incl. baseline, with bypass path) | Logic reviewed; retention settings have prefs plumbing but no UI yet; worker untested on device |
| Onboarding & privacy UI | Full disclaimer gate (must accept), mic+notification permission flow, mic-active indicator, battery-optimization hint, placement guidance | Compiled; flows untested on device |
| Detection debug knob | Settings toggle: relax event opening 2→1 consecutive windows for quiet-night testing; takes effect next recording; affected sessions marked with a `+dbg1` model-version suffix | Compiled; no automated test |
| Recording controls | Master "Recording enabled" switch (gates every new start — manual and scheduled — never force-stops an active night) + optional scheduled nightly recording (off by default): exact-alarm bedtime/end scheduling with a ~10-minute inexact fallback, high-priority bedtime notification whose tap/Start action starts the service (the one-tap user-interaction exemption for while-in-use mic access on Android 11+), auto-stop at the end time, skip-tonight marker (naturally expires), re-arming on boot/timezone-clock changes/settings changes/app open, Settings card (start/end TimePicker, next-start note, exact-alarm + battery notes) and Home schedule row | 23 tests (ScheduleTimes, SchedulePolicy); alarm/notification/receiver runtime unverified on device |

## 2. Real-device record (dogfooding)

**Three consecutive dogfood nights ran — 2026-11-01 → 04, Zurich, all COMPLETED.** What they found; every fix is visible in the code it changed:

- **Night 1** — 1,047 near-silent "SNORE" events (rolling floor ≈ mic self-noise in a quiet room; low-band ratio saturated) and the entire 50-clip budget consumed by the first 42 minutes of bedtime noise. Fixes: relative level gate with a −60 dBFS hard floor, sub-gate SNORE downgrade to OTHER_NOISE at finalize, per-hour clip budget + 30-min settling window (`AudioEngine`, `EventDetector`, `DetectorConfig`).
- **Night 2** — first real Garmin import: token exchange returned HTTP 406 once a valid key passed auth (`Accept: application/json` on a bare-JWT endpoint; invalid keys 401 before content negotiation, which is why it never showed in testing). Fix: `Accept: */*` (`GarminClient`).
- **Night 3** — the night's loudest snore (peak −16.6 dBFS) missed its clip under the first-come-first-served budget. Fix: a 15-clip loudness reserve that bypasses the hourly/nightly caps for clearly-audible events (`DetectorConfig`).
- **Session 10** — the 2 s exit hysteresis shattered one long snore train into fragments. Fix: exit-timeout/merge-gap rework; the v1-spectral gates and thresholds were then tuned offline through `FixtureEvaluator` on the labeled dogfood clips.

**Still unverified on a device:** battery draw over a full night (the ≤ 10 %/8 h gate has never been measured), the thermal-throttle path (never triggered in three nights), Doze/exact-alarm behavior of the nightly schedule, the interrupted-night banner + RECOVERED finalize after a real process death (all three nights ran to completion), notification lifecycle edge cases, and report rendering with more history than three sessions.

## 3. Documented but NOT implemented (deliberate gaps — do not assume they exist)

| Item | Where documented | When it matters |
|---|---|---|
| `model_registry` table, TFLite/ONNX runtime, log-mel `FeatureExtractor` | ARCHITECTURE §4/§6, DATA_MODEL §3.8 (flagged as planned) | Phase 2 model v1 |
| Report snapshot table (reports are computed live from events) | DATA_MODEL §3.7 (flagged as planned) | Phase 4 reprocessing/trends |
| Retention-settings UI (prefs plumbing exists: `UserPreferences.setRetention`) | README known limitations | P1 |
| Movement/accelerometer, sleep/wake estimate (M5/M6) | ROADMAP Phase 2 | Phase 2 |
| Experimental breathing indicators + toggle (M7/M8) | ROADMAP Phase 2, MEASUREMENT_RELIABILITY §6 | Phase 2 |
| Health Connect, FIT/CSV Garmin fallback, export (CSV/JSON/PDF), clinical comparison view | ROADMAP Phase 3 | Phase 3 remainder |
| Trends, personal baseline bands, associations, insight rules | ROADMAP Phase 4 | Phase 4 |
| Smart alarm, anti-snore cues, sonar | ROADMAP Phases 3–5 | Later |

## 4. Sequencing deviation (accepted, visible in ROADMAP)

Phase 3 slices (Garmin import, factor log) landed **before** Phase 2 (acoustic intelligence, movement). Consequence: the report already has three data-source sections plus session-health observability, but detection is the v1-spectral heuristic (no learned model) and there is no sleep/wake estimate. This is coherent — the claim policy holds regardless of data sources — but it means Phase 2 is now the largest open block.

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

### 2026-11-04 (after the full-system audit + hardening pass — see [REVIEW_2026-11-04](REVIEW_2026-11-04.md) for the complete account)

1. Evidence refreshed: 92 tests/13 classes (already stale — real count was 237) → **287 tests/36 classes, 0 failures**; schema line corrected to **v7** (1→7, six real migrations) — the schema had moved to v7 before this pass without the docs following.
2. Full-system audit (4 parallel read-only passes: audio/detection, data, service/scheduling/UI, tooling/build) found and the hardening pass fixed: sticky-restart silent-recording (Android 11+ background mic restriction), `stopSelf`-before-`startForeground` crash paths, an `EventDetector` branch that silently dropped events, Garmin re-import duplication, RetentionWorker orphan-sweep vs in-flight clips, non-transactional multi-step writes, the `HomeViewModel` interrupted-state race, and ~20 smaller items. An independent review of the fix diff caught one P0 regression and five P1s in the first fix wave; all were fixed (details in REVIEW_2026-11-04 §3).
3. README: START_STICKY behavior rewritten (silent auto-resume no longer exists — interrupted notification + tap-to-resume instead); schema line 1→7; Garmin re-import semantics documented; `uv run pytest` added to the build section.
4. Tooling: `pyproject.toml` + `uv.lock` (reproducible Python env for `tools/`); `night_loudest.py` ported to the non-mangling exec-out transfer; `.gitignore` cache entries; env-var release signing scaffolding; repo initialized as a git repository (baseline staged, **not committed** — first commit deliberately left to the developer).

### 2026-11-04 late (review-fix wave + docs-consistency pass)

1. **Three dogfood nights recorded** (§2) — the first real-device evidence; every finding shipped as a fix, and the detector moved to `energy-heuristic-v1-spectral` with offline fixture tuning.
2. **Review-fix wave** (7 items, one commit each): Garmin night join re-anchored on the wake date (DATA_MODEL §5.3), Garmin import windows in the session timezone, `data_extraction_rules.xml` excluding all app data from cloud backup + device transfer, `showWhenLocked` removed from MainActivity, Garmin client endpoint-failure isolation + bounded Retry-After backoff + cancellation rethrow, snore share/score over captured time with unioned intervals + RECOVERED no-event handling, collision-safe clip names + transactional retention cleanup. Evidence: 318 tests / 40 classes, 0 failures.
3. **Lint gated:** the two existing Android lint errors (`MissingPermission`, `NewApi`) fixed; `lintDebug` (abortOnError) runs in CI.
4. **Docs matched to the code: detector v1-spectral described everywhere (+ §7.1 detector changelog in MEASUREMENT_RELIABILITY); SpO₂ tier contradiction resolved (M12 → Tier 1 levels-only) and a machine-readable tier table (§8) enforced by a doc-parsing test; permission inventory completed to the real 9 manifest permissions; "no INTERNET" statements replaced by the single-host Garmin allowlist; schema v6 → v7 in ARCHITECTURE; EventDetector hysteresis description corrected (window-count 2-in/4-out, not EMA/probability); silent-auto-resume sentence removed (START_STICKY does not resume capture); migration-test claims corrected (none exist — reviewed SQL only); README docs/ description updated after the personal PDFs/docx left the repo.
