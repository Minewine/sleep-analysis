# Next Steps — Prioritized

Ordered by leverage. P0 blocks everything else; each item lists why now and what "done" means.

Related: [STATUS.md](STATUS.md) (current state) · [Roadmap](ROADMAP.md) (phase plan) · [MVP_SCOPE.md](MVP_SCOPE.md) (quality gates) · [IDEAS_BACKLOG.md](IDEAS_BACKLOG.md) (sequenced improvement ideas)

---

## P0 — First overnight test on a real phone ← THE GATE

Everything else is blocked on evidence only real nights can provide: battery, 8 h stability, detection usefulness, recovery behavior, report quality. Code-side work before this would be guesswork. *(The code-side hardening this test relies on is already in place: interrupted-night banner with explicit finalize, session health + per-session logs, morning notes.)*

**Checklist (night 1 — keep it clean, no experiments):**

| # | Step | Why |
|---|---|---|
| 1 | Physical phone, **Android 11+**, working mic, ≥ 50 % charge | minSdk 30; emulator can't capture overnight |
| 2 | Install `app-debug.apk` (`adb install app/build/outputs/apk/debug/app-debug.apk` or sideload) | — |
| 3 | Accept the disclaimer; grant **Microphone** (+ Notifications) | gates the service |
| 4 | Follow the Home battery hint: **exclude from battery optimization** | #1 overnight-recording killer |
| 5 | Verify **≥ 500 MB free** storage (app enforces) | pre-flight gate |
| 6 | Placement: nightstand, **0.5–1 m from your head**, mic unobstructed (watch thick cases), **plugged in** | capture quality + power |
| 7 | Optional but recommended: fill the **Daily log** for the day | makes the report's factor card meaningful |
| 8 | Tap **Start night recording** → confirm the persistent notification + running timer; lock the phone and leave it | verifies FGS lifecycle |
| 9 | Morning: tap **Stop** in the app (or notification) → lands on the report | — |

**Morning checks (map to MVP_SCOPE gates):**

- Session status is **COMPLETED** (not RECOVERED / INTERRUPTED) — A2/A3.
- Time in bed ≈ reality; **play 2–3 snore clips** and label them (Snore / Other-not-snore) — first labeled data + detection signal.
- Glance at the report's **Session health** section: windows processed ≈ 2 × minutes in bed, no capture-gap warning; open **View session log** once to confirm logging works.
- If you snored but nothing was detected: enable **Settings → Debug: relax event opening** before night 2 (affected sessions are marked `dbg1` in their data).
- Android Settings → Battery: app usage **≤ 10 %** for the night — the headline quality gate; note the number.
- Phone not hot at wake-up (thermal path sanity).
- Any integrity note in the report (interruptions) → note the cause.
- Fill the Daily log honestly if not done the evening before.

**Night 2–3 (drills, not night 1):** force-stop the app mid-night (swipe away / `adb shell am force-stop com.sleepanalysis`) → next launch must show the **interrupted-night banner** (with the saved-event count); tapping *Finalize & view report* must produce a **RECOVERED** report covering up to the kill. Then one night *without* the battery-optimization exclusion to see the real difference. Battery %/charging state is recorded in every heartbeat and the stop log line (re-added 2026-11-07; sessions recorded on builds between 2026-11-04 and the 11-07 build may lack `batt=`), so the ≤ 10 %/8 h gate reads straight from the session log — or automatically via `tools/night_battery.py`, which `tools/morning_report.sh` now appends to every report.

**Exit criteria for P0:** 3 consecutive nights with COMPLETED (or drilled-RECOVERED) sessions, battery ≤ 10 %/night on at least one, ≥ 10 labeled events, zero data loss.

**Status 2026-11-14 (14 sessions in):** Validity grading verified live (night 16 grades A). **Battery gate measured and it changes shape:** night 16 (first fully-unplugged night) drained **25% in 7h17m — 27.5%/8h device drain** (`tools/night_battery.py` in `tools/morning_report.sh` step 6). **Gate semantics clarified:** the ≤10%/8h P0 criterion is against the APP's *attributed* usage (Android Settings → Battery / `dumpsys batterystats`, and only readable before the next full charge resets it — night 16's attribution was wiped by the morning charge); the session-log number is *total device drain*, now a **regression tripwire at ≤25%/8h** on this hardware. Code-side power model (AudioEngine/MonitorService audit): compute is modest (~2 windows/s spectral work, ~2% core duty), the structural costs are the session-long PARTIAL_WAKE_LOCK (`MonitorService.kt:699` — SoC never suspends for 7.5h), the `UNPROCESSED` mic path, and per-window noise-floor copy+sort GC churn; DB/clip/log I/O is negligible. Follow-ups: (a) **wakelock A/B night** — debug toggle to drop our lock (AudioFlinger holds its own record lock); verdict gated on heartbeat windows-continuity; (b) pre-spectral level gate + cached noise floor (detection-neutral compute cut); (c) read Android Settings → Battery → app share for a night *before* charging. Also still outstanding: the interrupted-night RECOVERED drill (no RECOVERED/INTERRUPTED session exists). Night 16 itself: loudest night recorded (792 snore events, 50m56s union, 20-min continuous bout 03:00–03:20).

## P1 — Dogfood + hardening (1–2 weeks alongside nightly use)

1. **30-night dogfood** with a tiny nightly log (battery %, anomalies) — Phase 1's definition-of-done gate ([MVP_SCOPE](MVP_SCOPE.md) §7).
2. **Garmin live import** (same period): enter the rotated key, import a date, check the four endpoint HTTP statuses in Settings — validates the `?date=` assumption and real payload shapes against the parsers; raw JSON makes remapping lossless.
3. ~~**CI + lint rules**~~ — **done (2026-11-04)**: GitHub Actions runs build + unit tests + Android lint + the four policy gates (merged-manifest permission allowlist — now 9 declared permissions + 2 documented library additions, forbidden-terms check, repo-hygiene check, network-host allowlist) plus ruff/pytest; see `.github/workflows/ci.yml` and [ARCHITECTURE.md](ARCHITECTURE.md) §6.6.
4. **Report snapshot persistence** (DATA_MODEL §3.7). ~~Retention decision for `raw_payload`~~ — **done (2026-11-04)**: raw payloads now follow session retention in the daily worker (DATA_MODEL §3.9).
5. **DB trigger test** for clinical immutability (in-memory Room test against the `clinical_baseline` triggers) — the only Phase 1 safety mechanism with no automated coverage.
6. **Retention-settings UI** — the plumbing exists (`UserPreferences.setRetention`, worker already reads it); only the Settings screen section is missing.
7. ~~**Health Connect stage import**~~ — **implemented (2026-11-16), awaiting on-device E2E validation**: connect-client 1.1.0 (compileSdk 36 / AGP 8.9.1 / Gradle 8.11.1), `READ_SLEEP` permission + rationale plumbing, schema v14 `sleep_stage.source` (GARMIN/WEARABLE, source-scoped replace), `HealthConnectImportManager` (pure mapping/filter/summary helpers + unit tests + instrumented migration test), Settings card and report backfill rendering the source-labeled stage timeline. Validate: grant the HC sleep read permission, import a date with a linked session, confirm the report's timeline and the `raw_payload` summary row.

## The pain-point line — decided 2026-11-19 (phase view: [ROADMAP.md](ROADMAP.md))

The dogfood goal for this stretch: **analyse sleep and highlight pain points**. Five items, decided and ordered — 1–2 implemented (2026-11-19), 3 once a multi-week baseline has accumulated, 4–5 after that.

1. ~~**Morning rating**~~ — **implemented (2026-11-19)**: 1–5 + an optional note each morning, stored as a `MORNING_RATING` DailyFactor (`SCALE_1_5` kind, `user_factor` table, `valueNum`, wake date). Deliberately **not** a Measurement row — the DailyFactor doctrine holds: self-logged context never feeds scores. Chips in the report's morning-notes card (the report opens directly after stop), editable through the existing Factor Log screen.
2. ~~**Night tags**~~ — **implemented (2026-11-19)**: `SICK` and `SLEPT_ON_BACK` as boolean DailyFactors (ALCOHOL/LATE_MEAL/STRESS already exist). `FactorLogScreen` gained the UI-state fields + save-mapping entries, and `FactorLogRepository` the single-key merge upsert/removal (`saveFactorForDate`/`removeFactorForDate`) so a report-side write never clobbers the Daily log's day — the one piece of new repo logic.
3. **Rolling-baseline pain-point layer** (after a multi-week baseline accumulates) — 7-night rolling baseline per metric; a pain point = deviation beyond threshold on ≥ 2 consecutive nights; validity grade A/B nights only; weekly ranked top pain points (recurrence × effect on outcome). Extends the existing `InsightRules` baseline-excursion logic; no new measurement claims.
4. **Formalized N-of-1 experiments** — the shipped Playbook/`sleep_experiment` machinery gains auto-tagging of nights into with/without groups (fed by 1–2) and a distribution comparison with a minimum-nights guard. First candidate: snoring share vs HRV (ρ = −0.80 over the first 11 dogfood nights, via `tools/night_trends.py`).
5. **Breathing-irregularity indicator** (Tier 2, behind the experimental toggle) — a conservative acoustic heuristic over existing events/clips (bout end → silent gap → resumption, loud tier), surfaced as "worth mentioning to a doctor", never a diagnosis. Fills the Tier-2 exploration slot the README already reserves (Phase 2's M7); Phase 5 promotion rules unchanged.

**Hardening follow-ups (from review, 2026-11-19 — P3s, not blockers):** (a) `FactorLogRepository.mergeForDate` reads outside the DAO's `@Transaction` — a single-writer assumption, documented in the code and safe with the report's morning card as the sole caller; proper fix is an atomic read+delete+insert DAO method. (b) `ReportViewModel.saveMorningFactors` has no failure feedback — deferred until the codebase has a standard error surface (the same gap as `saveMorningNotes`/`labelEvent`). (c) `removeFactorForDate` on an absent key delete+inserts unchanged rows — a one-line early-return if ever touched.

## P2 — Phase 2 proper (acoustic intelligence; ~3–6 weeks)

1. **Fixture eval harness** (labeled WAV set → precision/recall asserted in tests) — needed before any model claim; your labeled events from P0 dogfood are the seed. The harness is shipped (`evalFixtures` task, `tools/export_fixtures.py`); the loop is documented in [EVAL_RUNBOOK.md](EVAL_RUNBOOK.md).
2. **Detector v1**: TFLite model (YAMNet-mapped first, custom CNN later) behind the existing `SnoreDetector` interface + `model_registry` table (already specced, DATA_MODEL §3.8).
3. **Movement (accelerometer)** → Tier-1 sleep/wake estimate (M5/M6) — biggest report upgrade.
4. **Experimental breathing indicators** (M7/M8) behind the Settings toggle, exactly per MEASUREMENT_RELIABILITY §2/§7.
5. Per-user threshold calibration from accumulated labels.

## P3 — Phase 3 remainder → Phase 4

Health Connect import · export (CSV/JSON + PDF with provenance + disclaimers) · clinical comparison view · trends/personal bands/associations (Phase 4). *(Scheduled-start research is done — shipped as the one-tap bedtime-notification flow in the recording controls; device verification pending.)*

---

## The single highest-leverage next action

> **Run night 1 of the overnight test — tonight, with the P0 checklist.**
>
> It is the only step that produces the evidence every other decision depends on: whether the battery gate holds, whether the service survives OEM power management, whether v0 detection produces clips worth labeling, and whether the morning report is trustworthy enough to build Phase 2 on. Roughly 10 minutes of setup, then sleep.
