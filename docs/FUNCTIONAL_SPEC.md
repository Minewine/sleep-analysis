# Functional specification

**Status:** complete (2026-11-25) · 11 sections under [`docs/functional_spec/`](functional_spec/) · 388 requirement rows, 10 cross-cutting invariants, 109 consolidation candidates, 29 confirmed defects.

## Purpose

This specification says what SleepAnalysis does and how, precisely enough for two uses:

1. **AI code review** — every section states the behaviour a reviewer can check with yes/no
   requirement rows, each with the exact conditions, constants and the test that pins it. A
   reviewer reads the section, then the code, and reports every place they disagree.
2. **Consolidation** — the owner expects redundant code. Each section separates the
   **contract** a refactor must preserve (persisted formats, version strings, platform
   contracts, cross-area interfaces, safety-net tests) from the **implementation** (free to
   change), and lists consolidation candidates with checkable evidence and a removal risk.
   [`11-consolidation-map.md`](functional_spec/11-consolidation-map.md) merges all candidates
   across areas into one ranked, phased plan.

## How to read it

- **Requirement IDs** — each section N has rows `XXX-01…` (prefix REC, AUD, DET, DAT, RPT,
  RUI, INS, GRM, APP, PYT). One testable statement per row: exact condition, exact outcome,
  exact constant values.
- **Status values** — `tested` (a unit/instrumented test asserts it; named in the row) ·
  `device-verified` (evidence from a real night on the owner's phone) · `unverified`
  (implemented, no test, no device evidence) · `not implemented` (designed or documented only).
- **Candidate kinds** — `dead`, `test-only`, `superseded`, `duplicate`, `debug/experiment`,
  `legacy-compat`, `over-general`, `dependency`, `doc-drift`; risk of removal is
  `none` / `low` / `medium` / `high` with a reason.
- **Where is not a requirement** — the "Where" column names `path/File.kt · symbol` to locate
  the behaviour; the requirement is the statement, not that line of code. A refactor may move
  or rewrite the implementation as long as every requirement row and every N.6 contract
  boundary still holds.
- **N.7 / N.8 of each section** list that area's candidates and open questions; section 11
  supersedes them for planning (duplicates merged, risks reconciled).

## Contents

| Section | File | One line |
|---|---|---|
| 0. Overview | [00-overview.md](functional_spec/00-overview.md) | Product boundary, module map (all 100 Kotlin files), end-to-end flows, the 10 invariants, platform/permissions, dependencies, documentation freshness, glossary |
| 1. Recording controls | [01-recording-controls.md](functional_spec/01-recording-controls.md) | Service state machine, every start/stop command and gate, start delay, interruptions/resume, scheduling and alarms, the two switches and skip history, Home UI; includes the independent review verdict on the uncommitted schedule-row off-state change |
| 2. Audio capture engine | [02-audio-capture.md](functional_spec/02-audio-capture.md) | Engine lifecycle/threading, capture-quality mechanisms (silenced-client, stall watchdog, thermal), capture_stats JSON v1→v6, WAV/clip integrity, measurement provenance, session-log format |
| 3. Detection, clips and scoring | [03-detection-clips-scoring.md](functional_spec/03-detection-clips-scoring.md) | Hop-by-hop detection pipeline, shipped detector `energy-heuristic-v3-peakgate` gates, clip budgeting, noise floor, SnoreMath/SnoreScore formulas, fixture evaluation |
| 4. Data layer | [04-data-layer.md](functional_spec/04-data-layer.md) | Room schema v14 in full, migration chain 1→14, SessionRepository and every DAO method with callers, write-only columns, retention worker, Hilt wiring, backup rules |
| 5. Report domain | [05-report-domain.md](functional_spec/05-report-domain.md) | Report assembly with exact denominators, snapshot schema v12, bursts/bouts, quiet gaps, validity grading A–D, daily factors, user labels, the unwired ClaimPolicy |
| 6. Report UI | [06-report-ui.md](functional_spec/06-report-ui.md) | Refresh pipeline (HC sync, Garmin backfill + retry budgets, evening window), every card and control, clip playback, labeling, share text, session log viewer |
| 7. Trends, insights, experiments | [07-trends-insights-experiments.md](functional_spec/07-trends-insights-experiments.md) | 7-night trend rows and missed-night synthesis, validity gating, personal baseline, SRI/social jetlag, rule catalog, playbook and N-of-1 lifecycle, escalation |
| 8. Garmin and Health Connect | [08-garmin-health-connect.md](functional_spec/08-garmin-health-connect.md) | Key storage, single-host network posture and endpoints, parsers and exclusions, session linking, retry gate and import-date default, Health Connect import |
| 9. App shell, settings, privacy, build | [09-app-shell-settings-privacy.md](functional_spec/09-app-shell-settings-privacy.md) | Navigation routes, onboarding disclaimer, history/baseline/factor-log screens, debug toggles, privacy rules in manifest and code, Gradle gates and CI |
| 10. Python tooling and CI | [10-python-tooling-ci.md](functional_spec/10-python-tooling-ci.md) | One block per tool, the fixture-format contract, app-side contracts each script depends on, duplication across scripts, CI Python job |
| 11. Consolidation map | [11-consolidation-map.md](functional_spec/11-consolidation-map.md) | 109 candidates grouped by kind with merged evidence and reconciled risks, 29 confirmed defects, four-phase deletion order, false leads |

## Cross-cutting invariants (from section 0.4)

| ID | Invariant |
|---|---|
| INV-01 | Audio, clips, logs and the database stay on the device. The only network purpose is a user-triggered Garmin pull; no other host, no telemetry. Enforced by the `checkNetworkHosts`/`checkPermissionAllowlist` Gradle gates; the gate sees only literal hosts in Kotlin sources. |
| INV-02 | Every measurement row carries provenance: source, method, type, unit, confidence, model version, isClinical. All phone rows are written through `MeasurementRecorder`; verification resolved that nothing writes the table outside it. |
| INV-03 | The clinical baseline is immutable: insert and read only; SQL ABORT triggers on update/delete, created on database create and every open; only delete-all removes rows. No test pins it yet (an open decision in 0.9). |
| INV-04 | An interrupted night is never silently auto-recovered: capture resumes only via the explicit resume action; the night ends only via an explicit finalize. |
| INV-05 | Absence is made visible, never silently dropped: interrupted card, missed-night banner, not-captured spans (skipped-hop and client-silenced), capture stall watchdog. |
| INV-06 | Privacy basics: first-launch disclaimer gates the app, mic-active indicator, `allowBackup=false`, data-extraction rules, app-private storage only. |
| INV-07 | Retention: sessions/logs/raw payloads follow sessionRetentionDays (default 90), clips follow clipsRetentionDays (default 7), orphan sweep only with no open session and ≥1 h file age. The values are defaults only — no settings UI exists. |
| INV-08 | Claims policy: Tier 3 is never computed, stored or shown; Tier 2 only behind an experimental toggle. Enforced only at the write boundary (`requireWritable` + `checkForbiddenTerms`); the display half (`ClaimPolicy.displayAllowed`) has no production caller — a documented invariant without an enforcement point. |
| INV-09 | Comparisons are gated on provenance: nights count for insights/baselines only with grade A/B and exact detector and score version matches; changes are flagged, never silently compared. |
| INV-10 | Background entry points never start the microphone: the scheduled-start receiver only posts the bedtime notification, boot/timezone receivers only re-arm; the only start sites are the service's start/startNow. |

Full statements with enforcement points, pinning tests and status:
[section 0.4](functional_spec/00-overview.md).

## How this document was produced

Eleven writer agents drafted one section each from the working tree (2026-11-24/25), citing
only code they had read in that session; a mechanical reference scan and Android
Lint supplied candidate leads that every writer had to confirm or refute with their own greps.
Each section was then checked row-by-row against the code by an independent reviewer agent
(writer ≠ reviewer, per its verification brief: requirements, constants, contract boundaries,
re-verification of every candidate row, flows, failure handling, coverage, form). All eleven
sections were reviewed this way; every finding (11 WRONG, 3 UNSUPPORTED/FALSE-CANDIDATE-level,
several MISSING/QUALITY) was applied by the section's writer or, for sections whose writer had
ended, by the coordinator, and the repository citation checker was re-run clean after
each fix — 1,974 file citations, 1,380 symbols, 627 test citations, 0 unresolved problems.
The consolidation map was synthesised from the verified N.7 tables plus the evidence, merging
duplicates across areas. Two known limits: status labels `device-verified` rest on the owner's
own device observations and were not re-verified; reviewers spot-checked documentation rather
than reading every doc end to end. Baselines at production time: `./gradlew :app:testDebugUnitTest`
→ 821 tests, 0 failures; `uv run pytest -q` → 116 passed; `uv run ruff check .` → clean.
