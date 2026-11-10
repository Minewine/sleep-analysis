# Evidence base — every encodable parameter, its value, and its citation

The single table of record for every constant the app encodes: parameter → value/criterion →
citation (Author, year, venue) → evidence strength → the allowed-claim language. Machine-checked
by `EvidenceBaseDocTest` (the same file-walk mechanism as `ClaimPolicyTest`): a pinned literal
that drifts from the code — or a playbook evidence key without a row here — is a build failure.

Values marked **APP CONVENTION** are not literature thresholds; they are the app's own honest
choices and are labeled so in code KDoc too. Nothing in this table is a population norm handed
to the user as a personal trigger — personal triggers are always the user's OWN distributions
(docs/INSIGHTS.md §Eligibility, §Baseline method). See also
[MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) (the binding tier system) and
[NIGHT_REPORT.md](NIGHT_REPORT.md) §Night validity (the grade matrix these rows summarize).

| key | parameter | value / criterion | citation (Author, year, venue) | evidence strength | allowed-claim language |
|---|---|---|---|---|---|
| `shortfall_warn` | capture-window shortfall WARNING | > 0.10 of expected windows missed | App convention (mirrors the report's capture-health heuristics) | convention | "some capture evidence is missing" |
| `shortfall_critical` | capture-window shortfall CRITICAL | > 0.40 of expected windows missed | App convention | convention | "the night's capture is severely incomplete" |
| `gap_warn` | unexplained (UNCAPTURED) quiet-gap minutes | > 15 min WARNING; > 60 min CRITICAL | App convention | convention | data-quality wording only |
| `clipped_warn` | clipped-clip rate | > 0.10 WARNING; > 0.30 CRITICAL | App convention | convention | data-quality wording only |
| `ambient_relative` | ambient noise trigger | tonight's ambient P95 robust z > 2.5 vs the rolling prior-night distribution (needs ≥ 5 prior nights) | App convention; WHO night-noise guidance is COPY-ONLY | convention | relative only — "louder than your usual room" |
| `noise_absolute_never` | absolute noise levels | never encoded, never displayed | WHO 2009 (Night Noise Guidelines for Europe) — copy-only reference, never a threshold | n/a | copy-only mention; **no absolute dB claims anywhere** |
| `garmin_stale` | vendor payload freshness | > 24 h old = distrust flag | App convention | convention | "stale import" data-quality wording |
| `stage_sum_tolerance` | reported vs window sleep-time consistency | ±10% stage-sum tolerance | App convention | convention | data-quality wording only |
| `tst_tolerance` | reported-vs-window sleep-time tolerance | ±45 min (vendor validation bias ~+40 min TST) | Schyvens 2025; Chinoy 2021 | moderate | honesty footnote, never a user threshold |
| `stage_kappa` | Garmin stage agreement with sleep-lab stages | κ 0.21–0.54 (fair-to-moderate); deep-sleep LoA ±2.5 h | Schyvens 2025; Chinoy 2021; Miller 2022 | moderate (validation literature) | surfaced as an "estimates" footnote only; never stage-truth claims |
| `rmssd_relative` | wrist rMSSD interpretation | baseline-relative ONLY; nightly ICC 0.79–0.89 | Lenz 2026; Nuuttila 2022 | moderate | "relative to your own baseline" — **no absolute HRV norms** |
| `spo2_bounds` | wrist SpO₂ plausibility | 70–100% accepted; < 90% distrust flag; device error ±2–3 pp | Jung 2022; Rajakariar 2024 | moderate | vendor level context; **no desaturation counting** (M12/M17) |
| `hr_bounds` | wrist heart-rate plausibility | 30–220 bpm accepted | App convention (validator bound) | convention | plausibility validator only |
| `rmssd_bounds` | wrist rMSSD plausibility | 2–300 ms accepted | App convention (validator bound) | convention | plausibility validator only |
| `low_snore_floor` | trivial-snore suppression | snore share < 0.01 of capture suppresses snoring-metric rules/excursions | Klaus 2021 (IJERPH) | moderate | suppression rule, never surfaced as a number |
| `snore_detection_validation` | phone-mic snoring detection honesty | PPV collapses for light snorers; reported accuracy ~95% for loud snoring in validated settings | Klaus 2021; Chiang 2021 | moderate | honesty context in docs; copy says "your recordings suggest" |
| `sri_min_days` | Sleep Regularity Index minimum | ≥ 5 valid days; SRI = 100 × mean 24 h epoch-state agreement | Windred 2024 (Sleep) | strong (method) | "how steady your timing is" — association wording only |
| `sjl_convention` | social-jetlag flag | > 1 h between free/weekday midpoints = flagged CONVENTION | Roenneberg 2012 (Current Biology) | moderate (convention flag) | approximate; the app cannot know the work schedule |
| `alcohol_flag` | ALCOHOL factor flag | logged flag only; intended semantics ≥ 2 drinks; **no safe-hours cutoff exists** | Gardiner 2025; Ebrahim 2013 | moderate | association wording; never a safe-amount claim |
| `caffeine_cutoff` | CAFFEINE_AFTER_14 flag | 14:00 default cutoff; meta-analytic context TST −45 min | Gardiner 2023 (Sleep Medicine Reviews) | moderate | research context in docs; the user's own deltas in copy |
| `exercise_context` | EXERCISE flag | flag only (no timing/intensity recorded); vigorous exercise < 1 h before bed is the literature's sensitive window | Stutz 2019 (Sports Medicine) | moderate | association only; the mechanism is NOT tested |
| `stress_threshold` | STRESS high level | ≥ 4 on the user's own 1–5 scale | Kim & Dimsdale 2007 (Psychosomatic Medicine) | moderate | association wording only |
| `wwc_floor` | N-of-1 minimum usable points | ≥ 5 usable phase points (What Works Clearinghouse single-case standard) | WWC 2010 (IES); Goodwin 2024 | strong (method) | methodology row; never surfaced as a number |
| `n1_phase_length` | N-of-1 phase length | ≥ 7 usable phase nights to a verdict (default planned = 7; WWC floor ≥ 5 usable points) | Goodwin 2024 (single-case design); app convention | moderate | "try this for a week and watch your own number" |
| `n1_verdict` | N-of-1 verdict trigger | phase median outside the start band beyond 2 robust SD (robust z magnitude > 2, z = (median − median₀)/(1.4826·MAD₀), floor-limited) | robust-statistics method; Goodwin 2024 (context) | moderate | "changed beyond your usual night-to-night variation" — **never "improved"** |
| `n1_confound` | N-of-1 confound exclusion | nights carrying a logged factor OTHER than the experiment's own are excluded from the verdict values (listed in resultJson) | Goodwin 2024 (single-case design) | moderate | methodology row |
| `n1_aba` | N-of-1 A-B-A confirmation | optional return-to-baseline phase; phase-2 median back inside the start band → MODERATE confidence ("consistent with the change"), else stays LOW ("effect not confirmed") | Goodwin 2024 (single-case design) | moderate | methodology row |
| `escalation_share` | escalation acoustic arm, share trigger | snore share > 0.25 of captured analysis time | App convention (STOP-BANG "loud snoring" is a self-report item — Chung 2012; no acoustic threshold is cited) | convention | "your recordings show…" data wording |
| `escalation_bout` | escalation acoustic arm, bout trigger | longest snore bout > 10 min | App convention (same basis) | convention | data wording |
| `escalation_consecutive` | escalation acoustic arm, persistence | 3 or more consecutive eligible nights (quality grade A/B) meeting both acoustic criteria | App convention | convention | data wording |
| `stopbang` | escalation self-report questions | three screening questions (S: loud snoring; O: witnessed pauses/gasping; T: daytime sleepiness) derived from the STOP-BANG screening tool | Chung 2012 (Anesthesiology) | strong (screening tool) | screening conversation aid; **evaluation, never diagnosis, never a score** |

## Playbook evidence rows

One row per [PlaybookAction] evidence key (drift-tested: every key below is asserted to appear
here; the action catalog lives in docs/INSIGHTS.md §Playbook). Every instruction copy stays
inside its row's allowed-claim language: suggestive ("try…, watch your own number"), never a
cure, never a promised outcome.

| key | action | value / criterion | citation (Author, year, venue) | evidence strength | allowed-claim language |
|---|---|---|---|---|---|
| `edinger_2021` | stimulus_control; wind_down | awake > 20 min → get up, dim light, return when sleepy; 30–60 min dim low-stimulation buffer | Edinger 2021 (J Clin Sleep Med, AASM clinical guideline) | strong | suggestive — a guideline-recommended behavioral technique, framed as "worth trying" |
| `windred_2024` | fixed_wake | fixed wake time ±30 min, all week | Windred 2024 (Sleep) | moderate (observational) | suggestive; regularity associations only |
| `roenneberg_2012` | morning_light | 10–30 min outdoor light within ~1 h of waking | Roenneberg 2012 (Current Biology, circadian context) | observational | suggestive; circadian-alignment context |
| `balban_2023` | slow_breathing | 5 min slow breathing, ~6 breaths/min | Balban 2023 (Cell Reports Medicine, randomized trial) | moderate (RCT) | suggestive; physiology outcomes reported in research, promised nowhere |
| `rusch_2019` | mindfulness | regular mindfulness practice | Rusch 2019 (meta-analysis) | moderate | suggestive |
| `lan_2017` | bedroom_temp | ~18–20 °C for most adults | Lan 2017 (thermoregulation review); Baniassadi 2023 | moderate | suggestive; comfort range, not a prescription |
| `dutheil_2021` | nap_limit | nap ≤ 20 min, end by 15:00; skip naps when sleep is already disturbed | Dutheil 2021 (review) | moderate | suggestive |
| `brosnan_2024` | screens_optional | screen-use change is OPTIONAL — evidence is weak | Brosnan 2024 | weak | **optional, never promised** — the copy must say so |
| `ravesloot_2017` | side_sleeping | try side-sleeping for a week and watch the snore share | Ravesloot 2017 (position and sleep) | moderate | suggestive; the app has NO position data — observe, never infer |
| `camacho_2015` | myofunctional | 5 min tongue/throat exercises daily, 8–12 weeks | Camacho 2015; Camacho 2018 (reviews) | moderate | suggestive; weeks-long horizon stated |
| `souza_2017` | bed_elevation | raise bed head 7.5–30° | Souza 2017; Maniaci 2025 | weak–moderate | suggestive |
| `mazza_2019` | nasal_dilator | external nasal dilator for a week | Mazza 2019 (randomized trial) | moderate | suggestive |
| `ebrahim_2013` | no_evening_alcohol | skip evening alcohol for a week, watch the snore share | Ebrahim 2013 (Alcoholism: Clinical & Experimental Research) | moderate | suggestive; **no safe-amount claim** |
| `peppard_2000` | weight_context | ~2.6% weight change went with roughly a 1% shift in breathing-related sleep measures — CONTEXT ONLY, not experiment-verifiable in this app | Peppard 2000 (cohort study) | observational | context only; the app measures snoring, not weight |

## NEVER-CLAIMS

The list below mirrors docs/INSIGHTS.md §Playbook and docs/MEASUREMENT_RELIABILITY.md. Nothing
in the app may cross these lines; the doc tests pin them so drift is a build failure:

1. **No diagnosis** of any condition, no disease language, no clinical staging — the escalation
   card is a screening conversation aid (evaluation, not diagnosis).
2. **No AHI** estimates, no RDI, no respiratory-event index anywhere (Tier 3, M15).
3. **No apnea detection** — audible pauses are never apneas (the two hard equivalences, §2 of
   MEASUREMENT_RELIABILITY.md).
4. **No desaturation counting** — SpO₂ renders as vendor levels only, never events (M12/M17).
5. **No absolute dB** claims — phone audio is relative dBFS; absolute SPL comparisons and the
   WHO night-noise guidance stay copy-only.
6. **No absolute HRV norms** — rMSSD is interpreted relative to the user's own baseline only.
7. **No sleep restriction / sleep-consolidation suggestions** — those interventions need a
   professional; the playbook never suggests them.
8. **No cure language** — playbook items are suggestions with cited evidence, never cures,
   treatments, or promised outcomes.
9. No population threshold becomes a personal trigger: personal triggers are the user's OWN
   distributions; the app-convention rows above are labeled as conventions.
