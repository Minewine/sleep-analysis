# Roadmap — SleepAnalysis

Five incremental phases. Each phase must be shippable and useful on its own; the measurement tiers ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md)) are binding in every phase.

Related: [Product vision](PRODUCT_VISION.md) · [MVP scope](MVP_SCOPE.md) (Phase 1 detail) · [Architecture](ARCHITECTURE.md) · [Data model](DATA_MODEL.md) · [Privacy & regulatory](PRIVACY_AND_REGULATORY.md)

---

## Phase overview

| Phase | Theme | Effort (part-time) | Measurement tiers introduced |
|---|---|---|---|
| 1 | Sleep recorder + basic report | 4–8 weeks | M1–M4 (Tier 1), clinical baseline |
| 2 | Acoustic intelligence | 3–6 weeks | M5–M6 (Tier 1), M7–M8 (Tier 2, toggle) |
| 3 | Data platform | 4–8 weeks | M10–M13 (device-reported), factors, export |
| 4 | Sleep intelligence | 4–6 weeks | Analysis over existing tiers only — no new claims |
| 5 | Experimental research | Open-ended | Promotion path for Tier 2 → Tier 1; M9 sonar evaluation |

## Phase 1 — Sleep recorder + basic report

**Goal:** a reliable nightly recorder and an honest morning report, with the clinical baseline stored immutably.

- Foreground-service audio capture, ring buffer, event clips only (no full-night audio)
- Swappable snore detector *(shipped: `energy-heuristic-v1-spectral` — energy/band heuristic + spectral flatness/harmonicity and breath-rhythm gates; the YAMNet-mapped model is Phase 2)*, snore + loud-noise events (cough/speech classification arrives with the model)
- Night timeline + clip playback + user labels
- Morning report: time in bed, snoring summary (time, share, tiers, score), acoustic event counts, integrity notes, baseline context
- Clinical baseline structured read-only entry (HUG numbers)
- Disclaimers, retention worker, per-night + delete-all
- Privacy posture: app-private storage; `INTERNET` exists only under the Phase 3 Garmin amendment ([PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) §1)

**Effort:** 4–8 weeks. Full detail, acceptance criteria, quality gates, milestones: [MVP_SCOPE.md](MVP_SCOPE.md).

**Success criteria:** 30-night dogfood with zero data loss; battery ≤ 10%/night; snore precision ≥ 0.80 / recall ≥ 0.70 on fixtures; report < 1 s; every displayed number provenance-traceable.

**Defers:** everything in Phases 2–5 below; explicitly not even experimental breathing indicators.

## Phase 2 — Acoustic intelligence

**Goal:** better and broader acoustic measurement, and the first experimental indicators — clearly labeled.

- **Model v1:** lightweight custom CNN on log-mel (int8, 1–3 MB), trained/fine-tuned on AudioSet snoring subsets + ESC-50 + Kaggle snore sets with home-noise augmentation; model swap via ModelRegistry; per-class precision/recall regression suite in CI
- **Event taxonomy expansion:** better cough/speech/loud-breathing; **experimental** `GASP_LIKE` and `BREATHING_PAUSE_CANDIDATE` event types (Tier 2, behind the experimental toggle)
- **Experimental indicators (Tier 2):** breathing-irregularity indicator (M7), respiratory-pattern indicator (M8) — per-hour, always badged, never in headline scores, never in exports as claims
- **Movement (Tier 1):** accelerometer via SensorEngine; movement index, restlessness events; sleep/wake estimation (M5) combining movement + sound stillness; `SLEEP_DURATION_ESTIMATE` becomes a real measurement
- **Per-user calibration from labels:** threshold tuning from Phase 1/2 user labels (no model retraining, no cloud)
- **Clip quality:** intensity-based selection, quota tuning; optional clip compression (AAC) at session end

**Effort:** 3–6 weeks.

**Success criteria:** v1 model beats v0 by ≥ 10 pp precision on fixtures; user label agreement ≥ 85% on snore clips over a 14-night sample; experimental indicators visibly and consistently labeled (audit of report payloads); sleep/wake estimate sanity-checked against self-reported wake episodes.

**Defers:** any promotion of M7/M8 to Tier 1 (Phase 5 validation only), sonar, wearable.

## Phase 3 — Data platform

**Goal:** bring the other data layers in — wearables, factors, clinical comparison, export.

- **Health Connect integration:** import sleep sessions (device-reported), nocturnal HR/HRV (M10/M11), vendor SpO₂ levels (M12, device-reported levels only), vendor sleep stages (M13, context-only) — all land as Measurement rows via `WearableSource`, `method = DEVICE_REPORTED`; Health Connect is on-device IPC (no INTERNET)
- **Garmin import — SHIPPED EARLY (first slice of this phase):** Personal API key in Android Keystore, on-demand pull of sleep/HRV/Body Battery/resting HR for one date, raw JSON in `raw_payload`, report labeled "Garmin estimate" (docs/GARMIN_INTEGRATION.md). Still open from this item: Health Connect, FIT/CSV file-import fallback
- **Lifestyle factor logging UI:** weight, tirzepatide dose, alcohol, caffeine, exercise, stress, pain, notes ([DATA_MODEL.md](DATA_MODEL.md) §3.6)
- **Clinical comparison view:** baseline metrics side by side with longitudinal trends under the §4 framing ("correlation and context — not equivalence")
- **Export:** user-initiated local CSV/JSON (full provenance columns) + PDF morning/period report for clinician sharing; exports pass the same ClaimPolicy filter and embed disclaimers
- **Scheduled start — SHIPPED EARLY (recording controls pass):** optional nightly schedule (off by default) with a bedtime notification-action start solving the Android 11+ while-in-use mic constraint (one tap = user interaction); exact alarms with an inexact ~10-minute fallback, auto-stop at the end time, skip-tonight, reboot re-arming; manual start remains the default

**Effort:** 4–8 weeks.

**Success criteria:** a wearable + factors timeline for 14+ consecutive nights; export opens correctly in Sheets/LibreOffice with provenance intact; comparison view copy passes the wording rules; network traffic still limited to the single Garmin host allowlist.

**Defers:** associations/insight ranking (Phase 4), any score combining phone + wearable data numerically.

## Phase 4 — Sleep intelligence

**Goal:** longitudinal analysis over the data platform — without adding new measurement claims.

- **Trends:** nightly/weekly/monthly views per measurement type, per source
- **Personal baseline bands:** rolling normal ranges per measurement (e.g., snoring time p25–p75) computed from the user's own history; deviations flagged as *observations* with conservative wording
- **Associations:** factor ↔ measurement correlations (e.g., alcohol units vs snore time), with lag options, minimum-sample thresholds, and explicit "correlation, not causation" framing; low sample sizes show "not enough data" instead of a number
- **Insight rules engine:** rule-based (deterministic) insights in the report — e.g., "This week's snoring ran above your usual range on 3 nights"
- **Reprocessing:** re-run newer models over stored clips → new Measurement rows (append-only provenance history)

**Effort:** 4–6 weeks.

**Success criteria:** an insight the user considers actionable, traceable end-to-end to Measurement rows; zero insights render without sufficient sample size; association view survives the wording audit ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §3).

**Defers:** predictive claims ("you will have a bad night"), anything resembling risk scoring.

## Phase 5 — Experimental research features

**Goal:** the only place where Tier 2 → Tier 1 promotion is attempted, and where speculative sensing lives.

- **Validation plan for breathing indicators (M7/M8):** prospective comparison window against a follow-up HUG sleep study — run the app nightly around the study, compare indicator vs. PSG-scored events with agreement statistics (sensitivity/specificity at personal thresholds); promotion only if the numbers justify it, otherwise demote or keep experimental indefinitely
- **Sonar evaluation (M9):** device-specific bench test (accuracy across distances/positions), pet/hearing safety review, battery impact — ship only behind an experimental opt-in, or reject
- **Optional feature cache / on-device personalization research:** feature-store experiments for future model work; per-user fine-tuning beyond thresholds stays research-grade until proven
- **Clinician share links / networked anything:** only if a concrete need arrives — this would be the first feature to add a networked host beyond the Garmin allowlist; it must pass a full privacy review against [PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) before design starts

**Effort:** open-ended, research-paced.

**Success criteria:** every feature entering this phase has a written validation plan *before* implementation; promotions/demotions follow the change control in [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §7; rejected ideas are documented, not silently dropped.

## Cross-phase gates (every release)

1. Measurement tiers unchanged or changed only via §7 change control.
2. No networked host beyond the single Garmin Personal API allowlist (`personal.connectapi.garmin.com`), until an approved feature deliberately changes that through privacy review.
3. Battery ≤ 10%/night and thermal gates still pass on the reference device.
4. Disclaimer copy audit: forbidden terms ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §3) absent from non-disclaimer strings.
5. Data-loss drill: forced process-death recovery still works.
