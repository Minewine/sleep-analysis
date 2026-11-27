# Insights — personal baselines, regularity, and rule-based suggestions

Schema v16 (docs/DATA_MODEL.md §3.7/§3.8). The Insights screen turns the recorded nights into
*personal* observations: robust bands over the user's own eligible nights, an approximate
sleep-regularity row, and a fixed set of deterministic rules. Everything here is pure Kotlin
(`domain/insights/`) over stored evidence, assembled by `SessionRepository.buildInsights()`.
(The playbook/escalation/experiment sections described at the bottom were removed in the
2026-11-22 consolidation.)

**Tone contract:** every claim reads "your recordings suggest…" — never clinical, never
diagnosis, never a threshold handed down from a guideline. Events ≠ arousals; pauses ≠ apneas;
wrist windows ≠ PSG ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §2, M13).

## Eligibility

A night is **eligible** when BOTH hold:

- **Validity gate:** the night's NIGHT_VALIDITY grade is **A or B** (grade ≥ B) — capture
  evidence (windows, gaps, clips, interruptions) supports trusting the night at all
  ([NIGHT_REPORT.md](NIGHT_REPORT.md) §Night validity).
- **Registry gate:** the night's detector version and score-formula version match the
  **current** versions in `model_registry` (schema v12, docs/DATA_MODEL.md §3.8) — before any
  registry row exists the shipped constants are the current versions, so pre-v12 nights stay
  comparable. A version change silently starts a NEW baseline; nothing compares across it.
  A night's detector version is RESOLVED ONCE: the label-aware first snore event's version,
  else — on a zero-snore night — the detector version current at its finalize (stored on the
  report snapshot, docs/DATA_MODEL.md §3.7). That single resolved value serves the snapshot,
  the registry and this gate, so **zero-snore nights stay eligible** and anchor the share band
  low instead of silently dropping out of every baseline.

The analysis set is the rolling last **14 eligible nights** ([PersonalBaseline.WINDOW_NIGHTS]).
The regularity windows and every rule run on the SAME eligible set — one eligibility decision,
never per-card disagreement.

## Confidence tiers

| Concept | Meaning |
|---|---|
| LOW | Directional or single-night evidence; small samples; association wording only |
| MODERATE | Same claim, more support (rule-specific group-size gates, e.g. R6 at ≥5 nights per group) |

**HIGH does not exist** for insights. A personal n-of-14 observation can never earn it; any
future rule that reads like a clinical claim is a defect, not an optimization.

## Baseline method (median + MAD)

Per metric over the eligible window: **median ± 2·1.4826·MAD** (the robust σ so one weird
night neither shifts nor inflates the band). A metric's band half-width never drops below a
per-metric floor (share 1 %, bout 1 min, score 5, awake 10 min, rMSSD 5 ms — APP CONVENTIONS),
so a stable personal metric cannot collapse to zero width. Excursion = robust z =
(value − median) / (1.4826·MAD, floor-limited) with \|z\| > 2.5. Baseline is READY at ≥10
eligible nights; before that the state is COLLECTING(n/10) and band-gated rules stay silent.

Metrics: `snore_share` (label-aware union snoring / captured analysis time, 0..1),
`longest_bout_ms`, `score` (stored SNORE_SCORE), `garmin_awake_min` (Garmin
VENDOR_AWAKE_SLEEP/60), `rmssd_ms` (Garmin VENDOR_HRV_RMSSD). Missing Garmin values are
missing — never imputed.

## SRI / SJL definitions

- **SRI (Sleep Regularity Index)** — Windred 2024 (Sleep): `100 × mean[I(state_e == state_(e+24h))]`
  over 60 s epochs across the union horizon, each epoch's asleep/awake state compared with the
  epoch exactly 24 h later (absolute epochs; a DST day simply compares one fewer hour).
  APPROXIMATED: inputs are the nightly Garmin sleep windows re-parsed from the stored
  `raw_payload` dailySleep JSON (no nap epochs — daytime is approximated as wake), wrist-derived.
  Needs ≥5 valid days → otherwise COLLECTING.
- **Social jetlag** — Roenneberg 2012 (Current Biology), approximate: \|midpoint(free days) −
  midpoint(work days)\| over recent windows, Sat/Sun vs Mon–Fri classified by the LOCAL MORNING
  a window ends on, midpoints anchored to the preceding local noon. The >1 h flag is labeled
  **CONVENTION**: the app does not know the user's work schedule, and shift workers are
  misclassified by design of the approximation.

## Rule catalog

Trigger, claim template (copy key = string-resource name), evidence shown, confidence rule and
literature anchor per rule. All snoring-metric rules require eligible nights only and suppress
trivial-snore nights (< 1 % of capture — Klaus 2021, IJERPH: phone-mic snoring reliability
collapses for light snorers). All thresholds not marked cited are APP CONVENTIONS.

| `bedtime_variability` | `insight_rule_bedtime_variability` | Std of in-bed start clock-times (anchored to the preceding local noon) over a rolling 7-night window; triggers when tonight's std lands in the user's own worst tertile of historical rolling stds (needs ≥5 historical stds). NO absolute threshold — NSF 2023 endorses none; copy gives the descriptive Windred quintile context (~1 h stable / ~3 h erratic). | Tonight's night + window counts in detailJson | LOW | Windred 2024 (Sleep, regularity context); NSF 2023 (no-threshold position) |
| `sri_dip` | `insight_rule_sri_dip` | Tonight's rolling SRI in the user's own worst tertile vs earlier rolling SRIs (≥5 historical). Association wording only — dose–response is contested, the rule never says "more regular is always better". | Tonight's SRI + historical median | LOW | Windred 2024 (Sleep) |
| `social_jetlag` | `insight_rule_social_jetlag` | SJL > 1 h (CONVENTION flag; approximate — no work-schedule tag). | SJL hours | LOW | Roenneberg 2012 (Current Biology) |
| `second_half_worse` | `insight_rule_second_half_alcohol` | Alcohol-logged night whose second-half snore share > 1.3 × first half (second half ≥ 1 % of capture) — the evidence-backed pattern copy: first-half deep-sleep suppression, later REM rebound + wake. | Both halves' shares | LOW | Ebrahim 2013 (Alcoholism: Clinical & Experimental Research) |
| `second_half_worse` | `insight_rule_second_half_untagged` | Same trigger without an ALCOHOL factor that night — the hedged copy: late-night REM/wake can be entirely normal. | Both halves' shares | LOW | Ebrahim 2013 (context); Klaus 2021 (trivial-snore suppression) |
| `baseline_excursion` | `insight_rule_baseline_excursion` | Any baseline metric outside its robust band (robust z > 2.5), both directions marked; READY baseline required; snoring-metric excursions suppressed on trivial-snore nights. | Metric value, band, z | LOW | Method: robust statistics convention (median/MAD) |
| `alcohol_nights` | `insight_rule_alcohol_nights` | ALCOHOL-logged nights vs the rest on Garmin awake minutes (longest bout in the detail); triggers at ≥3 with / ≥6 without nights with Garmin data. The logged flag carries NO amount — and no safe-hours cutoff exists (Gardiner 2025), so the copy never makes one. MODERATE at ≥5 each, else LOW. | Group medians + night ids | LOW / MODERATE at ≥5 per group | Gardiner 2025; Ebrahim 2013 |
| `caffeine_nights` | `insight_rule_caffeine_nights` | CAFFEINE_AFTER_14-logged nights vs the rest on Garmin awake minutes and sleep duration; ≥3 per group. The flag carries no dose. | Group medians + night ids | LOW | Gardiner 2023 (Sleep Medicine Reviews meta: TST −45 min — context in the doc, never rendered as the user's number) |
| `exercise_evening` | `insight_rule_exercise_evening` | EXERCISE-logged night whose Garmin awake minutes exceed the personal band. HONEST LIMIT: the factor log records only the fact — no timing, no intensity — so the <1 h-before-bed mechanism is NOT tested; the rule tests association only. | Tonight's awake minutes + band | LOW | Stutz 2019 (Sports Medicine, evening-exercise context) |
| `stress_high` | `insight_rule_stress_high` | STRESS ≥ 4 (self-log) night with rMSSD below the personal band AND Garmin awake minutes above it — both markers required. MODERATE at ≥3 stress-tagged nights in the window, else LOW. | Both z-scores + stress level | LOW / MODERATE at ≥3 nights | Kim & Dimsdale 2007 (Psychosomatic Medicine); Guo 2022 (wrist PPG context) |

## Playbook & Escalation — REMOVED (consolidation 2026-11-22)

The playbook (evidence-graded action catalog + N-of-1 experiments), the escalation card, and the machinery behind them — `Playbook.kt`, `EscalationRule.kt`, `ExperimentTracker.kt`, the `sleep_experiment` table (schema v13, dropped in migration 14→15) — were removed in task T6 (see [CONSOLIDATION_PLAN.md](CONSOLIDATION_PLAN.md)); the N-of-1 line is shelved in [NEXT_STEPS.md](NEXT_STEPS.md). Historical record, kept for the never-claims line below:

- **Playbook actions** carried evidence rows in [EVIDENCE_BASE.md](EVIDENCE_BASE.md), ranked by rule confidence × recency, with a never-list (no sleep restriction, no AHI/apnea/desaturation language, no cure claims) and experiment standards (≥ 7 usable phase nights, verdict = phase median beyond 2 robust SD, confound exclusion for foreign-factor nights, single ACTIVE row, optional A-B-A confirm).
- **Escalation** was a pure rule (`EscalationRule.evaluate`) over the same eligible night set plus a one-time STOP-BANG-derived self-report (S/O/T questions, Chung 2012): acoustic arm = ≥ 3 consecutive eligible nights with snoreShare > 0.25 AND longest bout > 10 min (streak adjacency over the eligible sequence, not the calendar); copy contract "worth discussing with a clinician", evaluation never diagnosis.

Still true: `InsightsDocTest` drift-tests the surviving rule catalog and copy keys; dismissed-rule persistence stays (guardrail 6 — `insights_dismissed_rules` in DataStore, re-shown from the insights screen). Any revival inherits the never-claims guardrails and the [EVIDENCE_BASE.md](EVIDENCE_BASE.md) evidence-key discipline.

## Guardrails — never-claims

1. No diagnosis, no screening, no apnea/arousal language anywhere in insight copy.
2. No population threshold becomes a trigger: triggers are the user's OWN distributions
   (worst tertile, robust band) or a convention clearly labeled CONVENTION.
3. No "safe amount" claims for alcohol or caffeine — the factor log records flags, and the
   literature has no safe cutoff to cite (Gardiner 2025).
4. Never compare across a detector/score version change (registry-gated eligibility).
5. Never render a Garmin value as anything but a device-reported estimate; missing Garmin
   data removes the metric, never imputes it.
6. Dismissal is a preference, never data deletion — dismissed rule ids persist in DataStore
   (`insights_dismissed_rules`) and can be re-shown.

## What the snapshot table contributes

Finalize stores each night's compact headline JSON in `report` (schema v12, §3.7 of
DATA_MODEL.md) with the detector/score versions that produced it — the provenance anchor the
registry gate reads back when later slices re-derive or export insight inputs.
