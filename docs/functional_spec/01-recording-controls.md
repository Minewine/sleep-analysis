# 1. Recording controls

**Scope:** `app/src/main/java/com/sleepanalysis/monitoring/service/` (MonitorService, MonitorState, StartIntentPolicy, InterruptedNotification, InterruptionStopSequence, BatteryStatus, ThermalPolicy), `monitoring/schedule/` (RecordingScheduler, ScheduleTimes, SchedulePolicy, the three receivers, BedtimeNotification), `domain/policy/` (StartDelayPolicy, NightStopPolicy, MissedNightPolicy), `data/prefs/UserPreferences.kt`, `ui/home/`, and the recording-control part of `ui/settings/SettingsScreen.kt`; the unit tests under `app/src/test/java/com/sleepanalysis/`. Audio engine = section 2, detector = section 3, database = section 4. Describes the working tree (includes the uncommitted Home schedule-row change, reviewed in 1.8).
**Requirement prefix:** REC — rows in 1.3 are numbered REC-01, REC-02, …
**Status values:** `tested` = a unit/instrumented test asserts it (cite it) · `device-verified` = evidence from a real night or device run exists (cite where) · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

## 1.1 What the user gets

1. Start one overnight recording from Home with one tap; missing permissions are requested first, then a persistent foreground notification runs with an elapsed timer [REC-02, REC-57, REC-58].
2. Stop from Home, from the notification's Stop action, or automatically at the scheduled end time; every explicit stop finalises the night as COMPLETED and opens its report [REC-22, REC-23, REC-25, REC-26].
3. A Stop tap during the night asks "Still up?" when the schedule is on and a scheduled end is less than 12 h ahead [REC-52, REC-53].
4. Optional start delay (Off / 15 / 30 / 45 / 60 / 90 min): time in bed counts from the tap, audio capture begins later, the notification counts down, "Start capture now" skips the rest [REC-07 … REC-16].
5. After a process death nothing records by itself: a high-priority notification offers Resume, Home shows an interrupted banner with Resume and "Finalize & view report" actions (the Home Resume button was added in consolidation T8, defect D6), and Start stays disabled until the night is resolved [REC-27 … REC-34].
6. Master switch: off = no recording can start (manual, scheduled or resume); an active night is never stopped [REC-35].
7. Optional nightly schedule: bedtime notification with Start recording / Skip tonight, auto-stop at the end time, re-armed across reboot, clock and timezone changes, settings changes and app opens [REC-36 … REC-48, REC-61, REC-62].
8. Skip tonight from Home or Settings; every skip is kept in a persistent history [REC-43, REC-44, REC-45].
9. Home missed-night banner for the night before: nothing recorded, or a short night with the in-bed time [REC-49, REC-50, REC-51].
10. Home schedule row: the next armed start with Skip tonight, or an explicit "Scheduled start is off" row with a shortcut to Settings [REC-54, REC-55].
11. Pre-flight card "Before tonight" (microphone, notification permission, battery exemption with a Fix button, free storage) and the last-night card with its actual capture window "Captured X – Y" [REC-56, REC-59, REC-60].

## 1.2 How it works today

### 1.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| `MonitorService` | `monitoring/service/MonitorService.kt` | Foreground service (type microphone): start/stop commands, begin/end gates, start delay, wake lock, thermal listener, heartbeat, notification 41 |
| `MonitorState`, `MonitorStateHolder`, `canBeginCaptureAfterDelay`, `canSkipStartDelay`, `canStartNewSession` | `monitoring/service/MonitorState.kt` | In-memory lifecycle (a singleton StateFlow, never persisted) and three pure guards |
| `StartCommand`, `startCommandFor`, `ResumeDecision`, `resumeDecision` | `monitoring/service/StartIntentPolicy.kt` | Pure decision tables for delivered intents and the Resume tap |
| `InterruptedNotification` | `monitoring/service/InterruptedNotification.kt` | High-priority notification 1043 with a Resume action; the notification → best-effort promotion → stop ordering lives in `monitoring/service/InterruptionStopSequence.kt` · `run` |
| `ThermalPolicy` | `monitoring/service/ThermalPolicy.kt` | Throttled iff thermal status ≥ MODERATE |
| `BatteryStatus`, `format`, `read` | `monitoring/service/BatteryStatus.kt` | `batt=NN% charging=…` field for heartbeat and stop log lines |
| `RecordingScheduler` | `monitoring/schedule/RecordingScheduler.kt` | Arm / cancel the two alarms, next start, skip tonight |
| `ScheduleTimes` | `monitoring/schedule/ScheduleTimes.kt` | Pure schedule math (next occurrence, skip marker by intended wake day) and the pure gate for posting the bedtime notification (`monitoring/schedule/SchedulePolicy.kt` · `shouldPostBedtimeNotification`) |
| `ScheduledStartReceiver` | `monitoring/schedule/ScheduledStartReceiver.kt` | Bedtime alarm: posts the bedtime notification or handles its Skip action; always re-arms |
| `ScheduledStopReceiver` | `monitoring/schedule/ScheduledStopReceiver.kt` | End alarm: stops an active session or removes a stale bedtime notification; always re-arms |
| `BootReceiver` | `monitoring/schedule/BootReceiver.kt` | Re-arms on boot / timezone / clock change; never records |
| `BedtimeNotification` | `monitoring/schedule/BedtimeNotification.kt` | High-priority notification 1042 with Start / Skip actions |
| `StartDelayPolicy` | `domain/policy/StartDelayPolicy.kt` | Delay bounds and capture-start math |
| `NightStopPolicy` | `domain/policy/NightStopPolicy.kt` | When a manual stop asks for confirmation |
| `MissedNightPolicy` | `domain/policy/MissedNightPolicy.kt` | Verdict for the night before today (banner input) |
| `UserPreferences`, `PreferenceBounds` | `data/prefs/UserPreferences.kt` | DataStore "settings": switches, schedule, skip, delay (~~debug flags~~ — removed in consolidation T3) |
| `HomeViewModel`, `canFinalizeInterrupted`, `PreFlightChecklist` | `ui/home/HomeViewModel.kt` | Home state: start-up safety net, pre-flight, schedule row, stop confirmation, finalize |
| `HomeScreen`: `StatusCard`, `ScheduleRow`, `ScheduleOffRow`, `MicIndicator`, `PreFlightCard`, `InterruptedCard`, `MissedNightBanner`, `LastNightCard`, `captureWindowLabel`, `scheduleRowState` | `ui/home/HomeScreen.kt` | Home UI |
| `SettingsViewModel`, `START_DELAY_OPTIONS`, `StartDelayRow`, `TimeRow`, `ScheduleTimePickerDialog` | `ui/settings/SettingsScreen.kt` | Two switches, time rows, start-delay chips, warnings, debug switches (the ViewModel class lives in this file) |

### 1.2.2 Flows

Symbol chains below are written File.symbol; the canonical citations are listed under each flow.

#### Flow: manual start

1. HomeScreen.onStartClick: pre-flight says the mic is missing → system permission dialog (a denial ends the flow). Mic granted and (API ≥ 33 and notifications not granted) → notification permission dialog with pendingStart; when it returns the start is issued whatever the answer. Otherwise → HomeViewModel.startRecording at once.
2. MonitorService.start → startForegroundService with ACTION_START and start_source=manual.
3. MonitorService.onStartCommand → startCommandFor → START_NEW_SESSION → beginRecording(existingSessionId = null, source) — the gates are the table in 1.2.3.
4. beginRecording: gates → Starting → row opened → wake lock, thermal listener, log recording_start → delay branch or immediate capture.
5. beginCaptureLocked: engine started (section 2) → heartbeat → Recording → notification 41 re-anchored to the session start.
6. Home shows Recording: red mic dot, "Recording… microphone active", elapsed since the session start.

#### Flow: start delay

1. beginRecording: captureStartAt = session.startedAt + delay minutes; remaining = captureStartAt − now.
2. remaining > 0 → log start_delay; state Delayed; count-down notification 41; the wait (delay job) runs outside the begin/end mutex, so Stop and the scheduled end stay responsive.
3. remaining ≤ 0 → beginCaptureLocked at once.
4. Natural expiry → beginCapture → under the mutex: canBeginCaptureAfterDelay false → log capture_start_skipped and return; true → beginCaptureLocked.
5. "Start capture now" → HomeViewModel.startCaptureNow → MonitorService.startNow → skipStartDelay: the wait is cancelled and capture is stamped at the tap.
6. Stop during the delay → endRecording(COMPLETED, source) → log start_delay_cancelled → row finalised with zero capture → Finished.

#### Flow: stop and finalise

1. Triggers: Home Stop (after the confirmation rule, only in Recording), the Stop action of notification 41, ScheduledStopReceiver (source schedule), or a capture error (status INTERRUPTED).
2. endRecording under the mutex: cancel heartbeat → engine.stop() (section 2) → thermal listener and wake lock released → log recording_stop (only when an engine existed) → finalizeSession(status, stats).
3. finalizeSession true → Finished(sessionId); false or exception → Idle (log finalize_skipped or finalize_failed).
4. Always: notification removed, stopSelf.
5. Home collects Finished → consumeFinished (→ Idle) → opens that session's report.

#### Flow: process death, sticky restart, resume

1. The process dies; the row stays open (status RECORDING) with whatever the heartbeat saved.
2. START_STICKY restart with a null intent → SURFACE_INTERRUPTED → surfaceInterruptionOrStop → InterruptionStopSequence.run: notification 1043 → startInForeground inside runCatching → stopForeground(REMOVE) + stopSelf. No capture.
3. HomeViewModel.init: state Idle and an open row → Interrupted(id, startedAt) + saved-event count → InterruptedCard.
4. "Resume recording" (notification 1043) → ACTION_RESUME → resumeDecision RESUME → beginRecording(existingSessionId): stats carried, resumedAfterInterruption = 1, delay recomputed from the session start, 1043 cancelled.
5. Or "Finalize & view report" (Home) → canFinalizeInterrupted → finalizeOpenAsRecovered → RECOVERED → report.

#### Flow: scheduled night

1. RecordingScheduler.arm (triggers in REC-37) computes the start and the end independently and registers both alarms (exact, or a 10-minute window without the permission).
2. Start alarm → ScheduledStartReceiver: occurrence day from the extra (or the next start's date) → gate (REC-42) → BedtimeNotification.show → finally arm again (start moves to the next unskipped night, end stays tonight's).
3. "Start recording" → service ACTION_START with start_source=schedule → the same path as a manual start, delay included; the bedtime notification is cancelled in beginRecording.
4. "Skip tonight" → ScheduledStartReceiver with ACTION_SKIP: marker + history written, notification removed, arm again.
5. End alarm → ScheduledStopReceiver: Recording, Starting or Delayed → MonitorService.stop with source schedule; otherwise the stale bedtime notification is removed; finally arm again.
6. Home or Settings "Skip tonight" → RecordingScheduler.skipNextStart: nothing when a switch is off; otherwise the marker and the history are written and the schedule re-armed, and the caller refreshes its own next-start value (HomeViewModel.skipTonight, SettingsViewModel.skipTonight).

#### Flow: Home opens or resumes

1. HomeViewModel.init: safety net (open row → Interrupted), refreshPreFlight, refreshSchedule, scheduler.arm (self-heal after force-stop or update), experiment chip (outside this area).
2. ON_RESUME (observer in HomeScreen): refreshPreFlight, refreshSchedule, refreshExperimentChip.
3. refreshSchedule: nextScheduledStart = scheduler.nextStartOrNull(now) (null when a switch is off); then scheduleResolved = true.
4. scheduleRowState(scheduleResolved, recordingEnabled, nextScheduledStart) → Hidden, Off or Armed.

### 1.2.3 State and stored data

States (MonitorStateHolder is a singleton StateFlow; the state is never persisted — after a process death it is Idle until HomeViewModel.init runs):

| State | Meaning | Foreground service | Session row | Home status card | Notification |
|---|---|---|---|---|---|
| `Idle` | nothing active | not running | none open (an open row here = interrupted night not yet surfaced) | "Not recording", grey dot, Start | none |
| `Starting` | inside beginRecording, between the locked re-check and Delayed or Recording | running | opened as RECORDING from gate 8 on | "Starting…", Start button | 41, recording text |
| `Delayed` | row open, delay wait armed, no engine | running (microphone access held, nothing read) | open | "Waiting — audio capture starts in X", Stop, "Start capture now" | 41, count-down |
| `Recording` | engine live | running | open; heartbeat rewrites `captureStats` every 15 min | red dot, "Recording… microphone active", elapsed from the session start, Stop | 41, chronometer from the session start |
| `StartError` | start refused or failed, service stopping | stops | not opened (resume path: the open row stays open) | "Not recording" + "Could not start: …" | removed |
| `Interrupted` | open row, no live engine; set by HomeViewModel.init or by gate 7 | not running | open | "Not recording", Start disabled, banner when the ViewModel summary exists | 1043 until resume or finalize |
| `Finished` | transient after a successful finalize | stops | finalised | "Not recording" for one composition, then the report opens | removed |

Transitions (From · Trigger · To · Side effects):

| From | Trigger | To | Side effects |
|---|---|---|---|
| any | start while the state is Recording, Starting or Delayed | unchanged | early return before any promotion (no notification re-post) |
| Idle, StartError, Finished, Interrupted | ACTION_START or the bedtime Start action passes gates 1–3 | Starting | promoted with notification 41; then gates 4–13 |
| Starting | master switch off (new session) | Idle | Logcat warning only; notification removed; service stops; no row |
| Starting | another row open (new session) | Interrupted(open.id, open.startedAt) | session log start_blocked_interrupted; service stops; no new row |
| Starting | row missing / already finalised | StartError / Idle | service_error_session_missing / log start_aborted |
| Starting | delay > 0 and remaining > 0 | Delayed(id, startedAt, captureStartsAtMs) | wake lock (~~unless SKIPPED~~ — always acquired since consolidation T3 removed the debug arms), thermal listener, log recording_start + start_delay, 1042 and 1043 cancelled, count-down 41, delay job |
| Starting | no delay left | Recording | beginCaptureLocked: engine + heartbeat, log detector_config, 41 re-anchored |
| Starting | throwable inside the locked body | StartError | log start_failed; wake lock released |
| Delayed | delay job fires, guard holds, row still open | Recording | as for the immediate branch |
| Delayed | delay job fires, row finalised meanwhile | Idle | log capture_start_aborted reason=session_finalized; service stops |
| Delayed | ACTION_START_NOW | Recording (Idle / StartError on failure) | wait cancelled; capture start = tap; log start_delay_skipped |
| Delayed | Stop (manual or schedule) | Finished | log start_delay_cancelled; finalize COMPLETED with null stats |
| Recording | Stop (manual or schedule) | Finished | log recording_stop; finalize COMPLETED with engine stats |
| Recording | capture error | Finished | finalize INTERRUPTED; the stop line says source=capture_error (~~source=manual~~ — fixed in consolidation T8, defect D4) |
| Finished | Home collects the state | Idle | consumeFinished; Home opens the report |
| Recording, Delayed, Starting | process death, then START_STICKY restart (null intent) | Idle in the new process | 1043 posted; promotion best-effort; service stops; row stays open |
| Idle | HomeViewModel.init finds an open row | Interrupted | saved-event count for the banner |
| Interrupted | ACTION_RESUME, switch on, row open | Starting → Delayed or Recording | resumedAfterInterruption = 1; log session_resumed; 1043 cancelled |
| Interrupted | ACTION_RESUME, switch off | Interrupted | nothing starts; 1043 stays; service stops |
| Interrupted | ACTION_RESUME, row already finalised | Interrupted (in memory) | 1043 cancelled; service stops |
| Interrupted, Idle | Home "Finalize & view report" | Idle | row RECOVERED; 1043 cancelled; latest report opened |
| any | onDestroy by the system | unchanged (holder not reset) | engine stopped; listener and wake lock released |

beginRecording gates, in order (`monitoring/service/MonitorService.kt` · `beginRecording`):

| # | Condition | Outcome |
|---|---|---|
| 1 | in-memory state is Recording, Starting or Delayed | return; nothing promoted |
| 2 | always | startInForeground(now): no-op without RECORD_AUDIO on API 34+, otherwise startForeground with notification 41 and type MICROPHONE (type-less below 34 without the permission) |
| 3 | RECORD_AUDIO missing | StartError(onboarding_mic_rationale); notification removed; stopSelf |
| 4 | free space on filesDir below 500 MiB | StartError(service_error_low_storage); notification removed; stopSelf |
| 5 | under the mutex: state again Recording, Starting or Delayed | return (the service stays promoted); otherwise state ← Starting |
| 6 | new session and master switch off | Idle; Logcat warning; notification removed; stopSelf |
| 7 | new session and openSession() non-null | Interrupted(open); log start_blocked_interrupted; notification removed; stopSelf |
| 8 | new session: startSession() (status RECORDING, startedAt = now, device zone, device / OS / app version); resume: the given id. Row missing | StartError(service_error_session_missing); stopSelf |
| 9 | row already has endedAt | Idle; log start_aborted reason=session_already_finalized; stopSelf |
| 10 | resume only | stats carried from the stored capture stats; markResumedAfterInterruption; log session_resumed after=process_death |
| 11 | always | acquire the wake lock (~~mode from the debug flag and the delay; acquire unless SKIPPED~~ — the debug modes were removed in consolidation T3, FULL is the only mode); thermal listener; log recording_start source=… storageFreeMB=… thermal=…; cancel 1042 and 1043 |
| 12 | captureStartAt = startedAt + delay; remaining > 0 | Delayed; log start_delay; count-down notification; wait armed outside the mutex |
| 13 | remaining ≤ 0 | beginCaptureLocked at once; on resume the chronometer is re-anchored to the session start |
| any | throwable between 5 and 13 | StartError(message or "Failed to start recording"); log start_failed; wake lock released; stopSelf |

Decision tables (each row is one pure function; the tests are the ones that pin it):

| Function | Rule | Pinned by |
|---|---|---|
| `monitoring/service/StartIntentPolicy.kt` · `startCommandFor` | ACTION_START → START_NEW_SESSION · ACTION_START_NOW → START_NOW_SKIP_DELAY · ACTION_RESUME → RESUME_OPEN_SESSION · ACTION_STOP → STOP · null intent or any other action → SURFACE_INTERRUPTED (never starts capture) | `StartIntentPolicyTest.null intent is a sticky restart and never starts capture`, `StartIntentPolicyTest.unknown action is treated as a sticky restart and never starts capture`, `StartIntentPolicyTest.start-now action is a distinct user-driven command` |
| `monitoring/service/StartIntentPolicy.kt` · `resumeDecision` | openSessionId ≤ 0 → STALE · else recording disabled → BLOCKED · else RESUME | `StartIntentPolicyTest.resume with an open session and recording enabled resumes capture`, `StartIntentPolicyTest.resume with the master switch off never starts capture and keeps the banner`, `StartIntentPolicyTest.resume without an open session is stale regardless of the switch` |
| `monitoring/service/MonitorState.kt` · `canBeginCaptureAfterDelay` | true iff state is Delayed with the same sessionId and the same captureStartsAtMs | `DelayedCaptureGuardTest.matching delayed state allows capture`, `DelayedCaptureGuardTest.different session or recomputed capture start blocks capture` |
| `monitoring/service/MonitorState.kt` · `canSkipStartDelay`, `canStartNewSession` | skip: true iff Delayed · new session: false only for Interrupted | `StartIntentPolicyTest.start-now only skips while the state is Delayed`, `StartIntentPolicyTest.a new start is refused while an interrupted night awaits its decision (P2-1)` |
| `ui/home/HomeViewModel.kt` · `canFinalizeInterrupted` | true only for Idle and Interrupted | `InterruptedFinalizeGuardTest.idle and interrupted states allow finalize`, `InterruptedFinalizeGuardTest.live engine states refuse finalize`, `InterruptedFinalizeGuardTest.transient states refuse finalize` |
| `monitoring/schedule/SchedulePolicy.kt` · `shouldPostBedtimeNotification` | recordingEnabled ∧ scheduleEnabled ∧ ¬skipSet ∧ ¬alreadyRecording ∧ ¬hasOpenSession (alreadyRecording = Recording, Starting or Delayed) | `SchedulePolicyTest.full truth table over all 32 gate combinations` |
| `domain/policy/NightStopPolicy.kt` · `shouldConfirmStop` | false when the schedule is off or nextEnd is null; else Duration(now → nextEnd).toHours() < 12 | `NightStopPolicyTest.stop during the night confirms`, `NightStopPolicyTest.stop after tonight's end does not confirm`, `NightStopPolicyTest.the night window is bounded` |
| `domain/policy/MissedNightPolicy.kt` · `evaluate` | today ∈ skippedDays → Skipped · eligible = ended sessions waking today with COMPLETED, RECOVERED, or INTERRUPTED with capture evidence · any eligible ≥ 4 h in bed → Recorded · eligible non-empty → Short(sum of in-bed time) · else Missed | `MissedNightPolicyTest.skipped wins — the skip key is the night's intended WAKE date`, `MissedNightPolicyTest.an INTERRUPTED night with captured windows counts as recorded or short, not missed`, `MissedNightPolicyTest.an evening hour plus an after-midnight stretch sums but never promotes` |
| `domain/policy/MissedNightPolicy.kt` · `todayZone` | zone of the first (most recent) session when parseable, else the device zone | `MissedNightPolicyTest.today anchors on the most recent session's zone` |
| `monitoring/service/ThermalPolicy.kt` · `isThrottled` | thermalStatus ≥ THERMAL_STATUS_MODERATE | `ThermalPolicyTest.moderate and above throttle` |
| `domain/policy/StartDelayPolicy.kt` · `sanitizeDelayMinutes` | null or negative → 0 · above 120 → 120 · else the value | `StartDelayPolicyTest.sanitize clamps to the valid range` |
| `monitoring/schedule/ScheduleTimes.kt` · `nextOccurrence` | today at the given minutes when strictly after now, else tomorrow; minutes clamped to 0…1439 | `ScheduleTimesTest.same-day future occurrence stays today`, `ScheduleTimesTest.occurrence exactly now rolls to tomorrow` |
| `monitoring/schedule/ScheduleTimes.kt` · `nextStart` | nextOccurrence(now, start), advanced day by day while intendedWakeDay(candidate, end) equals the marker | `ScheduleTimesTest.the skip marker keys on the intended wake date, not the start date`, `ScheduleTimesTest.a post-midnight start wakes the same day and its skip keys that day` |
| `monitoring/schedule/ScheduleTimes.kt` · `intendedWakeDay` | local epoch day of nextOccurrence(start, end) | `ScheduleTimesTest.intendedWakeDay crosses midnight for an evening start and stays put for a night start` |
| `ui/home/HomeScreen.kt` · `scheduleRowState` | ¬scheduleResolved → Hidden · ¬recordingEnabled → Hidden · armedStart non-null → Armed(armedStart) · else Off | `ScheduleRowStateTest.unresolved schedule hides the row whatever the other inputs`, `ScheduleRowStateTest.recording disabled hides the row with or without an armed start`, `ScheduleRowStateTest.recording enabled without an armed start is the explicit off-state` |
| `monitoring/service/BatteryStatus.kt` · `format` | null → "batt=? charging=?" · else "batt=<percent>% charging=<true or false>" | `BatteryStatusTest.values render exactly` |

Skip marker keyed by the intended WAKE date (schedule_skip_epoch_day). Example, evening start 22:30 → 06:30: on Nov 1 at 10:00 the next start is Nov 1 22:30 and wakes on Nov 2, so the marker is Nov 2; with it, nextStart answers Nov 2 22:30, and from Nov 2 at 10:00 the same marker no longer matches (that start wakes Nov 3). Example, post-midnight start 00:30 → 07:00: on Nov 1 at 00:00 the next start is Nov 1 00:30 and wakes on Nov 1, so the marker is Nov 1 itself; nextStart answers Nov 2 00:30. Both are pinned by the two ScheduleTimesTest functions cited above.

Preference keys (DataStore file "settings"; all declared in `data/prefs/UserPreferences.kt`):

| Key | Type | Default and bounds | Meaning |
|---|---|---|---|
| `recording_enabled` | Boolean | true | master switch; gates every start, never a stop |
| `schedule_enabled` | Boolean | false | schedule switch |
| `schedule_start_minutes` | Int | 1350 (22:30); clamped to 0…1439 when written and on every read | bedtime, minutes of day |
| `schedule_end_minutes` | Int | 390 (06:30); clamped to 0…1439 when written and on every read | end time |
| `schedule_skip_epoch_day` | Long | 0 = none | the single skip marker: intended wake date of the skipped night; overwritten by each skip, never cleared |
| `schedule_skip_history` | Set of String | empty | every skipped wake date as a decimal string; additive, never pruned; written in the same edit as the single key; days ≤ 0 ignored, malformed entries dropped on read |
| `start_delay_minutes` | Int | 0; clamped to 0…120 by the setter and by `data/prefs/UserPreferences.kt` · `startDelayMinutesNow` | capture start delay in minutes |
| ~~`debug_skip_capture_wakelock`~~ | Boolean | false (lock held) | removed in consolidation T3 (the wake lock is always held; old stored values are ignored) |
| ~~`debug_enter_windows_1`~~ | Boolean | false | removed in consolidation T3 (the detector always uses ENTER_WINDOWS = 2; stored "+dbg1" rows stay readable) |
| `disclaimer_accepted_at`, `clips_retention_days`, `session_retention_days`, `noise_display_min_confidence` | Long, Int, Int, Double | null; 7 and 90, clamped to 1…365; 0.5, clamped to 0.0…0.9 with NaN → default | disclaimer, retention and the report display filter — outside this area |
| `garmin_auto_retry_date` / `_ms` / `_count`, `garmin_not_ready_retry_date` / `_ms` / `_count`, `insights_dismissed_rules`, `escalation_self_report_answered`, `escalation_snoring_loud`, `escalation_witnessed_pauses`, `escalation_daytime_sleepiness` | String, Long, Int; Set of String; Boolean × 4 | unset / 0 / 0; empty; false | Garmin backfill budgets (2 per date, 6 h; 4 per date, 1 h), insights and escalation self-report — outside this area |

Notifications posted from this area (texts: `app/src/main/res/values/strings.xml` · `notif_recording_title` and neighbours):

| Id | Channel (importance) | Posted by | Content | Actions |
|---|---|---|---|---|
| 41 | `monitoring` (IMPORTANCE_LOW) | `monitoring/service/MonitorService.kt` · `buildRecordingNotification` (promotion, re-anchored by `monitoring/service/MonitorService.kt` · `updateChronometer`) and `monitoring/service/MonitorService.kt` · `buildDelayNotification` during the delay | Recording: "Recording sleep audio", chronometer up. Delay: "Sleep recording starting soon", "Audio capture begins at <time> — time to fall asleep. Tap Stop to cancel.", count-down chronometer. Ongoing, onlyAlertOnce | "Stop" → service ACTION_STOP; tap → MainActivity |
| 1042 | `scheduled_recording` (IMPORTANCE_HIGH) | `monitoring/schedule/BedtimeNotification.kt` · `show` | "Time to record your night" / "Ready to record — one tap needed so Android grants the microphone." PRIORITY_HIGH, CATEGORY_ALARM, autoCancel | "Start recording" → service ACTION_START with start_source=schedule; "Skip tonight" → broadcast ScheduledStartReceiver ACTION_SKIP with the occurrence day; tap → MainActivity |
| 1043 | `interrupted_recording` (IMPORTANCE_HIGH) | `monitoring/service/InterruptedNotification.kt` · `show` | "Night recording was interrupted" / "Recording stopped overnight — tap to reopen the app and resume or finalize the recording." PRIORITY_HIGH, CATEGORY_ERROR, autoCancel | "Resume recording" → service ACTION_RESUME; tap → MainActivity |

PendingIntents (all FLAG_IMMUTABLE):

| Request code | Type | Target | Extras | Other flags |
|---|---|---|---|---|
| 1001 | getBroadcast | ScheduledStartReceiver | occurrence_epoch_day (start's local date) | FLAG_UPDATE_CURRENT |
| 1002 | getBroadcast | ScheduledStopReceiver | none | FLAG_UPDATE_CURRENT |
| 1003 | getService | MonitorService ACTION_START | start_source=schedule | FLAG_UPDATE_CURRENT |
| 1004 | getBroadcast | ScheduledStartReceiver ACTION_SKIP | occurrence_epoch_day | FLAG_UPDATE_CURRENT |
| 1005 | getService | MonitorService ACTION_RESUME | none | FLAG_UPDATE_CURRENT |
| 0 | getService | MonitorService ACTION_STOP (both builders of notification 41) | none | none |
| 0 | getActivity | MainActivity (content intent of 41, 1042, 1043) | none | FLAG_UPDATE_CURRENT |

Data this area writes through the repository (definitions are section 4): session `startedAt` (tap time) and status at finalize (COMPLETED, INTERRUPTED, RECOVERED); `captureStats` JSON rewritten by the heartbeat with `saved_at_ms` and `capture_started_at` (`data/repo/SessionRepository.kt` · `saveCaptureProgress`); `resumedAfterInterruption` set to 1 by `data/db/dao/Daos.kt` · `markResumedAfterInterruption` (only while the row is open).

## 1.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| REC-01 | An intent action maps as: ACTION_START → START_NEW_SESSION, ACTION_START_NOW → START_NOW_SKIP_DELAY, ACTION_RESUME → RESUME_OPEN_SESSION, ACTION_STOP → STOP; a null intent (START_STICKY restart) or any other action → SURFACE_INTERRUPTED, which never starts capture | `monitoring/service/StartIntentPolicy.kt` · `startCommandFor` | `StartIntentPolicyTest.explicit start action starts a new session`, `StartIntentPolicyTest.stop action never starts capture`, `StartIntentPolicyTest.unknown action is treated as a sticky restart and never starts capture` | `tested` |
| REC-02 | onStartCommand returns START_STICKY for every delivery and dispatches: STOP → endRecording(COMPLETED, stop_source or manual); START_NEW_SESSION → beginRecording(null, start_source or manual); START_NOW_SKIP_DELAY → skipStartDelay; RESUME_OPEN_SESSION → resumeInterruptedSession; SURFACE_INTERRUPTED → surfaceInterruptionOrStop | `monitoring/service/MonitorService.kt` · `onStartCommand` | — | `unverified` |
| REC-03 | beginRecording applies the thirteen gates of the table in 1.2.3, in that order | `monitoring/service/MonitorService.kt` · `beginRecording` | — | `unverified` |
| REC-04 | The foreground promotion (notification 41) precedes every early return; without RECORD_AUDIO no startForeground is attempted on API 34+ and a type-less one is used below 34 | `monitoring/service/MonitorService.kt` · `startInForeground` | — | `unverified` |
| REC-05 | StartError carries the microphone rationale, the low-storage text, the missing-session text or the thrown message ("Failed to start recording" when null); the notification is then removed and the service stops | `monitoring/service/MonitorService.kt` · `beginRecording` | — | `unverified` |
| REC-06 | Home shows "Could not start: <message>" while the state is StartError; Start remains enabled — except after the low-storage StartError, where Home also disables Start (`startEnabled` requires `preFlight.storageOk`) | `ui/home/HomeScreen.kt` · `StatusCard` | — | `unverified` |
| REC-07 | Delay bounds 0–120 minutes: null or negative → 0, above 120 → 120, default 0 | `domain/policy/StartDelayPolicy.kt` · `sanitizeDelayMinutes` | `StartDelayPolicyTest.sanitize clamps to the valid range`, `StartDelayPolicyTest.over-max preference is clamped in the math too` | `tested` |
| REC-08 | The planned capture start = session start + delay minutes | `domain/policy/StartDelayPolicy.kt` · `captureStartAtMs` | `StartDelayPolicyTest.capture starts after the configured delay`, `StartDelayPolicyTest.zero delay starts capture at session start` | `tested` |
| REC-09 | Settings offers six chips — Off, 15, 30, 45, 60, 90 min; the choice applies to the next start | `ui/settings/SettingsScreen.kt` · `START_DELAY_OPTIONS` | — | `unverified` |
| REC-10 | remaining > 0 → Delayed(sessionId, startedAt, captureStartsAtMs): the row started at the tap, log start_delay, count-down notification 41, the wait runs outside the begin/end mutex | `monitoring/service/MonitorService.kt` · `beginRecording` | — | `device-verified` (sessions 24–26, 60-minute delay, owner's phone) |
| REC-11 | When the delay elapses, capture begins only if the state is still Delayed for the same session with the same planned capture start; otherwise only capture_start_skipped is logged | `monitoring/service/MonitorState.kt` · `canBeginCaptureAfterDelay` | `DelayedCaptureGuardTest.stop during the delay blocks capture`, `DelayedCaptureGuardTest.session already recording blocks a second capture start` | `tested` |
| REC-12 | Natural expiry starts the engine and sets Recording(sessionId, startedAt) when the row is still open; a row finalised meanwhile → Idle with log capture_start_aborted | `monitoring/service/MonitorService.kt` · `beginCaptureLocked` | — | `device-verified` (two of sessions 24–26 expired naturally) |
| REC-13 | "Start capture now" skips only in Delayed; in any other state it promotes and stops without capture; in Recording or Starting it only logs start_delay_skip_ignored | `monitoring/service/MonitorState.kt` · `canSkipStartDelay` | `StartIntentPolicyTest.start-now only skips while the state is Delayed` | `tested` |
| REC-14 | The skip stamps the capture start at the tap (log start_delay_skipped planned=… actual=…) and is not gated on the master switch | `monitoring/service/MonitorService.kt` · `skipStartDelay` | — | `device-verified` (one of sessions 24–26, session log start_delay_skipped) |
| REC-15 | A stop during the delay finalises COMPLETED with null stats and logs start_delay_cancelled; no recording_stop line is written | `monitoring/service/MonitorService.kt` · `endRecording` | — | `unverified` |
| REC-16 | A sticky restart mid-delay posts 1043 and stops; a later Resume re-enters Delayed with the original planned capture start (session start + current delay) or captures at once when it has passed | `monitoring/service/MonitorService.kt` · `resumeInterruptedSession` | — | `unverified` |
| REC-17 | Wake lock PARTIAL_WAKE_LOCK "SleepAnalysis::capture" with a 14 h timeout, not re-acquired; FULL by default; DELAY_ONLY when the debug flag is on and the delay > 0 (released in beginCaptureLocked, log capture_wakelock_released); SKIPPED when the debug flag is on and the delay is 0 (never acquired); recording_start carries " wl=off" or " wl=off-delay-covered". (DELAY_ONLY / SKIPPED and the debug flag were removed in consolidation T3 — FULL is the only mode; the markers stay contract for stored logs.) | ~~monitoring/service/MonitorService.kt · CaptureWakelockMode~~ (removed) | — | ~~`unverified`~~ removed |
| REC-18 | Throttled iff the thermal status is ≥ MODERATE | `monitoring/service/ThermalPolicy.kt` · `isThrottled` | `ThermalPolicyTest.none and light do not throttle`, `ThermalPolicyTest.moderate and above throttle` | `tested` |
| REC-19 | The current thermal status is applied when the listener registers and again to the freshly created engine; every status change is logged as thermal status=<NAME> throttled=<bool> | `monitoring/service/MonitorService.kt` · `applyCurrentThermalStatusToEngine` | — | `unverified` |
| REC-20 | Heartbeat every 15 minutes, only while an engine exists: logs heartbeat (windows, skipped, clips, gateSkip, freeMB, STORAGE_LOW below 500 MB, battery) and saves the cumulative capture stats with saved_at_ms and capture_started_at; a failed beat is logged heartbeat_failed | `monitoring/service/MonitorService.kt` · `startHeartbeat` | — | `unverified` |
| REC-21 | Battery field "batt=<percent>% charging=<true or false>"; unavailable → "batt=? charging=?" | `monitoring/service/BatteryStatus.kt` · `format` | `BatteryStatusTest.null renders placeholders`, `BatteryStatusTest.values render exactly` | `tested` |
| REC-22 | Every explicit stop finalises COMPLETED — Home Stop, the Stop action of notification 41, the scheduled stop; a capture error finalises INTERRUPTED | `monitoring/service/MonitorService.kt` · `endRecording` | — | `device-verified` (manual stops of sessions 24–26) |
| REC-23 | finalizeSession true → Finished(sessionId); false or throwing → Idle with log finalize_skipped reason=already_finalized or finalize_failed; a stop with no active session id leaves the row untouched and sets Idle | `monitoring/service/MonitorService.kt` · `endRecording` | — | `unverified` |
| REC-24 | Stop sources: manual (default — Home, notification) and schedule; a capture error stops with `source=capture_error` (fixed in consolidation T8, defect D4 — MonitorService.SOURCE_CAPTURE_ERROR; it previously logged `manual`). Start sources: manual, schedule, resumed | `monitoring/service/MonitorService.kt` · `SOURCE_SCHEDULE` | — | `unverified` |
| REC-25 | Finished is transient: Home calls consumeFinished (→ Idle) and opens that session's report | `ui/home/HomeViewModel.kt` · `consumeFinished` | — | `unverified` |
| REC-26 | Scheduled stop: Recording, Starting or Delayed → stop with source schedule, whatever started the night; otherwise a stale bedtime notification is removed; the schedule is re-armed afterwards | `monitoring/schedule/ScheduledStopReceiver.kt` · `onReceive` | — | `unverified` |
| REC-27 | On a null or unknown intent the interrupted notification is posted first, the promotion is attempted inside runCatching, and the stop always runs | `monitoring/service/InterruptionStopSequence.kt` · `run` | `InterruptionStopSequenceTest.a failed promotion does not prevent the notification or the stop`, `InterruptionStopSequenceTest.a successful promotion is followed by notification and stop in order` | `tested` |
| REC-28 | Notification 1043 is posted only when a row is open and no session is active in the service instance; otherwise the sticky restart just promotes and stops | `monitoring/service/MonitorService.kt` · `surfaceInterruptionOrStop` | — | `unverified` |
| REC-29 | Resume decision: no open row → STALE (1043 cancelled, service stops); recording disabled → BLOCKED (row and 1043 stay, nothing starts); otherwise RESUME | `monitoring/service/StartIntentPolicy.kt` · `resumeDecision` | `StartIntentPolicyTest.resume with the master switch off never starts capture and keeps the banner`, `StartIntentPolicyTest.resume without an open session is stale regardless of the switch` | `tested` |
| REC-30 | A resume keeps the session id, carries the heartbeat-persisted stats, sets resumedAfterInterruption = 1, logs session_resumed after=process_death, counts already-saved clips against the clip budget and cancels 1043 | `monitoring/service/MonitorService.kt` · `beginRecording` | — | `unverified` |
| REC-31 | On Home start-up an open row with the in-memory state Idle becomes Interrupted(id, startedAt) together with the saved-event count; the state is re-checked after the database read and never clobbers a live state | `ui/home/HomeViewModel.kt` · `init` | — | `unverified` |
| REC-32 | The Home finalize acts only in Idle or Interrupted | `ui/home/HomeViewModel.kt` · `canFinalizeInterrupted` | `InterruptedFinalizeGuardTest.live engine states refuse finalize`, `InterruptedFinalizeGuardTest.transient states refuse finalize` | `tested` |
| REC-33 | The Home finalize closes every open row as RECOVERED, clears the banner, cancels 1043, opens the latest session's report and sets Idle | `ui/home/HomeViewModel.kt` · `finalizeInterrupted` | — | `unverified` |
| REC-34 | The Home banner offers "Finalize & view report" and a working Resume button wired to `resumeInterrupted` (added in consolidation T8, defect D6 — HomeScreen.kt · InterruptedCard; previously only notification 1043 could resume) and is hidden once the state is Recording | `ui/home/HomeScreen.kt` · `InterruptedCard` | — | `unverified` |
| REC-35 | The master switch (default on) gates the Home Start button, a new-session start, the bedtime notification and the resume; turning it off never stops an active night (the setter only writes the preference and re-arms) | `ui/settings/SettingsScreen.kt` · `SettingsViewModel.setRecordingEnabled` | — | `unverified` |
| REC-36 | arm(): with either switch off, both alarms are cancelled and the bedtime notification removed | `monitoring/schedule/RecordingScheduler.kt` · `arm` | — | `device-verified` (2026-11-23, schedule switched off at 21:53) |
| REC-37 | arm(): start = nextStart(now, start, end, skip day) and end = nextOccurrence(now, end), computed independently; request codes 1001 and 1002; the start intent carries occurrence_epoch_day = the start's local date. arm() runs at HomeViewModel creation, from the four schedule setters, in skipNextStart, at the end of both alarm receivers, and on boot / timezone / clock change | `monitoring/schedule/RecordingScheduler.kt` · `arm` | — | `unverified` |
| REC-38 | Alarms are exact RTC_WAKEUP (setExactAndAllowWhileIdle) when the SDK is below 31 or canScheduleExactAlarms() is true; otherwise setWindow with a 10-minute window | `monitoring/schedule/RecordingScheduler.kt` · `arm` | — | `device-verified` for the exact arm (Android 16, 2026-11-24); the window arm is `unverified` |
| REC-39 | nextOccurrence returns today's occurrence when it is strictly after now, else tomorrow; out-of-range minutes are clamped to 0…1439 | `monitoring/schedule/ScheduleTimes.kt` · `nextOccurrence` | `ScheduleTimesTest.same-day future occurrence stays today`, `ScheduleTimesTest.past occurrence moves to tomorrow`, `ScheduleTimesTest.out-of-range minutes are clamped into the valid day range` | `tested` |
| REC-40 | nextStart advances day by day past any candidate whose intended wake day equals the skip marker; every other marker value (0, past, future) is ignored | `monitoring/schedule/ScheduleTimes.kt` · `nextStart` | `ScheduleTimesTest.the skip marker keys on the intended wake date, not the start date`, `ScheduleTimesTest.a post-midnight start wakes the same day and its skip keys that day`, `ScheduleTimesTest.sentinel zero and far-past skip markers are ignored` | `tested` |
| REC-41 | intendedWakeDay is the local epoch day of the next end-time occurrence after the start — the day after an evening start, the same day for a post-midnight start | `monitoring/schedule/ScheduleTimes.kt` · `intendedWakeDay` | `ScheduleTimesTest.intendedWakeDay crosses midnight for an evening start and stays put for a night start` | `tested` |
| REC-42 | The bedtime notification is posted only when recording is enabled, the schedule is enabled, the night is not skipped, nothing is Recording, Starting or Delayed, and no row is open | `monitoring/schedule/SchedulePolicy.kt` · `shouldPostBedtimeNotification` | `SchedulePolicyTest.full truth table over all 32 gate combinations`, `SchedulePolicyTest.an open interrupted night suppresses the bedtime notification (P2-1)` | `tested` |
| REC-43 | The start receiver never starts the service and always re-arms; without the occurrence extra it uses the next start's date. Its Skip action stores the marker (intended wake day of the carried occurrence), removes the bedtime notification and re-arms. The whole bedtime path (alarm → notification → its Start action starts the microphone service) has never run on a device | `monitoring/schedule/ScheduledStartReceiver.kt` · `onReceive` | — | `unverified` |
| REC-44 | skipNextStart does nothing when a switch is off; otherwise it stores the marker of the next start and re-arms | `monitoring/schedule/RecordingScheduler.kt` · `skipNextStart` | — | `unverified` |
| REC-45 | Every skip write also adds the day to the history set (days ≤ 0 ignored); stored entries are parsed back and malformed ones dropped | `data/prefs/UserPreferences.kt` · `skipHistoryWith` | `ScheduleSkipHistoryTest.mirroring is additive and starts from nothing`, `ScheduleSkipHistoryTest.non-positive days never enter the history`, `ScheduleSkipHistoryTest.parsing survives hostile stored values` | `tested` |
| REC-46 | BootReceiver re-arms on BOOT_COMPLETED, TIMEZONE_CHANGED and TIME_SET, ignores every other action and never records | `monitoring/schedule/BootReceiver.kt` · `onReceive` | — | `unverified` |
| REC-47 | Schedule minutes are clamped to 0…1439 by the setter and on every read; defaults 22:30 and 06:30 | `data/prefs/UserPreferences.kt` · `PreferenceBounds.sanitizeMinuteOfDayOrDefault` | `PreferenceBoundsTest.schedule minutes clamp to the minutes-of-day clock`, `PreferenceBoundsTest.read-time clamp contains legacy out-of-range stored values, defaults only when absent` | `tested` |
| REC-48 | Settings rejects a start equal to the end (inline error "Start and end time must differ.", old value kept); the time rows are enabled only when both switches are on | `ui/settings/SettingsScreen.kt` · `SettingsViewModel.setScheduleStart` | — | `unverified` |
| REC-49 | Missed-night verdict: Skipped when today is in the skip set; else over the eligible sessions (ended, waking today, COMPLETED, RECOVERED, or INTERRUPTED with capture evidence) — Recorded when one is ≥ 4 h in bed, else Short(sum of in-bed time) when any, else Missed | `domain/policy/MissedNightPolicy.kt` · `evaluate` | `MissedNightPolicyTest.recorded when a night-like session woke today`, `MissedNightPolicyTest.exactly 4h in bed is night-like`, `MissedNightPolicyTest.under 4h is short and reports the captured in-bed time`, `MissedNightPolicyTest.short sums multiple sessions of the same night`, `MissedNightPolicyTest.non-night-like statuses never silence the banner`, `MissedNightPolicyTest.an INTERRUPTED night with zero captured windows still renders missed`, `MissedNightPolicyTest.one night-like session among short ones silences the banner` | `tested` |
| REC-50 | "Today" is the current date in the most recent session's stored zone; the device zone is the fallback for no history or an unparseable zone | `domain/policy/MissedNightPolicy.kt` · `todayZone` | `MissedNightPolicyTest.today anchors on the most recent session's zone`, `MissedNightPolicyTest.no sessions fall back to the device zone`, `MissedNightPolicyTest.an unparseable session zone falls back to the device zone` | `tested` |
| REC-51 | The banner is suppressed when there are no sessions or any open session; capture evidence = at least one processed window in `domain/report/CaptureStats.kt` · `anyProcessedWindows` | `ui/home/HomeViewModel.kt` · `missedNight` | — | `unverified` |
| REC-52 | A manual stop asks for confirmation iff the schedule is on and the next scheduled end is less than 12 whole hours away | `domain/policy/NightStopPolicy.kt` · `shouldConfirmStop` | `NightStopPolicyTest.stop during the night confirms`, `NightStopPolicyTest.stop after tonight's end does not confirm`, `NightStopPolicyTest.stops far from any night shape do not confirm`, `NightStopPolicyTest.no schedule never confirms`, `NightStopPolicyTest.the night window is bounded` | `tested` |
| REC-53 | The rule is evaluated at tap time against one fresh read of BOTH schedule inputs (`prefs.scheduleStopInputsNow()` — fixed in consolidation T8, defect D-15/D1: the old code read WhileSubscribed state flows nobody subscribed to, so the end time was stuck at the initial 06:30 and a user-changed end was ignored; the fresh read honors the user's setting); the dialog is shown only from the Recording state (a Delayed stop is immediate) and survives rotation | `ui/home/HomeViewModel.kt` · `shouldConfirmStopNow` | — | `unverified` |
| REC-54 | Schedule row: Hidden until the first schedule read completes and whenever recording is disabled; Armed(nextStart) when the scheduler answers a start; otherwise Off | `ui/home/HomeScreen.kt` · `scheduleRowState` | `ScheduleRowStateTest.unresolved schedule hides the row whatever the other inputs`, `ScheduleRowStateTest.recording disabled hides the row with or without an armed start`, `ScheduleRowStateTest.an armed start is carried as given`, `ScheduleRowStateTest.recording enabled without an armed start is the explicit off-state` | `tested` |
| REC-55 | refreshSchedule is called from HomeViewModel.init and on every Home ON_RESUME; it stores nextStartOrNull(now) — null when a switch is off — and then sets scheduleResolved = true (once). Armed row: "Scheduled start tonight at HH:MM" when the start is today, else "Next scheduled start: <date time>", with "Skip tonight"; off-row: "Scheduled start is off — recording starts manually." with "Turn on in Settings", which opens the existing Settings screen (`ui/home/HomeScreen.kt` · `ScheduleOffRow`) | `ui/home/HomeViewModel.kt` · `refreshSchedule` | — | `unverified` |
| REC-56 | Start is enabled iff recording is enabled, free storage ≥ 500 MB and the state is not Interrupted; the hint "Recording is disabled in Settings." shows only when the master switch is off and nothing is active (`!active && !recordingEnabled` — fixed in consolidation T8, defect D3; the interrupted-night and low-storage cases have their own cards/hints) | `ui/home/HomeScreen.kt` · `StatusCard` | — | `unverified` |
| REC-57 | Start tap: a missing microphone permission → permission dialog (a denial ends the flow); then, on API 33+, a missing notification permission → dialog, and the start is issued when it returns whatever the answer; otherwise the start is issued directly | `ui/home/HomeScreen.kt` · `onStartClick` | — | `unverified` |
| REC-58 | The microphone dot is red only in Recording; its content description is state-dependent (fixed in consolidation T8, defect D2 — HomeScreen.kt · MicIndicator now announces the inactive string `home_mic_indicator_inactive` when the mic is off, instead of "Microphone active" in every state) | `ui/home/HomeScreen.kt` · `MicIndicator` | — | `unverified` |
| REC-59 | Capture-window label "Captured X – Y": start = capture_started_at from the stats JSON when > 0, else the tap time; no line while the night is open | `ui/home/HomeScreen.kt` · `captureWindowLabel` | `CaptureWindowLabelTest.open night has no capture window line`, `CaptureWindowLabelTest.without stats the tap time is the window start`, `CaptureWindowLabelTest.capture_started_at moves the window start past the delay`, `CaptureWindowLabelTest.malformed stats JSON falls back to the tap time`, `CaptureWindowLabelTest.a capture_started_at of zero falls back to the tap time` | `tested` |
| REC-60 | Pre-flight card: microphone, notification permission (API 33+), battery exemption with a Fix button, free storage ≥ 500 MB; refreshed at init and on ON_RESUME | `ui/home/HomeViewModel.kt` · `refreshPreFlight` | — | `unverified` |
| REC-61 | Settings shows the exact-alarm warning with "Allow exact alarms", and the notification warning with "Allow notifications", only when both switches are on and the capability is missing; both are re-checked on ON_RESUME | `ui/settings/SettingsScreen.kt` · `refreshExactAlarmPermission` | — | `unverified` |
| REC-62 | The four schedule setters update their state first, write the preference, then call rearmSchedule (arm + next start); skipTonight in Settings stores the marker and refreshes the next start | `ui/settings/SettingsScreen.kt` · `rearmSchedule` | — | `unverified` |

## 1.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| `MIN_FREE_BYTES` | 500 MiB | `monitoring/service/MonitorService.kt` · `MIN_FREE_BYTES` | gate 4: start refused below; the heartbeat appends STORAGE_LOW below `STORAGE_WARN_MB` (500 MB) |
| `NOTIFICATION_ID` | 41 | `monitoring/service/MonitorService.kt` · `NOTIFICATION_ID` | foreground notification (recording and count-down) |
| `CHANNEL_ID` | "monitoring", IMPORTANCE_LOW | `monitoring/service/MonitorService.kt` · `CHANNEL_ID` | channel of notification 41 |
| `WAKELOCK_TAG` | "SleepAnalysis::capture" | `monitoring/service/MonitorService.kt` · `WAKELOCK_TAG` | partial wake lock name |
| `MAX_SESSION_MS` | 14 h | `monitoring/service/MonitorService.kt` · `MAX_SESSION_MS` | wake-lock acquire timeout |
| `HEARTBEAT_INTERVAL_MS` | 15 min | `monitoring/service/MonitorService.kt` · `HEARTBEAT_INTERVAL_MS` | heartbeat period |
| `NOTIFICATION_ID` | 1042 | `monitoring/schedule/BedtimeNotification.kt` · `NOTIFICATION_ID` | bedtime notification (channel "scheduled_recording", IMPORTANCE_HIGH) |
| `NOTIFICATION_ID` | 1043 | `monitoring/service/InterruptedNotification.kt` · `NOTIFICATION_ID` | interrupted notification (channel "interrupted_recording", IMPORTANCE_HIGH) |
| `START_REQUEST_CODE`, `END_REQUEST_CODE` | 1001, 1002 | `monitoring/schedule/RecordingScheduler.kt` · `START_REQUEST_CODE` | alarm PendingIntents |
| `INEXACT_WINDOW_MS` | 10 min | `monitoring/schedule/RecordingScheduler.kt` · `INEXACT_WINDOW_MS` | setWindow fallback without the exact-alarm permission |
| `START_REQUEST_CODE`, `SKIP_REQUEST_CODE` | 1003, 1004 | `monitoring/schedule/BedtimeNotification.kt` · `SKIP_REQUEST_CODE` | Start and Skip actions |
| `RESUME_REQUEST_CODE` | 1005 | `monitoring/service/InterruptedNotification.kt` · `RESUME_REQUEST_CODE` | Resume action |
| `MAX_DELAY_MINUTES` | 120 min | `domain/policy/StartDelayPolicy.kt` · `MAX_DELAY_MINUTES` | upper bound of the start delay; default 0 (`DEFAULT_DELAY_MINUTES`) |
| `START_DELAY_OPTIONS` | 0, 15, 30, 45, 60, 90 min | `ui/settings/SettingsScreen.kt` · `START_DELAY_OPTIONS` | chips offered in Settings |
| `NIGHT_WINDOW_HOURS` | 12 h | `domain/policy/NightStopPolicy.kt` · `NIGHT_WINDOW_HOURS` | horizon of the stop confirmation |
| `NIGHT_LIKE_MIN_IN_BED_MS` | 4 h | `domain/policy/MissedNightPolicy.kt` · `NIGHT_LIKE_MIN_IN_BED_MS` | night-like threshold (mirrors the previous-night constant) |
| `DEFAULT_SCHEDULE_START_MINUTES` | 1350 (22:30) | `data/prefs/UserPreferences.kt` · `DEFAULT_SCHEDULE_START_MINUTES` | default start; the default end is 390 (06:30, `DEFAULT_SCHEDULE_END_MINUTES`) |
| `MIN_MINUTE_OF_DAY`, `MAX_MINUTE_OF_DAY` | 0, 1439 | `data/prefs/UserPreferences.kt` · `MAX_MINUTE_OF_DAY` | clamp of the schedule times |
| ~~DEFAULT_DEBUG_SKIP_CAPTURE_WAKELOCK~~ | false | ~~data/prefs/UserPreferences.kt · DEFAULT_DEBUG_SKIP_CAPTURE_WAKELOCK~~ | removed in consolidation T3 (the wake lock is now always held) |
| initial values | recordingEnabled true, scheduleEnabled false | `ui/home/HomeViewModel.kt` | StateFlow values before the first emission (~~scheduleEndMinutes 390, `ui/home/HomeViewModel.kt` · `scheduleEndMinutes`~~ — the flow was deleted in consolidation T8's D-15 fix; the stop confirmation reads `prefs.scheduleStopInputsNow()` fresh instead) |
| `storageOk` | free ≥ 500 MB | `ui/home/HomeViewModel.kt` · `storageOk` | gates Start and the pre-flight row |

## 1.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| Microphone permission missing at start | StartError with the onboarding rationale; the service is promoted type-less below API 34, then stopped | `monitoring/service/MonitorService.kt` · `beginRecording` |
| Free storage below 500 MiB at start | StartError(service_error_low_storage); Home additionally disables Start | `monitoring/service/MonitorService.kt` · `beginRecording` |
| Open row at start in a fresh process (state Idle) | Interrupted(open); log start_blocked_interrupted | `monitoring/service/MonitorService.kt` · `beginRecording` |
| "Start capture now" during Recording or Starting | only logs start_delay_skip_ignored; never stops a live engine | `monitoring/service/MonitorService.kt` · `skipStartDelay` |
| Capture error mid-night | endRecording(INTERRUPTED) → Finished → report | `monitoring/service/MonitorService.kt` · `onCaptureError` |
| A service coroutine throws | logged as service_coroutine_crash; the supervisor keeps the engine running | `monitoring/service/MonitorService.kt` · `crashHandler` |
| Heartbeat failure | logged heartbeat_failed; next beat in 15 min | `monitoring/service/MonitorService.kt` · `startHeartbeat` |
| Stop while the row was already finalised elsewhere | Idle; log finalize_skipped reason=already_finalized (database exception: finalize_failed) | `monitoring/service/MonitorService.kt` · `endRecording` |
| Sticky restart whose foreground promotion is refused | 1043 is already posted; log interrupted_fg_promotion_failed; the stop still runs | `monitoring/service/MonitorService.kt` · `surfaceInterruptionOrStop` |
| Bedtime alarm while recording, delayed, an interrupted night is open, the night is skipped, or a switch is off | no bedtime notification | `monitoring/schedule/ScheduledStartReceiver.kt` · `onReceive` |
| Alarm delivered without the occurrence extra | the next start's date is used | `monitoring/schedule/ScheduledStartReceiver.kt` · `fallbackOccurrenceDay` |
| End alarm while active but the service cannot be started from the background | IllegalStateException logged; the recording keeps running | `monitoring/schedule/ScheduledStopReceiver.kt` · `onReceive` |
| No exact-alarm permission on API 31+ | setWindow with a 10-minute window; Settings warning and "Allow exact alarms" | `monitoring/schedule/RecordingScheduler.kt` · `arm` |
| Corrupt schedule minutes | clamped to 0…1439 on read | `data/prefs/UserPreferences.kt` · `PreferenceBounds.sanitizeMinuteOfDayOrDefault` |

## 1.6 Contract boundaries — what a refactor must not break

**Persisted formats**
- DataStore file "settings"; keys of this area: `recording_enabled`, `schedule_enabled`, `schedule_start_minutes`, `schedule_end_minutes`, `schedule_skip_epoch_day` (Long, an intended WAKE date as epoch day, 0 = none), `schedule_skip_history` (string set of epoch days, additive), `start_delay_minutes`, ~~`debug_skip_capture_wakelock`~~, ~~`debug_enter_windows_1`~~ (both keys removed in consolidation T3 — the app no longer reads or writes them; old stored values are ignored). Defaults and bounds: the table in 1.2.3.
- Session row fields written here: `startedAt` (tap time), `status` (COMPLETED, INTERRUPTED, RECOVERED — see section 4), `captureStats` JSON keys `saved_at_ms` and `capture_started_at` written by the heartbeat, column `resumedAfterInterruption` (schema v7).
- Session log event names written here: recording_start, start_delay, start_delay_skipped, start_delay_skip_ignored, start_delay_skip_failed, start_delay_cancelled, capture_start_skipped, capture_start_aborted, capture_wakelock_released, detector_config, thermal, heartbeat, heartbeat_failed, capture_error, recording_stop, finalize_failed, finalize_skipped, start_failed, start_blocked_interrupted, start_aborted, session_resumed, service_coroutine_crash, interrupted_fg_promotion_failed, event_persist_failed. Details are key=value pairs; stop lines carry `source=` (manual or schedule) and `status=`, start lines carry `source=` (manual, schedule, resumed; ~~and the wake-lock arm `wl=off` or `wl=off-delay-covered`~~ — no longer written since consolidation T3, old logs keep the markers; the `capture_wakelock_released` event went with the removed arms too). Heartbeat and stop lines end with the battery field, which `tools/night_battery.py` · BATT_RE parses.

**Version strings stored with data**
- The model version stored with each event is SessionRepository.MODEL_VERSION (~~or that value plus "+dbg1" when the debug enter-windows switch is on~~ — the toggle was removed in consolidation T3; the suffix is no longer written, stored "+dbg1" rows stay readable; section 3 owns the string).

**Platform contracts**
- Manifest `app/src/main/AndroidManifest.xml`: permissions RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, WAKE_LOCK, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; service `.monitoring.service.MonitorService` (not exported, foregroundServiceType microphone); receivers ScheduledStartReceiver, ScheduledStopReceiver, BootReceiver (not exported).
- Intent actions: `com.sleepanalysis.action.START`, `com.sleepanalysis.action.STOP`, `com.sleepanalysis.action.START_NOW`, `com.sleepanalysis.action.RESUME`, `com.sleepanalysis.action.SCHEDULE_SKIP`; extras `start_source`, `stop_source`, `occurrence_epoch_day`.
- Notification ids 41, 1042, 1043; channels `monitoring`, `scheduled_recording`, `interrupted_recording`; PendingIntent request codes 1001–1005 and 0 (table in 1.2.3).
- START and START_NOW are delivered with startForegroundService, so the service must call startForeground before stopping; the service always returns START_STICKY.
- Capture begins only on a user-initiated start (Home tap or notification action): a background-started microphone service would record silence on Android 11+.

**Cross-area interfaces**
- Out to section 4: `data/repo/SessionRepository.kt` · `startSession`, `session`, openSession, `findOpenSession`, `finalizeSession`, `saveCaptureProgress`, `markResumedAfterInterruption`, `finalizeOpenAsRecovered`, `latestSession`, `savedEventCountForSession`, `clipCountForSession`, `insertEvent`.
- Out to section 2: the engine is built with enterWindows and initialClipCount and receives `setThrottled`; its listener reports `onCaptureError` (→ INTERRUPTED finalize) and `onEventFinalized` (→ insertEvent). Out to section 3: the `detector_config` log line (~~and the "+dbg1" model version~~ — removed in consolidation T3).
- In from other areas: `ui/trends/TrendsScreen.kt` · `scheduleSkipHistoryDaysNow` (skip history for trends); `ui/navigation/AppNavHost.kt` · `onOpenSettings` (wired to the Settings route); the report package reads `resumedAfterInterruption` and `capture_started_at`.
- The monitor state is read only inside this area (Home, the two alarm receivers); no other screen observes it.

**Safety-net tests** (run first after touching this area): `StartIntentPolicyTest`, `DelayedCaptureGuardTest`, `InterruptionStopSequenceTest`, `InterruptedFinalizeGuardTest`, `SchedulePolicyTest`, `ScheduleTimesTest`, `NightStopPolicyTest`, `StartDelayPolicyTest`, `MissedNightPolicyTest`, `ScheduleSkipHistoryTest`, `PreferenceBoundsTest`, ~~UserPreferencesDefaultsTest~~ (removed in consolidation T3), `ThermalPolicyTest`, `BatteryStatusTest`, `CaptureWindowLabelTest`, `ScheduleRowStateTest`. MonitorService itself, both ViewModels, the scheduler, the three receivers and the two notification builders have no test.

## 1.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| ~~monitoring/service/MonitorService.kt · CaptureWakelockMode (DELAY_ONLY and SKIPPED arms)~~ — removed in consolidation T3 | `debug/experiment` | selected only in `beginRecording` from `debug_skip_capture_wakelock` (default false); Settings switch `settings_debug_wakelock_title`; markers `wl=off`, `wl=off-delay-covered`, event `capture_wakelock_released` | `medium` — both arms are untested and the markers already exist in stored session logs | About 90 lines of comments and branches across MonitorService, UserPreferences and SettingsScreen; owner decides whether the A/B night still needs to run |
| ~~`debug_enter_windows_1`, ui/settings/SettingsScreen.kt · setDebugEnter1~~ — removed in consolidation T3 (the "+dbg1" suffix stays readable in stored data) | `debug/experiment` | read in `beginCaptureLocked`; persisted as the "+dbg1" model-version suffix | `high` — a version string stored with events (section 3) | Owner decision |
| ~~ui/home/HomeViewModel.kt · PreFlightChecklist.allClear~~ (removed in consolidation T1) | `dead` | scan A row 49; only the declaration — HomeScreen reads the four fields separately | `none` | done |
| `monitoring/schedule/ScheduleTimes.kt` · `minutesOfDay` | `test-only` | only `ScheduleTimesTest.minutesOfDay helper spans the full valid range`; ScheduleRow computes `nextStart.hour * 60 + nextStart.minute` inline | `none` | Invisible to the scan (common identifier); delete with the test |
| `domain/policy/StartDelayPolicy.kt` · `remainingMs` | `test-only` | no production call — `beginRecording` computes `captureStartAt - System.currentTimeMillis()` inline; its KDoc claims it is used for the restart remainder | `low` | Use it at the single inline site, or delete with three StartDelayPolicyTest functions |
| `ui/home/HomeViewModel.kt` · `scheduleEndMinutes` | `dead` | never collected anywhere in app/src; only its current value is read by `shouldConfirmStopNow`, so it is always 390 | `low` — replacing it by a fresh read changes behaviour (fixes D1) | See 1.8 |
| `ui/home/HomeViewModel.kt` · `skipTonight` and `ui/settings/SettingsScreen.kt` · `skipTonight` | `duplicate` | scan C: identical bodies; the "refresh the next start from nextStartOrNull" line exists five times (HomeViewModel twice, SettingsViewModel three times) | `low` | Both ViewModels keep a private next-start state; the return value of `skipNextStart` is discarded by both callers |
| `monitoring/schedule/BedtimeNotification.kt` · `cancel` and `monitoring/service/InterruptedNotification.kt` · `cancel` | `duplicate` | scan C: identical bodies; both `show` functions repeat channel creation, the open-app PendingIntent (built a third time by `monitoring/service/MonitorService.kt` · `openAppPendingIntent`), icon, HIGH priority, autoCancel; `buildDelayNotification` and `buildRecordingNotification` are near-identical (scan C) | `low` | One small notification helper |
| `monitoring/service/MonitorService.kt` · `start` | `over-general` | the `source` parameter is never passed by a caller (only `ui/home/HomeViewModel.kt` · `startRecording`, default manual); the schedule source travels in the intent built by `monitoring/schedule/BedtimeNotification.kt` · `show` | `none` | Drop the parameter |
| ~~app/src/main/res/values/strings.xml · home_view_report~~ (string removed in consolidation T1), home_battery_hint_title, home_battery_hint_body, home_battery_hint_button | `dead` | lint UnusedResources; grep over app/src, tools, analysis, docs: only the declarations | `none` | The battery hint was superseded by the pre-flight card |
| ~~app/src/main/res/values/strings.xml · home_schedule_off~~ — string removed in consolidation T4 | `duplicate` | "Scheduled start is off — recording starts manually." against `settings_schedule_off` "Scheduling is off — recording starts manually." (one word differs) | `none` | Part of the uncommitted change; reuse one string |
| `data/repo/SessionRepository.kt` · `finalizeOpenAsRecovered` (KDoc) | `doc-drift` | KDoc says it runs "as a guard before a new recording starts"; `beginRecording` refuses instead (P2-1). MonitorService comments also cite a "stale cleanup" finalizer; `finalizeSession` has no caller besides `endRecording` | `none` | Fix comments (area 4 file) |
| `app/src/test/java/com/sleepanalysis/domain/policy/MissedNightPolicyTest.kt` (KDoc) | `doc-drift` | KDoc: "the skip set holds START-evening epoch days"; the code and the same test key WAKE dates | `none` | Fix KDoc |
| `monitoring/schedule/RecordingScheduler.kt` · `arm` (class KDoc) | `doc-drift` | lists boot, settings changes and the receivers; omits every HomeViewModel creation | `none` | Fix KDoc |
| `monitoring/service/InterruptionStopSequence.kt` · `run`, `monitoring/service/ThermalPolicy.kt` · `isThrottled` | `over-general` | one caller each, both in MonitorService; extracted to keep a JVM test | `low` | Keep while the tests exist |

Notes (non-normative):
- Inexact-alarm fallback: worth keeping (six lines in `arm` plus two strings) — it is the only safety net when SCHEDULE_EXACT_ALARM is revoked; the alternative is to arm nothing and warn on Home. Its gap is D5 in 1.8.
- `monitoring/service/BatteryStatus.kt`: keep — `format` output is parsed by `tools/night_battery.py` · BATT_RE; `read` is the only Android-dependent part and is untested.
- Single-caller helpers that exist as test seams: `monitoring/service/StartIntentPolicy.kt` · `resumeDecision`, `domain/policy/MissedNightPolicy.kt` · `todayZone`, `ui/home/HomeScreen.kt` · `captureWindowLabel`. Without a test: `monitoring/service/MonitorService.kt` · `delayExistingSession`, `monitoring/service/MonitorService.kt` · `updateDelayNotification`.
- Small leftovers: cross-area duplication — `data/repo/SessionRepository.kt` · `findOpenSession` and data/repo/SessionRepository.kt · openSession ran the same DAO query (openSession removed in consolidation T4) (this area calls both; gate 7 reads it twice in a row). `domain/policy/StartDelayPolicy.kt` · MAX_DELAY_MINUTES (120) is above the largest preset (90), so only a stored value reaches 91–120. `ui/home/HomeViewModel.kt` · `statusLabel` returns hard-coded English words instead of string resources.

## 1.8 Open questions for the owner

**Defects found while writing** (the pre-fix behaviour is described below; nothing was changed while writing — the fixes marked as such landed in consolidation T8)

- D1 MEDIUM — `ui/home/HomeViewModel.kt` · `shouldConfirmStopNow`: it reads the current value of `scheduleEndMinutes` and `scheduleEnabled`, two flows created with stateIn(viewModelScope, WhileSubscribed(5 s), initial). Subscribers: `scheduleEndMinutes` has none anywhere in app/src — HomeScreen collects recordingEnabled and scheduleEnabled (among others) in `ui/home/HomeScreen.kt` · HomeScreen, but never the end time — so its value is permanently the initial 390 = 06:30. The `scheduleEnabled` collection exists only for this purpose (the comment above it says so); after Home's first frame it holds the last value seen while Home was started, and a change made in Settings arrives when Home restarts its collection (the Stop button is only reachable with Home resumed). Effective rule today: confirm iff the schedule switch is on and the next 06:30 is less than 12 h away, i.e. for taps from 18:30 to 06:29. A user-changed end time is ignored in both directions. **Fixed in consolidation T8 (D-15):** `shouldConfirmStopNow` now derives both inputs from one fresh read, `prefs.scheduleStopInputsNow()` (HomeViewModel.kt), and the `scheduleEndMinutes` flow was deleted — a user-changed end time decides.
- D2 MEDIUM — `ui/home/HomeScreen.kt` · MicIndicator: it sets the content description "Microphone active" whether or not the microphone is active; only the colour changes (error red in Recording, surfaceVariant otherwise). A screen reader announces "Microphone active" for the grey dot in Idle, Starting, Delayed, StartError, Interrupted and Finished and never an inactive state; for everyone else the state is colour-only. During Delayed the permission is held but nothing is read, and the dot is grey. Same pattern: CheckRow and the battery check icon pass contentDescription = null, so pass / fail is visual only. **Fixed in consolidation T8:** MicIndicator now sets a state-dependent description — the inactive state announces `home_mic_indicator_inactive`.
- D3 LOW — `ui/home/HomeScreen.kt` · StatusCard: the hint "Recording is disabled in Settings." is shown whenever Start is disabled — also for an open interrupted night and for free storage below 500 MB (both composed into `startEnabled` in HomeScreen). **Fixed in consolidation T8:** the hint now shows only when `!active && !recordingEnabled` (the master-switch-off case; the other cases keep their own cards/hints).
- D4 LOW — `monitoring/service/MonitorService.kt` · `onCaptureError`: the stop runs without a source, so an engine failure is logged as recording_stop source=manual status=INTERRUPTED. Related, INFO: a stop during the start delay writes no recording_stop line at all (stats are null), only start_delay_cancelled, and `tools/night_battery.py` reads the stop line as the end of a night. **Fixed in consolidation T8:** the capture-error stop passes `source=capture_error` (`MonitorService.SOURCE_CAPTURE_ERROR`).
- D5 LOW — granting the exact-alarm permission does not re-arm: `rearmSchedule` runs only from the four setters, ON_RESUME only refreshes the warning, and no receiver listens for the permission-changed broadcast. The alarms stay in the 10-minute window until the next HomeViewModel creation, receiver run or setting change.
- D6 LOW — Home cannot resume an interrupted night: the banner has only "Finalize & view report"; Resume exists only as the action of notification 1043, which a tap on its body dismisses. Intended? **Fixed in consolidation T8:** the InterruptedCard gained a Resume button wired to `viewModel.resumeInterrupted` (HomeScreen.kt).
- D7 LOW, UNVERIFIED on device — a service instance destroyed and recreated inside a living process leaves the holder at Recording or Delayed (onDestroy does not reset it). A Stop then runs `endRecording` with no engine and session id −1 → Idle while the row stays open; the next Start is refused and, because the start-up safety net ran at init only, the banner summary is missing: Start is disabled with the D3 hint until the process restarts.

**Review of the uncommitted change** (Home schedule-row off-state: `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, the strings home_schedule_off / `home_schedule_enable` in `app/src/main/res/values/strings.xml`, README.md, new `app/src/test/java/com/sleepanalysis/ui/home/ScheduleRowStateTest.kt`)

- Correctness against the intent — all four outcomes hold in `ui/home/HomeScreen.kt` · `scheduleRowState`: recording disabled → Hidden; not resolved → Hidden; armed start → Armed, rendered by the unchanged ScheduleRow; otherwise Off → ScheduleOffRow with the existing onOpenSettings (rendered by ScheduleOffRow in HomeScreen), wired to Routes.SETTINGS by `ui/navigation/AppNavHost.kt` · AppNavHost.
- refreshSchedule callers — exactly two: `ui/home/HomeViewModel.kt` · `init` and the ON_RESUME handler of `ui/home/HomeScreen.kt` · HomeScreen. Paths that change the armed start without it: skipTonight (`ui/home/HomeViewModel.kt` · `skipTonight`, refreshes the value itself), the Settings setters (Home is not started underneath; refreshed on return), the alarms firing (refreshed on the next resume). No path can produce a null armed start while scheduleResolved is false.
- LOW race — the two inputs come from different reads. `recordingEnabled` is a StateFlow with initial value true (`ui/home/HomeViewModel.kt` · `recordingEnabled`) that only updates once Home collects it, while scheduleResolved is set by the init coroutine (`ui/home/HomeViewModel.kt` · `refreshSchedule`). For a user whose recording is disabled, a cold start can therefore compose one frame with resolved = true, recordingEnabled still true, armedStart null → the off-row, before the first real emission hides it. The window is one frame and was not observed; the switch being re-enabled shows no off-row (the value is already true). Fix: derive both inputs from one read inside refreshSchedule, or keep the row unresolved until recordingEnabled has emitted once.
- LOW test gap — ScheduleRowStateTest asserts all eight input combinations, so any change to the rule fails it (dropping the unresolved branch, reordering the disabled check, inverting the off-state). It pins nothing about the wiring: that line 233 runs after the read, that Off maps to ScheduleOffRow, or that recordingEnabled comes from the StateFlow. No HomeViewModel or Compose test exists.
- LOW wording — home_schedule_off repeats `settings_schedule_off` with one word changed; `home_schedule_enable` "Turn on in Settings" sits next to the switch label "Scheduled nightly recording". Reuse or unify.
- INFO — `scheduleEnabled` is now an unread property (collected in `ui/home/HomeScreen.kt` · HomeScreen but used for nothing else) kept alive only by its comment; removing it as unused would silently break the schedule half of the stop confirmation (D1).
- INFO accessibility and docs — same layout as the armed row: wrapping label with weight, a TextButton whose text is its accessible name, 48 dp target; no state description on either row; the shortcut opens Settings at the top, not at the switch. The README sentence omits that the row is hidden when recording is disabled; strings exist only in values/, so there is no translation gap. If nextStartOrNull throws, scheduleResolved stays false and the row stays hidden (the exception then surfaces in viewModelScope — pre-existing).

VERDICT: APPROVE

**Could not verify (UNVERIFIED)**
- The bedtime path end to end: alarm → notification 1042 → its Start action → microphone service → source=schedule. No test, never seen on the owner's phone.
- Every Android-dependent path of MonitorService (promotions per API level, sticky-restart promotion refusal, wake-lock arms, heartbeat timing), the receivers, the scheduler, the notification builders and both ViewModels — none has a test; DOGFOOD_CHECKLIST items 1, 3, 8 and 12 are still unticked.
- `monitoring/service/BatteryStatus.kt` · `read` (sticky broadcast) and the window arm of the alarms.

**Decisions for the owner**
- ~~Keep or drop the two debug switches (wake-lock experiment, enter-windows) — the second one writes a model-version suffix into stored data.~~ — resolved: both were dropped in consolidation T3 (stored "+dbg1" / `wl=off` markers stay readable).
- Keep the inexact-alarm fallback, or fail loudly instead (no alarm plus a Home warning)?
- Should Home offer Resume for an interrupted night (D6)?
- Raise the start-delay presets to 120 or lower the maximum to 90?
