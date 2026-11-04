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

**Night 2–3 (drills, not night 1):** force-stop the app mid-night (swipe away / `adb shell am force-stop com.sleepanalysis`) → next launch must show the **interrupted-night banner** (with the saved-event count); tapping *Finalize & view report* must produce a **RECOVERED** report covering up to the kill. Then one night *without* the battery-optimization exclusion to see the real difference.

**Exit criteria for P0:** 3 consecutive nights with COMPLETED (or drilled-RECOVERED) sessions, battery ≤ 10 %/night on at least one, ≥ 10 labeled events, zero data loss.

## P1 — Dogfood + hardening (1–2 weeks alongside nightly use)

1. **30-night dogfood** with a tiny nightly log (battery %, anomalies) — Phase 1's definition-of-done gate ([MVP_SCOPE](MVP_SCOPE.md) §7).
2. **Garmin live import** (same period): enter the rotated key, import a date, check the four endpoint HTTP statuses in Settings — validates the `?date=` assumption and real payload shapes against the parsers; raw JSON makes remapping lossless.
3. ~~**CI + lint rules**~~ — **done (2026-11-04)**: GitHub Actions runs build + unit tests + Android lint + the four policy gates (merged-manifest permission allowlist — now 9 declared permissions + 2 documented library additions, forbidden-terms check, repo-hygiene check, network-host allowlist) plus ruff/pytest; see `.github/workflows/ci.yml` and [ARCHITECTURE.md](ARCHITECTURE.md) §6.6.
4. **Report snapshot persistence** (DATA_MODEL §3.7). ~~Retention decision for `raw_payload`~~ — **done (2026-11-04)**: raw payloads now follow session retention in the daily worker (DATA_MODEL §3.9).
5. **DB trigger test** for clinical immutability (in-memory Room test against the `clinical_baseline` triggers) — the only Phase 1 safety mechanism with no automated coverage.
6. **Retention-settings UI** — the plumbing exists (`UserPreferences.setRetention`, worker already reads it); only the Settings screen section is missing.

## P2 — Phase 2 proper (acoustic intelligence; ~3–6 weeks)

1. **Fixture eval harness** (labeled WAV set → precision/recall asserted in tests) — needed before any model claim; your labeled events from P0 dogfood are the seed.
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
