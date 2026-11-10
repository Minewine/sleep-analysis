# Insights — personal baselines, regularity, and rule-based suggestions

Schema v13 (docs/DATA_MODEL.md §3.7/§3.8/§3.12). The Insights screen turns the recorded nights into
*personal* observations: robust bands over the user's own eligible nights, an approximate
sleep-regularity row, and a fixed set of deterministic rules. Everything here is pure Kotlin
(`domain/insights/`) over stored evidence, assembled by `SessionRepository.buildInsights()`.

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

## Playbook — evidence-graded actions and N-of-1 experiments (schema v13)

Concrete, suggestive changes tied to what the rules found. Every action carries its evidence
row in [EVIDENCE_BASE.md](EVIDENCE_BASE.md) (evidence key drift-tested), the metric that would
verify it, and — when the app can honestly run the check — an N-of-1 experiment row
(`sleep_experiment`, docs/DATA_MODEL.md §3.12). Copy keys are string-resource names
(InsightsDocTest drift-tests them). Tone contract: **a suggestion, never a cure** — "try this
for a week and watch your own number".

**Ranking** (pure `Playbook.rank`, APP CONVENTION): rule confidence (MODERATE > LOW) × recency
(related rule fired within the current 14-eligible-night window > not), actions whose related
rule is dismissed drop out, context-only actions never rank. When no rule fired, the starter
pair `fixed_wake` + `stimulus_control` leads.

**Experiment standards** (values in [EVIDENCE_BASE.md](EVIDENCE_BASE.md) N-of-1 rows, never
changed here): ≥ 7 usable phase nights to a verdict (WWC 2010 floor ≥ 5); verdict = phase
median beyond 2 robust SD of the START baseline band (\|z\| > 2); verdict copy is "changed
beyond your usual night-to-night variation" + direction, confidence LOW — **never "improved"**.
Nights carrying a logged factor OTHER than the experiment's own are confound-excluded from the
verdict (the excluded night ids are listed in `resultJson`). Exactly ONE experiment may be
ACTIVE (tracker-enforced; the UI repeats it). After a change verdict, an optional A-B-A
return-to-baseline phase may upgrade the verdict to MODERATE ("consistent with the change")
or leave it LOW ("effect not confirmed"). Requires a READY baseline at start.

| `stimulus_control` | Sleep quality | edinger_2021 | garmin_awake_min | yes | Awake > 20 min? Get up, dim light, return when sleepy. (AASM guideline, strong) |
| `fixed_wake` | Sleep quality | windred_2024 | score | yes | Fix wake time ±30 min, all week. |
| `morning_light` | Sleep quality | roenneberg_2012 | garmin_awake_min | yes | 10–30 min outdoor light within ~1 h of waking. |
| `wind_down` | Sleep quality | edinger_2021 | garmin_awake_min | yes | 30–60 min dim, low-stimulation buffer before bed. |
| `slow_breathing` | Sleep quality | balban_2023 | rmssd_ms | yes | 5 min slow breathing, ~6 breaths/min. (Balban 2023 RCT) |
| `mindfulness` | Sleep quality | rusch_2019 | rmssd_ms | yes | Regular mindfulness practice. (Rusch 2019 meta) |
| `bedroom_temp` | Sleep quality | lan_2017 | garmin_awake_min | yes | ~18–20 °C for most adults. |
| `nap_limit` | Sleep quality | dutheil_2021 | garmin_awake_min | yes | Nap ≤ 20 min, end by 15:00; skip naps when sleep is already disturbed. |
| `screens_optional` | Sleep quality | brosnan_2024 | garmin_awake_min | yes | Screen changes are OPTIONAL — weak evidence, never promised. |
| `side_sleeping` | Snoring | ravesloot_2017 | snore_share | yes | Try side-sleeping for a week and watch your snore share. The app has NO position data — observe for yourself. |
| `myofunctional` | Snoring | camacho_2015 | snore_share | yes | 5 min tongue/throat exercises daily, 8–12 weeks. |
| `bed_elevation` | Snoring | souza_2017 | snore_share | yes | Raise the bed head 7.5–30°. |
| `nasal_dilator` | Snoring | mazza_2019 | snore_share | yes | External nasal dilator for a week. |
| `no_evening_alcohol` | Snoring | ebrahim_2013 | snore_share | yes | Skip evening alcohol for a week and watch your snore share. Own factor: ALCOHOL. |
| `weight_context` | Snoring | peppard_2000 | snore_share | NO — context only | ~2.6% weight change went with roughly a 1% shift in breathing-related measures in cohort research. The app measures snoring, not weight. |

**Playbook never-list** (drift-pinned by InsightsDocTest): no sleep restriction or
sleep-consolidation suggestions (professional-guided only), no AHI, no apnea detection,
no desaturation counting, no cure language — same line as the guardrails below and the
NEVER-CLAIMS list in [EVIDENCE_BASE.md](EVIDENCE_BASE.md).

## Escalation — when to talk to a clinician

A pure rule over the SAME eligible night set the insights ran on
(`EscalationRule.evaluate`), plus the user's once-collected, editable self-report. Either arm
triggers the card:

- **Acoustic arm** (APP CONVENTIONS — no literature threshold is encoded): ≥ 3 consecutive
  eligible nights with snoreShare > 0.25 AND longest bout > 10 min. A night failing either
  criterion (or missing values) breaks the streak. CONVENTION — streak adjacency runs over the
  ELIGIBLE SEQUENCE, not the calendar: a night that never became eligible (grade C/D, version
  mismatch, unmeasured) is simply absent and does NOT break the streak; only an eligible night
  failing the acoustic criteria resets it.
- **Self-report arm**: any YES among three STOP-BANG-derived screening questions (S: snoring
  louder than talking or heard through closed doors; O: pauses or gasping witnessed during
  sleep; T: daytime sleepiness most days) — collected once under the card, persisted in
  DataStore, editable. Chung 2012 (Anesthesiology) is the screening-tool citation; the app
  never computes a STOP-BANG score.

Copy contract: "worth discussing with a clinician — snoring is common and usually harmless,
but these signals warrant a professional opinion". Evaluation, never diagnosis: no condition
is named, no test result is implied, the questions are screening context only.

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
