# Detector eval runbook — labels → precision/recall → tuning

The loop that turns nightly recordings into detector evidence. Everything here runs against
labeled clips only; `fixtures/` is gitignored (real audio never enters the repo —
[PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) §2).

## 1. Label clips (in the app, after each night)

Report → tap an event → play the clip → apply one of
`SNORE / COUGH / SPEECH / MOVEMENT / OTHER_NOT_SNORE`. Unlabeled clips are exported but
excluded from metrics. Aim: every clip labeled before exporting.

## 2. Export labeled fixtures (phone connected)

```bash
uv run tools/export_fixtures.py --session 1 --out fixtures   # --min-labels 1 to require labels
```

Pulls the DB + clip WAVs over adb into `fixtures/` with a `manifest.json`
(session, event metadata, labels). Re-run per night — the manifest accumulates the labeled set.

## 3. Run the eval

```bash
./gradlew evalFixtures -PsleepFixturesDir=$PWD/fixtures
```

`FixtureEvaluator` replays every clip through the live pipeline (identical window math) and
reduces per-window snore calls to one clip-level decision (snore-window ratio ≥ 0.5), then
prints per-class precision/recall. One-vs-rest: a clip labeled `SNORE` is positive for the
SNORE row and negative for every other row.

**Gate (MVP_SCOPE):** SNORE precision ≥ 0.80, recall ≥ 0.70 on the labeled set.

## 4. Tune from evidence (only after 3)

Thresholds live in `DetectorConfig` (gate margin, spectral flatness max, harmonicity min,
rhythm window). Every event persists its decision inputs (`features_json` incl.
`spectral_flatness_mean` / `harmonic_ratio_mean` since 2026-11-05), so a failing gate is
diagnosed by comparing the feature distributions of labeled true/false positives — never by
guessing. Any threshold change: re-run the eval, record the numbers here, bump the detector
changelog (MEASUREMENT_RELIABILITY §7.1).

## Current status

- **2026-11-05 — first measured eval** (session 1, night 2026-11-04→05; 17 labeled clips,
  floor pre-roll −84.12 dBFS): SNORE precision **0.50**, recall **0.43** (3 TP / 3 FP / 4 FN);
  COUGH 0.33/0.50; OTHER 0.17/0.50; MOVEMENT 0/0. Gate 0.80/0.70 **unmet** — expected at this
  label count. Known FPs: coughs called snoring (no cough class until Phase 2) and creaky-bed
  movement (harmonic ratio 0.14–0.70 — two creaks separable, two inside the snore band).
- **Do not tune below ~30 labels per class**: the distributions above overlap; a threshold
  change fitting 7 snores would overfit. Label first, re-run, then retune.
- Harness fidelity fix shipped today: replay's noise-floor ring is pre-rolled with the
  session's recorded floor (`floor_dbfs` in the manifest) — without it every clip replays
  cold and recall is 0 for ALL classes regardless of detector quality (measured before/after).
