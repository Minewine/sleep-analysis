# Dogfood verification checklist ("The Gate")

Claims the code makes that no automated test can verify — they need real nights on real
hardware. Log each result in `STATUS.md` (date + pass/fail + one line of evidence). One item
per night is fine; several combine naturally. Baseline: 460 unit tests + 36 pytest green at
the time this list was written (2026-11-08).

## Carried from NEXT_STEPS P0/P1 (never verified on device)

### 1. Scheduled start survives real Doze
- [ ] Evening: arm the schedule, lock the phone, unplug, screen off, don't touch it.
- [ ] Morning: did the bedtime notification fire at the scheduled minute (± a few minutes)?
- [ ] After tapping Start: does the FGS notification show "recording" and does the morning
      report contain events (i.e. the tap granted mic access — no silent zero-capture night)?
- Pass: notification fired, capture non-empty, `session_log` shows `recording_start
  source=schedule`.

### 2. Battery ≤10% over ~8 h (the documented claim is a claim, not a measurement)
- [ ] Note battery % at start (use Settings → Battery, or `adb shell dumpsys battery`).
- [ ] Same after the night (device unplugged the whole time).
- [ ] Pass: drain ≤10% and `capture_stats.thermal_throttle_events` is in the report for
      context. Record the actual number in STATUS.md either way.

### 3. Real process death → INTERRUPTED → RECOVERED
- [ ] Start recording, wait for capture to begin, then `adb shell am kill com.sleepanalysis`
      (or let the OS kill it — force-stop from recents does NOT count, it's user-initiated).
- [ ] Expected: the high-priority interrupted notification appears; tapping Resume continues
      the same session; the report flags "resumed after interruption"; finalize counts both
      engines' windows.
- [ ] Also try NOT tapping Resume for 30+ min: Home shows the interrupted banner with saved
      data; finalize produces a RECOVERED session with no share/score rows.

### 4. Retention worker actually deletes
- [ ] Settings: retention → 1 day, wait for the worker window (or trigger via debug knob).
- [ ] Pass: old sessions, their clips AND session logs are gone; last night untouched.

### 5. Morning report with real history
- [ ] After ≥4 recorded nights: history list, previous-night comparison, 7-day trend — do
      they render sanely (no zeroes, version flags where expected)?

## New in the 2026-11-08 change set (verify with the first nights)

### 6. Garmin "not ready yet" (404) + morning auto-retry
- [ ] Import a night EARLY in the morning (before Garmin finishes processing, e.g. right
      after waking): expect the "Garmin hasn't processed this night yet — retry later"
      state, NOT a generic error.
- [ ] Leave the app alone: within the not-ready retry budget (4×, 1 h apart) the import
      should land by itself later in the morning. Pass: vendor rows appear without a manual tap.

### 7. Label recompute (snore-score-v3)
- [ ] On any completed night, label a LOUD snore as COUGH: the report's snore time/share/score
      drop immediately and the measurement rows carry `label_corrected=true` (check via
      `uv run tools/export_night_csv.py --session N --out /tmp/x` → measurements CSV notes).
- [ ] Re-save the Nov-8 session 5 COUGH labels (the 01:06 events): its stored 42/100 score
      should drop and stay dropped (trend + previous-night comparisons now see corrected rows).

### 8. Start-delay skip + night-stop confirm
- [ ] With start delay > 0: tap start → "Waiting" card → "Start capture now" → capture
      begins immediately (session log shows `start_delay_skipped` with planned/actual times).
- [ ] During night hours (schedule on, before the scheduled end): tapping stop shows the
      "Still up?" dialog; "Keep recording" continues; "Stop recording" finalizes. After the
      scheduled end (or schedule off): no dialog.

## Optional / hard to trigger

### 9. Thermal throttle path
- [ ] Only if it happens naturally (phone under a blanket on a charger): report should note
      throttling and events should survive (stride-aware emission). Don't engineer it.
