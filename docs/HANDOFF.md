# Handoff — developing SleepAnalysis with an AI coding agent

This repo is maintained by a single developer working through AI coding agents. This document
briefs a **fresh agent (no prior context)** and provides ready-to-paste task prompts. It was
written for a handoff to Mistral AI (2026-11-27) but is agent-agnostic.

How to use: start each new chat with the **Preamble** below, then paste **one** task prompt.
One task per session; never parallel tasks in one chat. Prompts are sequenced by leverage —
run Prompt 1 first, then pick by priority from NEXT_STEPS.md.

---

## The Preamble (paste at the top of every session)

```text
You are working in the sleep-analysis repo (Android app: Kotlin 2.0 / Jetpack Compose /
MVVM + Hilt / Room + DataStore, minSdk 30 / target 35; Python tooling under tools/, managed
with uv — never system pip). Before touching anything, read these three docs:

- docs/STATUS.md — the honest current state (implemented vs documented vs unverified)
- docs/NEXT_STEPS.md — the prioritized plan
- docs/MEASUREMENT_RELIABILITY.md — the BINDING claim policy

Non-negotiables — a change that violates these must be refused, not implemented:
1. Never compute or display Tier-3 measurements: phone-derived AHI/RDI, sleep stages,
   arousal index, SpO₂ desaturation (types M14–M17). `MeasurementRecorder` rejects Tier-3
   writes; vendor desaturation counts (`numberOfEventsBelowThreshold`) are banned from
   import AND display.
2. Acoustic events are never arousals; audible pauses are never apneas. Exact allowed/
   forbidden wording: MEASUREMENT_RELIABILITY §3. The app is a wellness/research tool,
   never a medical device; disclaimer cards are mandatory.
3. Vendor data (Garmin / Health Connect) is context only — rendered with the vendor
   disclaimer, never merged numerically into phone-derived aggregates.
4. Self-logged factors (`user_factor` / DailyFactor) are user context and never feed scores.
5. Audio never leaves the device. Network is exactly one allowlisted host
   (personal.connectapi.garmin.com), user-triggered only; the Garmin key lives in the
   Android Keystore (AES-GCM). App data is excluded from backup/transfer. The CI gates
   (`checkPermissionAllowlist`, `checkForbiddenTerms`, `checkNetworkHosts`,
   `checkRepoHygiene`) enforce parts of this — a new permission or host means updating the
   allowlist files deliberately, never disabling a gate.
6. Every change: Conventional Commits (`feat:`/`fix:`/`docs:`/`refactor:`/`test:`), tests
   written with the code, full suites green before claiming done
   (`./gradlew :app:testDebugUnitTest` and `uv run pytest`), and the doc that describes the
   changed behavior updated in the same change — docs drift is treated as a defect here
   (see STATUS.md §5 for the annotation style: ~~strikethrough~~ + dated note).
7. Room is at schema v16. Any table change needs a real migration + exported schema JSON +
   coverage in the instrumented MigrationTest. If code and docs disagree, trust the code,
   then fix the doc.
```

## Project snapshot (as of 2026-11-27)

- **State:** Phase 1 complete (overnight recorder + morning report) + Phase 3 first slices
  (Garmin Personal API import, Health Connect stage import, daily factor log via the report's
  morning card) + hardening passes. The 2026-11-22 consolidation (tasks T1–T10) removed the
  experiments feature, the clinical-baseline table, the factor-log screen, and the settings
  debug toggles. Detector: `energy-heuristic-v3-peakgate` (heuristic — no learned model yet);
  score formula `snore-score-v4`; Room schema v16.
- **Evidence baseline (2026-11-26):** 767 unit tests / 0 failures / 83 classes; 121 Python
  tests; CI green.
- **Dogfood:** 16 nights / 14 recorded sessions (2026-11-01 → 14). Battery measured (night 16:
  25 % device drain in 7 h 17 m — regression tripwire ≤ 25 %/8 h). Still unverified on device:
  thermal-throttle path, Doze/exact-alarm schedule behavior, the RECOVERED finalize drill,
  notification lifecycle, app-attributed battery share, Health Connect import E2E.
- **Where things live:** Kotlin under `app/src/main/java/com/sleepanalysis/` (package
  `com.sleepanalysis`; `monitoring/` capture+detection, `data/` Room/repos/Garmin, `ui/` Compose,
  `domain/` report logic). Python tooling in `tools/` (`morning_report.sh` is the per-night
  analysis entry). Docs in `docs/` (`FUNCTIONAL_SPEC.md` is the behavior contract, 11 sections).

---

## Task prompts

### Prompt 1 — Orientation & baseline verification (run this first, change nothing)

```text
[PREAMBLE]

Task: establish that you can build and test this project, and report the lay of the land.
Do not modify any file.

1. `./gradlew assembleDebug :app:testDebugUnitTest -q` — report the unit-test count and
   failures vs the baseline (767 tests / 0 failures / 83 classes).
2. `uv run pytest -q` — report vs baseline (121 tests).
3. Read STATUS.md, NEXT_STEPS.md, MEASUREMENT_RELIABILITY.md, ARCHITECTURE.md, and skim
   docs/functional_spec/00-overview.md.
4. Reply with: (a) build/test evidence, (b) a ≤ 15-line summary of the current state in your
   own words, (c) any doc-vs-code drift you noticed while reading (do not fix it yet).
```

### Prompt 2 — Retention-settings UI (NEXT_STEPS P1 #6 — small, well-specified)

```text
[PREAMBLE]

Task: build the missing retention-settings UI. The plumbing exists and must be reused, not
rebuilt: `UserPreferences.setRetention` (prefs keys already read by the daily RetentionWorker;
defaults: clips 7 d, sessions 90 d; session logs and raw Garmin payloads follow session
retention). Add a Settings screen section (see docs/functional_spec/09-app-shell-settings-privacy.md
for the screen's current five groups and conventions) with per-data-type retention controls
and clear "this permanently deletes data" framing.

Done means: ViewModel + prefs round-trip unit-tested; UI compiled; STATUS.md §3 row
("Retention-settings UI — no UI yet") closed with the strikethrough style; the row in the
README known limitations updated; functional_spec section 09 gains the new group.
```

### Prompt 3 — Pre-flight mic sanity check (IDEAS_BACKLOG #1 — protects data quality)

```text
[PREAMBLE]

Task: implement the pre-flight microphone sanity check from docs/IDEAS_BACKLOG.md idea #1:
a ~5 s capture at tap-start (after the start-delay decision, before the night is spent) that
verifies RMS sits above the room's noise floor and real signal is present. It must catch a
blocked mic / dead audio path AT BEDTIME instead of in the morning (dogfood night 1 was 1,047
near-silent events). Reuse the existing floor concepts (`DetectorConfig` quiet-room gate uses
floor ≤ −80 dBFS; silenced-mic detection already records `client_silenced_spans` — a silent
5 s probe on a healthy mic looks different from zeros-from-a-silenced-client; read
docs/STATUS.md §1 on UNCAPTURED-vs-quiet first).

Done means: the check runs inside the start path without delaying capture materially; a
clear bedtime warning surfaces (notification or pre-start dialog — match existing UI
patterns); synthetic-audio unit tests cover healthy / blocked / silenced mics; IDEAS_BACKLOG
#1 marked done; the functional_spec 01 section documents the new step.
```

### Prompt 4 — Full-screen bedtime prompt (IDEAS_BACKLOG #9 — small)

```text
[PREAMBLE]

Task: make the scheduled bedtime notification a full-screen intent per docs/IDEAS_BACKLOG.md
idea #9: `setFullScreenIntent` on the "Time to record" notification so it takes over the
lock screen alarm-style at bedtime. Needs the `USE_FULL_SCREEN_INTENT` permission (Android
14+ requires the user to grant "Alarms & reminders" or special full-screen access in
Settings — surface that grant flow) and must still be exactly ONE tap to start recording
(the one-tap user-interaction exemption is what keeps while-in-use mic access legal — never
auto-start without a tap).

Done means: permission added to the manifest AND `ci/permission-allowlist.txt` (the
`checkPermissionAllowlist` gate will fail otherwise — this is by design); settings toggle
(default off); unit tests for the notification builder + policy; functional_spec 01 and 09
updated; IDEAS_BACKLOG #9 marked done.
```

### Prompt 5 — Swipe-to-label clips (IDEAS_BACKLOG #8 — small, feeds the eval loop)

```text
[PREAMBLE]

Task: add swipe-to-label to the existing clip labeling screen (the report's bulk
"Label clips" action; see docs/EVAL_RUNBOOK.md for why labels are the currency: ≥ 10 labeled
events seed the detector eval loop). A swipe gesture assigns the default label (Snore) and
advances to the next clip; a "3/5 labeled tonight" progress affordance motivates finishing.
Respect the existing five-way `EventUserLabel` set (Snore/Cough/Speech/Movement/Other-not-snore)
and its tests. Compose gesture handling only — no new dependencies.

Done means: unit tests for the labeling-session state (advancement, undo, completion);
IDEAS_BACKLOG #8 marked done; functional_spec 06 (report UI) updated.
```

### Prompt 6 — RECOVERED drill + on-device hardening (run WITH the developer present)

```text
[PREAMBLE]

Task: the interrupted-night RECOVERED finalize path has never been exercised on a real
device (no INTERRUPTED/RECOVERED session exists; NEXT_STEPS P0 "still outstanding"). Walk
the developer through the drill, then verify the results:

1. Have the developer start a night recording, then mid-night run:
   `adb shell am force-stop com.sleepanalysis`
2. Next launch must show the interrupted-night banner with the saved-event count. First try
   DISCARD/finalize (RECOVERED): the report must cover up to the kill, with `ended_at`
   bounded by the last heartbeat (+1 min), and capture_stats showing heartbeat-saved
   progress.
3. On a later night, try the Resume path (`resumeAfterInterruption` = true) and confirm the
   report leads its integrity notes with it.
4. Inspect the resulting session via `tools/morning_report.sh` and the session log. Fix
   whatever the drill surfaces (behavior bugs AND doc drift), with tests.
```

### Prompt 7 — Sleep intelligence on existing data (IDEAS_BACKLOG #3 + #6)

```text
[PREAMBLE]

Task: two report upgrades that only need data the app already imports (rules in
docs/IDEAS_BACKLOG.md; vendor framing rules in MEASUREMENT_RELIABILITY §4 — context,
disclaimer, never merged into phone aggregates):

1. Acoustic events × Garmin sleep stages: join event timestamps with the night's
   `sleep_stage` spans (schema v9+, source-scoped) and render "7 of 11 events in REM
   windows" style statements in the morning report. Handle nights with no stage data
   (omit the section) and RECOVERED sessions.
2. Sleep-debt card: 7-day actual vs `VENDOR_SLEEP_NEED` (baseline ≈ 460 min on this user's
   data) from existing Garmin rows — two queries + one card on the trends screen
   (docs/TRENDS.md conventions: eligibility gating, version flags).

Done means: assembler-level unit tests with synthetic stage/event fixtures; the card/section
omits gracefully when inputs are missing; NIGHT_REPORT.md / TRENDS.md updated; IDEAS_BACKLOG
#3 and #6 marked done.
```

### Prompt 8 — Export: CSV/JSON + PDF (NEXT_STEPS P3 / IDEAS_BACKLOG #7)

```text
[PREAMBLE]

Task: implement user-initiated local export per NEXT_STEPS P3 and IDEAS_BACKLOG idea #7:
(1) CSV/JSON per night or range with the full provenance columns (source, method, unit,
tier, model version — see DATA_MODEL §3); (2) a PDF morning/period report for clinician
sharing. Both must pass the same ClaimPolicy display filter and embed the standing
disclaimers; export is share-sheet/local only — NO network anywhere near this feature.
The clinical-baseline side-by-side was removed in the 2026-11-22 consolidation — do not
reintroduce it (see STATUS.md §5).

Done means: round-trip unit tests (export → re-importable, provenance intact); PDF renders
the disclaimer card and the session-health section; ROADMAP Phase 3 export item + NEXT_STEPS
P3 updated; IDEAS_BACKLOG #7 marked done.
```

### Prompt 9 — Phase 2 kickoff: learned detector behind the existing interface (P2 #1/#2)

```text
[PREAMBLE]

Task: start Phase 2 per ROADMAP and NEXT_STEPS P2 — a learned snore detector. Ground rules:
the `SnoreDetector` interface is the swap point; the heuristic
(`energy-heuristic-v3-peakgate`) stays the default until the model beats it; `model_registry`
rows + `MODEL_VERSION` discipline are already in place.

1. The fixture eval loop is live: `evalFixtures` Gradle task + `tools/export_fixtures.py`,
   documented in docs/EVAL_RUNBOOK.md with the labeled dogfood clips as the seed. Run the
   loop and report current heuristic precision/recall FIRST.
2. Export an expanded training set (features_json already persists per-event decision
   inputs). Train offline (Python, uv) — YAMNet-mapped embeddings first, custom CNN later.
3. Wire the model behind `SnoreDetector` with TFLite, versioned via `model_registry`.
4. Success gate (ROADMAP Phase 2): v1 beats v0 by ≥ 10 pp precision on fixtures; user label
   agreement ≥ 85 % on snore clips over a 14-night sample.
5. ARCHITECTURE §4/§6 anticipated this (log-mel `FeatureExtractor` design-not-code) — read it
   before deciding what to build vs keep inline.

Done means: eval numbers reported honestly (EVAL_RUNBOOK history updated); the model is a
drop-in behind the interface with fixture-based tests; MEASUREMENT_RELIABILITY §7.1 detector
changelog entry; no claim-policy changes without an explicit developer decision.
```

### Prompt 10 — Movement → sleep/wake estimate (P2 #3 — the biggest report upgrade)

```text
[PREAMBLE]

Task: implement the accelerometer path per ROADMAP Phase 2 (M5/M6): sample movement in the
existing `microphone`-type foreground service (or a co-registered sensor path), derive a
Tier-1 sleep/wake estimate, and land it in the report as its own section — clearly
distinguished from the vendor stages (rule: MEASUREMENT_RELIABILITY §2/§8 — phone-derived
sleep/wake ESTIMATE is allowed and must never be labeled as sleep STAGING; the M14–M17 ban
still applies to N1/N2/N3/REM).

Constraints: overnight battery is a regression gate (tripwire ≤ 25 %/8 h on this hardware,
night 16 baseline) — batch sensor reads, keep the SoC awake cost in mind (the session-long
wake lock is already the dominant cost, NEXT_STEPS P0 power model). New measurement types
must go through `MeasurementRecorder` with provenance; any new manifest permission updates
`ci/permission-allowlist.txt`.

Done means: estimate algorithm unit-tested on synthetic motion traces; report section +
integrity notes; DATA_MODEL (new types + provenance), NIGHT_REPORT.md, MEASUREMENT_RELIABILITY
tier rows updated; ROADMAP Phase 2 item annotated.
```

### Prompt 11 — Breathing-irregularity indicator (pain-point line #5 — Tier 2, experimental)

```text
[PREAMBLE]

Task: implement the Tier-2 breathing-irregularity indicator per NEXT_STEPS pain-point item 5
and MEASUREMENT_RELIABILITY §2/§7: a conservative acoustic heuristic over EXISTING
events/clips (loud-bout end → silent gap ≥ threshold → resumption), surfaced behind the
existing experimental toggle in Settings, worded exactly per MEASUREMENT_RELIABILITY §3
equivalence #2: "N breathing-pattern breaks (experimental indicator)" — never apnea/hypopnea/
AHI-adjacent language, framed as "worth mentioning to a doctor", never a diagnosis.

Done means: heuristic unit-tested on synthetic event sequences; report section appears only
with the toggle on + sufficient signal; §7 change-control entry written (what would promote
it, what would demote it); ROADMAP/STATUS rows updated.
```

### Prompt 12 — Recurring: doc-consistency pass (run after any milestone, or monthly)

```text
[PREAMBLE]

Task: audit the docs against the code and repair drift. Method (this repo does this after
every milestone — STATUS.md §5 records each pass):

1. Grep the docs for factual claims and verify each against the code: class/function names in
   backticks (do they exist?), constants (thresholds, counts, budgets), schema version and
   migration list, test-count baselines (run both suites), feature shipped/not-shipped
   claims, cross-doc status contradictions (STATUS vs NEXT_STEPS vs ROADMAP vs README).
2. Fix with the house style: current-behavior text updated in place; removed things get
   ~~strikethrough~~ + a dated note (see STATUS.md §5, the 2026-11-27 entry is the template);
   historical records stay historical.
3. Refresh STATUS.md's "As of" header, evidence line, and add a dated §5 entry describing
   the pass.

Report every discrepancy found (doc, line, said vs is) before fixing, and never change code
in a docs pass.
```

---

## Notes for the developer (not the agent)

- Nightly dogfood loop: docs/DOGFOOD_CHECKLIST.md; `tools/morning_report.sh` produces the
  analysis bundle for a session (battery, loudest, trends). The agent cannot run your
  nights — the data-quality ideas (#1/#3/#5) exist precisely to shorten that loop.
- The claim policy is the product. If a prompt result ever reads like a medical claim, reject
  it and cite MEASUREMENT_RELIABILITY.md — even if the agent argues the wording is fine.
- After each merged task, re-run Prompt 12 before the next one; small drift compounds fast.
