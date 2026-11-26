# 4. Data layer

**Scope:** Room schema v14 and its migration chain (`data/db/`, `app/schemas/`), the repositories under `data/repo/` (validity math excluded), `work/RetentionWorker.kt`, `di/AppModule.kt`, backup rules, clip and log file layout.
**Requirement prefix:** DAT — rows in 4.3 are numbered DAT-01, DAT-02, …
**Status values:** `tested` = a unit/instrumented test asserts it (cite it) · `device-verified` = evidence from a real night or device run exists (cite where) · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

## 4.1 What the user gets

1. Every night is one stored session that carries its events, clips, measurements, quiet gaps, validity grade and report snapshot; stopping the recording finalizes it [DAT-11, DAT-15, DAT-16, DAT-20, DAT-21, DAT-23].
2. An interrupted night becomes RECOVERED only after the user confirms; its end is bounded to the last moment capture was verifiably alive plus 60 s [DAT-14, DAT-19].
3. Correcting an event's label re-weighs that night's stored snore numbers, validity grade and snapshot [DAT-28 to DAT-31].
4. A short morning note can be attached to a night [DAT-32].
5. A night can be deleted from History: its rows, clip files and session log go, its measurements stay unlinked [DAT-09, DAT-33].
6. Delete-all removes every stored row, including the clinical baseline, plus all clip files and logs [DAT-34, DAT-35].
7. Clips expire after 7 days; nights with their events, stage timelines, gaps and snapshots, their logs and the raw vendor payloads after 90 days; measurements, baseline, factor log and experiments never expire [DAT-37 to DAT-42].
8. Clinical baseline entries can be added and viewed but never edited or deleted individually [DAT-01 to DAT-04].
9. Upgrading from any older build keeps the stored data [DAT-05 to DAT-08].
10. Self-logged factors: saving a date replaces that date's log; the report's morning card changes one key at a time [DAT-45 to DAT-47].
11. N-of-1 experiments persist across nights and survive the deletion of a night [DAT-48].
12. Nothing leaves the device through cloud backup or device-to-device transfer [DAT-44].
13. Retention periods are not editable in the UI [DAT-43].

## 4.2 How it works today

### 4.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| `AppDatabase` | `data/db/AppDatabase.kt` | Room database v14, 12 entities; MIGRATIONS; immutability triggers; `purgeEverythingIncludingClinical` |
| 12 DAO interfaces, `ClipBackedEventRow` | `data/db/dao/Daos.kt` | All SQL; @Transaction default bodies for the replace-by-key operations |
| 12 entities, `SessionStatus`, `EventType`, `UserLabel`, `ExperimentStatus`, `ModelRegistryKind` | `data/db/entity/Entities.kt` | Table definitions and the stored string constants |
| `SessionRepository` | `data/repo/SessionRepository.kt` | Session lifecycle, event/clip writes, finalize, labeling, delete paths; row gathering for report, trends, insights, experiments (1139 lines) |
| `BaselineRepository` | `data/repo/BaselineRepository.kt` | Insert-only access to the clinical baseline |
| `FactorLogRepository` | `data/repo/FactorLogRepository.kt` | Daily factor log: replace-a-date and single-key merge |
| `resolveEventTimes`, `EventTimes` | `data/repo/EventTimes.kt` | Pure mapping of detector offsets to stored times |
| `MeasurementRecorder` (calls only) | `data/recorder/MeasurementRecorder.kt` | The only writer of measurement rows; stamps provenance |
| `RetentionWorker`, `OrphanSweepPolicy` | `work/RetentionWorker.kt` | Daily retention sweep and orphan file sweep; pure sweep guard |
| `retention`, `RetentionSettings`, `PreferenceBounds` | `data/prefs/UserPreferences.kt` | DataStore retention keys, defaults and clamps |
| `AppModule` | `di/AppModule.kt` | Hilt providers |
| `SleepAnalysisApp` | `SleepAnalysisApp.kt` | Custom WorkManager configuration; schedules the worker at start |
| `ClipStore` (layout only) | `data/files/ClipStore.kt` | Clip files under files/clips |
| `SessionLogger` (layout only) | `data/logs/SessionLogger.kt` | One text log per session under files/logs |
| `export_night_csv.py` | `tools/export_night_csv.py` | Python exporter that reads the database file directly (a second consumer of the schema) |

Hilt providers (`di/AppModule.kt`) and their injection sites (constructor parameters typed with the provided class, searched over app/src/main):

| Provider | Provides | Injection sites |
|---|---|---|
| `di/AppModule.kt` · `provideDatabase` | AppDatabase (@Singleton) | SessionRepository, ValidityCalculator, RetentionWorker, GarminImportManager, HealthConnectImportManager, ExperimentTracker |
| `di/AppModule.kt` · `provideSessionDao` | SleepSessionDao | SessionRepository |
| `di/AppModule.kt` · `provideEventDao` | AcousticEventDao | SessionRepository |
| `di/AppModule.kt` · `provideClipDao` | AudioClipDao | SessionRepository |
| `di/AppModule.kt` · `provideMeasurementDao` | MeasurementDao | SessionRepository, MeasurementRecorder, GarminImportManager |
| `di/AppModule.kt` · `provideBaselineDao` | ClinicalBaselineDao | SessionRepository, BaselineRepository |
| `di/AppModule.kt` · `provideRawPayloadDao` | RawPayloadDao | GarminImportManager |
| `di/AppModule.kt` · `provideUserFactorDao` | UserFactorDao | FactorLogRepository |
| `di/AppModule.kt` · `provideReportDao` | ReportDao | none — reached through AppDatabase.reportDao |
| `di/AppModule.kt` · `provideModelRegistryDao` | ModelRegistryDao | none — reached through AppDatabase.modelRegistryDao |
| `di/AppModule.kt` · `provideSleepExperimentDao` | SleepExperimentDao | none — reached through AppDatabase.sleepExperimentDao (ExperimentTracker) |
| `di/AppModule.kt` · `provideClipStore` | ClipStore with root File(filesDir, "clips") | SessionRepository, RetentionWorker, MonitorService, AudioEngine, ReportViewModel, LabelingScreen, ValidityCalculator |
| `di/AppModule.kt` · `provideSessionLogger` | SessionLogger with root File(filesDir, "logs") | SessionRepository, RetentionWorker, MonitorService |
| `di/AppModule.kt` · `provideSnoreDetector` | SnoreDetector (EnergySnoreDetector) | MonitorService, AudioEngine, ReplayEngine |

DAO methods and their production callers ("purge" = AppDatabase.purgeEverythingIncludingClinical; "DAO-internal" = called only by a @Transaction method of the same interface):

| DAO | Methods with a production caller (caller) | No production caller |
|---|---|---|
| `SleepSessionDao` | insert (startSession); finalize (finalizeSession); findOpen (findOpenSession, openSession, finalizeOpenAsRecovered); byId (SessionRepository, 6 sites); latest (latestSession); observeAll (observeSessions, previousNightSummaryFor, buildTrends, buildInsights, experimentPhaseNights); all (GarminImportManager, HealthConnectImportManager, ValidityCalculator); delete (deleteNight); setMorningNotes (setMorningNotes); markResumedAfterInterruption (markResumedAfterInterruption); updateCaptureStats (saveCaptureProgress); countOpen, deleteOlderThan (RetentionWorker); deleteAll (purge) | allStartTimes — only SessionRepository.sessionsOlderThan, which has no caller |
| `AcousticEventDao` | insert (insertEvent); clipBackedEvents (clipBackedEventsForLabeling); bySession (SessionRepository, 6 sites); byId (labelEvent); firstSnoreModelVersion (previousNightSummaryFor, 2 sites); countForSession (savedEventCountForSession); setUserLabel (labelEvent); clearClipIds (RetentionWorker); deleteAll (purge) | — |
| `AudioClipDao` | insert (insertEvent); bySession (buildReport, buildTrends); olderThan, all, deleteByIds (RetentionWorker); countForSession (finalizeSession, clipCountForSession); sumBytesForSession (finalizeSession); setEventId (insertEvent); deleteAll (purge) | — |
| `MeasurementDao` | insert (MeasurementRecorder only); bySession (SessionRepository, 5 sites); garminBetween (garminMetricsForSessionDay, buildTrends, GarminImportManager); replaceGarminForDay (MeasurementRecorder.replaceGarminEstimates); replacePhoneForSession (MeasurementRecorder.replacePhoneEstimates, replaceNightValidity); deleteAll (purge) | insertAll, deleteGarminForTypes, deletePhoneForSession — DAO-internal |
| `ClinicalBaselineDao` | insert (BaselineRepository.add); observeAll (BaselineRepository.observeAll); all (SessionRepository.buildReport, direct DAO use); deleteAll (purge) | — |
| `RawPayloadDao` | upsert (GarminImportManager, HealthConnectImportManager); forDate (garminSleepSummaryForSession, nightRecordFor, ValidityCalculator); deleteOlderThan (RetentionWorker); deleteAll (purge) | — |
| `SleepStageDao` | bySession (buildReport); countForSession (HealthConnectImportManager); replaceForSession (GarminImportManager, HealthConnectImportManager); deleteAll (purge) | insertAll, deleteForSession — DAO-internal |
| `QuietGapDao` | bySession (buildReport, buildTrends, ValidityCalculator); replaceForSession (finalizeSession); deleteAll (purge) | insertAll, deleteForSession — DAO-internal |
| `UserFactorDao` | forDate (FactorLogRepository, SessionRepository.factorsForSessionDay, SessionRepository.nightRecordFor); replaceForDate (FactorLogRepository.saveForDate, FactorLogRepository.mergeForDate); deleteAll (purge) | insertAll, deleteForDate — DAO-internal |
| `ReportDao` | upsert (upsertReportSnapshot); bySession (nightRecordFor); deleteAll (purge) | all — no caller, no test |
| `SleepExperimentDao` | insert (ExperimentTracker.startExperiment); active (4 sites in ExperimentTracker); history (ExperimentTracker.history); byId (3 sites in ExperimentTracker); finish, finishConfirm (refreshActiveVerdict); beginConfirmPhase (startConfirmPhase); updateResult (keepVerdict); abandon (abandon); deleteAll (purge) | — |
| `ModelRegistryDao` | insert, closeOpenRows (registerModelVersion); currentForKind (registerModelVersion, currentModelVersions 2 sites); deleteAll (purge) | historyForKind, versionsAt — no caller, no test |

Public and top-level functions of the repositories (SessionRepository function names are unqualified):

| Function | Production callers |
|---|---|
| `data/repo/SessionRepository.kt` · `startSession` | MonitorService |
| `data/repo/SessionRepository.kt` · `session` | MonitorService (2 sites) |
| `data/repo/SessionRepository.kt` · `clipBackedEventsForLabeling` | LabelingScreen |
| `data/repo/SessionRepository.kt` · `findOpenSession` | MonitorService (2 sites), HomeViewModel — same query as openSession |
| `data/repo/SessionRepository.kt` · `openSession` | MonitorService (2 sites), ScheduledStartReceiver |
| `data/repo/SessionRepository.kt` · `latestSession` | HomeViewModel |
| `data/repo/SessionRepository.kt` · `observeSessions` | HistoryScreen, HomeViewModel |
| `data/repo/SessionRepository.kt` · `insertEvent` | MonitorService |
| `data/repo/SessionRepository.kt` · `finalizeSession` | MonitorService.endRecording; finalizeOpenAsRecovered |
| `data/repo/SessionRepository.kt` · `finalizeOpenAsRecovered` | HomeViewModel only |
| `data/repo/SessionRepository.kt` · `saveCaptureProgress` | MonitorService (heartbeat) |
| `data/repo/SessionRepository.kt` · `clipCountForSession` | MonitorService |
| `data/repo/SessionRepository.kt` · `labelEvent` | ReportViewModel, LabelingScreen |
| `data/repo/SessionRepository.kt` · `setMorningNotes` | ReportViewModel |
| `data/repo/SessionRepository.kt` · `markResumedAfterInterruption` | MonitorService |
| `data/repo/SessionRepository.kt` · `savedEventCountForSession` | HomeViewModel |
| `data/repo/SessionRepository.kt` · `deleteNight` | HistoryScreen |
| `data/repo/SessionRepository.kt` · `deleteAllData` | HistoryScreen |
| `data/repo/SessionRepository.kt` · `buildReport` | ReportViewModel (5 call sites) |
| `data/repo/SessionRepository.kt` · `validityGradeFor` | HomeViewModel; labelEvent |
| `data/repo/SessionRepository.kt` · `currentModelVersions` | none outside the class (buildInsights, experimentPhaseNights) |
| `data/repo/SessionRepository.kt` · `sessionsOlderThan` | none — dead |
| `data/repo/SessionRepository.kt` · `buildTrends` | TrendsScreen (maxNights 7, skippedDays from the skip history) |
| `data/repo/SessionRepository.kt` · `buildInsights` | InsightsViewModel |
| `data/repo/SessionRepository.kt` · `experimentPhaseNights` | ExperimentTracker |
| `data/repo/SessionRepository.kt` · `reportSnapshotHeadline` | upsertReportSnapshot (internal, pure) |
| `data/repo/SessionRepository.kt` · `labelCorrectedSnoreEstimates` | rewriteSnoreAggregates (internal, pure) |
| `data/repo/EventTimes.kt` · `resolveEventTimes` | insertEvent (pure) |
| `data/repo/BaselineRepository.kt` · `observeAll` | BaselineScreen |
| `data/repo/BaselineRepository.kt` · `add` | BaselineScreen |
| `data/repo/BaselineRepository.kt` · `all` | none — dead |
| `data/repo/FactorLogRepository.kt` · `factorsForDate` | FactorLogScreen |
| `data/repo/FactorLogRepository.kt` · `saveForDate` | FactorLogScreen |
| `data/repo/FactorLogRepository.kt` · `saveFactorForDate` | ReportViewModel |
| `data/repo/FactorLogRepository.kt` · `removeFactorForDate` | ReportViewModel |

### 4.2.2 Flows

#### Flow: Open the database
1. AppModule.provideDatabase → AppDatabase.build (singleton): registers MIGRATION_1_2 to MIGRATION_13_14 — there is no destructive fallback — and a Room callback.
2. Callback onCreate and onOpen → AppDatabase.createImmutabilityTriggers: CREATE TRIGGER IF NOT EXISTS trg_clinical_baseline_no_update BEFORE UPDATE ON clinical_baseline and trg_clinical_baseline_no_delete BEFORE DELETE ON clinical_baseline; both run SELECT RAISE(ABORT, 'clinical baseline is immutable').

#### Flow: Session start and resume (triggered from section 1)
1. MonitorService has no session id to resume → SessionRepository.startSession → SleepSessionDao.insert (status RECORDING, endedAt NULL, startedAt = now).
2. MonitorService re-attaches to the open session after a mid-night process death → SessionRepository.markResumedAfterInterruption → SleepSessionDao.markResumedAfterInterruption (resumedAfterInterruption = 1 WHERE id = :id AND endedAt IS NULL).
3. Start while a session is open: MonitorService and ScheduledStartReceiver test SessionRepository.openSession (SleepSessionDao.findOpen); MonitorService refuses the start (log event start_blocked_interrupted) and nothing is finalized automatically.

#### Flow: Event and clip write (per detected event, section 1)
1. MonitorService → SessionRepository.insertEvent(sessionId, sessionStartEpochMs, captureTimeBaseEpochMs, draft, clipRelativePath, clipDurationMs, clipSizeBytes, appliedGain, filePeakDbfs, modelVersion = MODEL_VERSION).
2. One Room transaction. (a) clipRelativePath non-null → AudioClipDao.insert (appliedGain falls back to 2.5 when not supplied). (b) resolveEventTimes → AcousticEventDao.insert (type, startMs, endMs, offsetMs, durationMs, peak and mean dBFS, intensityTier, confidence, modelVersion, clipId, featuresJson). (c) clipId non-null → AudioClipDao.setEventId(clipId, eventId).

#### Flow: Finalize — COMPLETED or INTERRUPTED (section 1 decides the status)
1. MonitorService.endRecording with COMPLETED (manual stop) or INTERRUPTED → SessionRepository.finalizeSession(sessionId, status, nightStats). An exception is caught by the service, logged as finalize_failed and reported as false.
2. One transaction. Session row missing, or endedAt already set → return false; nothing is written.
3. endedAt = now; time in bed = (endedAt − startedAt, at least 0) in seconds.
4. Clip count and clip bytes are read from audio_clip (countForSession, sumBytesForSession), not from engine counters.
5. Captured denominator = (windowsProcessed + windowsSkipped) × HOP_MS / 1000 from nightStats, else the heartbeat captureStats value; zero or absent → time in bed is used instead.
6. Snore set = events counting as snore after labels (SnoreMath.eventsCountingAsSnore); snore seconds = union of their spans; score = SnoreScore.compute over snore seconds, denominator and per-tier seconds.
7. Measurement rows are appended through MeasurementRecorder.recordPhoneEstimate (row conditions in DAT-15): sourceType PHONE, method ALGORITHM_ESTIMATED, isClinical false, modelVersion NULL except on the score row.
8. Quiet gaps: classifyQuietGaps(quietGaps(events, capture start, endedAt), level buckets, silenced spans) → QuietGapDao.replaceForSession.
9. The NIGHT_VALIDITY row is replaced through MeasurementRecorder.replaceNightValidity (computation in section 5).
10. SleepSessionDao.finalize(endedAt, status, captureStats JSON, audioBytes = clip bytes).
11. upsertReportSnapshot → ReportDao.upsert; then registerModelVersion(DETECTOR, resolved detector version, now) and registerModelVersion(SCORE, snore-score-v4, now).

#### Flow: Finalize as RECOVERED (user confirms the interrupted night on Home)
1. HomeViewModel → SessionRepository.finalizeOpenAsRecovered.
2. Loop, at most 5 times: SleepSessionDao.findOpen; no open row → stop; finalizeSession(open.id, RECOVERED, nightStats = null) returning false → stop; otherwise count += 1.
3. Inside finalizeSession: endedAt = min(now, lastAlive + 60 000 ms), lastAlive = max(last event endMs, captureStats.savedAtMs); no such evidence → endedAt = now.
4. No events → no TIME_IN_BED, share or score rows. Engine stats absent → captureStats keeps the heartbeat JSON, or {"schema_version":1} when there is none; quiet gaps come from the heartbeat-persisted buckets.

#### Flow: Label an event (report and labeling screens, section 6)
1. ReportViewModel.labelEvent or LabelingScreen → SessionRepository.labelEvent(eventId, label or null).
2. One transaction. Unknown event id → return. AcousticEventDao.setUserLabel(userLabel, labeledAt = now or NULL).
3. Session still open (endedAt NULL) → stop here; finalize applies the label.
4. MeasurementRecorder.replacePhoneEstimates(sessionId, labelCorrectedSnoreEstimates(session, events)): deletes the session's PHONE rows of the snore-aggregate types and inserts the recomputed rows (notes carry label_corrected=true).
5. ValidityCalculator.computeAndRecord → NIGHT_VALIDITY replaced (section 5).
6. upsertReportSnapshot(session, validityGradeFor(sessionId), now).

#### Flow: Per-night delete (History screen)
1. HistoryScreen → SessionRepository.deleteNight(sessionId).
2. ClipStore.deleteSessionClips(sessionId) — files first.
3. SleepSessionDao.delete(sessionId): cascades to acoustic_event, audio_clip, sleep_stage, quiet_gap and report; measurement.sessionId is set to NULL.
4. SessionLogger.deleteSessionLog(sessionId).

#### Flow: Delete all (History screen)
1. HistoryScreen → SessionRepository.deleteAllData.
2. AppDatabase.purgeEverythingIncludingClinical: one transaction; DROP TRIGGER IF EXISTS for both triggers; deleteAll on all 12 DAOs; the triggers are recreated in a finally block.
3. ClipStore.deleteAll, then SessionLogger.deleteAll. Preferences are not touched.

#### Flow: Retention sweep
1. SleepAnalysisApp.onCreate → RetentionWorker.schedule: enqueueUniquePeriodicWork("retention", ExistingPeriodicWorkPolicy.KEEP, 24 h). The manifest removes the default WorkManager initializer; SleepAnalysisApp.workManagerConfiguration supplies HiltWorkerFactory.
2. RetentionWorker.doWork reads UserPreferences.retention.first().
3. Step 1, clips: audio_clip rows with createdAt < now − clipsDays × DAY_MS; if any: one transaction { AcousticEventDao.clearClipIds(ids), AudioClipDao.deleteByIds(ids) }; then each file is deleted through ClipStore.absolutePath.
4. Step 2, sessions: SleepSessionDao.deleteOlderThan(now − sessionDays × DAY_MS) on startedAt (cascades as above; measurements are unlinked).
5. Step 3, logs: SessionLogger.purgeOlderThan(same cutoff). Step 3b: RawPayloadDao.deleteOlderThan(same cutoff) on fetchedAt.
6. Step 4, orphans: SleepSessionDao.countOpen, the referenced paths from AudioClipDao.all, then ClipStore.deleteOrphans guarded by OrphanSweepPolicy.shouldSweep.
7. Result.success() — there is no retry path.

#### Flow: Experiment persistence (insights and experiments area)
1. ExperimentTracker.startExperiment: refuses when SleepExperimentDao.active() is non-null; else SleepExperimentDao.insert (status ACTIVE, baselineStatsJson).
2. ExperimentTracker.refreshActiveVerdict: baseline phase → finish (ACTIVE → DONE, resultJson); confirm phase → finishConfirm (ACTIVE → DONE, resultJson and confirmPhaseJson).
3. ExperimentTracker.startConfirmPhase: refuses when another row is ACTIVE; beginConfirmPhase (DONE → ACTIVE, confirmPhaseJson).
4. ExperimentTracker.keepVerdict → updateResult (any status except ABANDONED). ExperimentTracker.abandon → SleepExperimentDao.abandon (non-ABANDONED → ABANDONED).
5. Nights come from SessionRepository.experimentPhaseNights(metric, sinceMs). Rows are not session-bound; only delete-all removes them.

#### Flow: Clinical baseline entry (Baseline screen)
1. BaselineScreen → BaselineRepository.add(entry) → ClinicalBaselineDao.insert; the screen observes BaselineRepository.observeAll.
2. Any later UPDATE or DELETE on that row aborts with 'clinical baseline is immutable'. A correction is a new row.

### 4.2.3 State and stored data

Session states (column sleep_session.status, flag resumedAfterInterruption):

| Stored value | Meaning | Written by | Read by |
|---|---|---|---|
| RECORDING, endedAt NULL | open night: recording, or interrupted and awaiting Resume / Finalize | startSession | findOpen, countOpen guards (MonitorService, ScheduledStartReceiver, HomeViewModel, RetentionWorker) |
| COMPLETED, endedAt set | finalized from a stop | finalizeSession, called by MonitorService.endRecording | report, trends, insights |
| INTERRUPTED, endedAt set | finalized by the service as interrupted (trigger condition in section 1) | finalizeSession, called by MonitorService.endRecording | report, trends, MissedNightPolicy |
| RECOVERED, endedAt set | finalized from the interrupted-night confirmation with lost engine stats | finalizeOpenAsRecovered → finalizeSession | report, trends, MissedNightPolicy, NightValidity |
| resumedAfterInterruption = 1 | the open night was resumed after a mid-night process death | markResumedAfterInterruption | validity input at finalize, report |

An "interrupted night" awaiting the user's decision is therefore an open row with status RECORDING; the stored INTERRUPTED value is a finalized state.

Schema v14 as exported to `app/schemas/com.sleepanalysis.data.db.AppDatabase/14.json` (identity hash 993422db94085134f9b93949319b53bc). Every table has id INTEGER PRIMARY KEY autoGenerate. Notation: ! = NOT NULL, ? = nullable.

| Table | Entity | Columns (name TYPE) | Indexes | Foreign keys and delete rule |
|---|---|---|---|---|
| `sleep_session` | `SleepSessionEntity` | startedAt INTEGER!; endedAt INTEGER?; status TEXT!; timeZone TEXT!; deviceModel TEXT!; osVersion TEXT!; appVersion TEXT!; captureStats TEXT?; audioBytesTotal INTEGER!; morningNotes TEXT?; resumedAfterInterruption INTEGER! [default 0]; createdAt INTEGER! | none | none |
| `acoustic_event` | `AcousticEventEntity` | sessionId INTEGER!; type TEXT!; startMs INTEGER!; endMs INTEGER!; offsetMs INTEGER!; durationMs INTEGER!; peakDbfs REAL!; meanDbfs REAL!; intensityTier TEXT!; confidence REAL!; modelVersion TEXT!; clipId INTEGER?; userLabel TEXT?; labeledAt INTEGER?; featuresJson TEXT?; createdAt INTEGER! | index_acoustic_event_sessionId_startMs (sessionId, startMs); index_acoustic_event_type (type) | sessionId → sleep_session.id CASCADE; no foreign key on clipId |
| `audio_clip` | `AudioClipEntity` | sessionId INTEGER!; eventId INTEGER?; filePath TEXT!; durationMs INTEGER!; sizeBytes INTEGER!; sampleRate INTEGER!; appliedGain REAL! [default 2.5]; filePeakDbfs REAL?; createdAt INTEGER! | index_audio_clip_eventId UNIQUE (eventId); index_audio_clip_createdAt (createdAt) | sessionId → sleep_session.id CASCADE; no foreign key on eventId |
| `measurement` | `MeasurementEntity` | sessionId INTEGER?; timestamp INTEGER!; sourceType TEXT!; method TEXT!; isClinical INTEGER!; measurementType TEXT!; tier TEXT!; valueNum REAL?; valueText TEXT?; unit TEXT!; confidence REAL?; modelVersion TEXT?; periodStart INTEGER?; periodEnd INTEGER?; notes TEXT?; createdAt INTEGER! | index_measurement_measurementType_periodStart (measurementType, periodStart); index_measurement_sessionId (sessionId); index_measurement_sourceType_method (sourceType, method) | sessionId → sleep_session.id SET NULL |
| `clinical_baseline` | `ClinicalBaselineEntity` | studyDate TEXT!; lab TEXT!; studyType TEXT!; nightLabel TEXT?; totalSleepTimeMinutes INTEGER?; sleepEfficiencyPercent REAL?; arousalIndexPerHour REAL?; n3Percent REAL?; ahiPerHour REAL?; notes TEXT?; sourceDocument TEXT?; enteredAt INTEGER! | none | none; guarded by the two triggers |
| `raw_payload` | `RawPayloadEntity` | source TEXT!; endpoint TEXT!; date TEXT!; payload TEXT!; fetchedAt INTEGER! | index_raw_payload_source_endpoint_date UNIQUE (source, endpoint, date) | none |
| `user_factor` | `UserFactorEntity` | date TEXT!; factorKey TEXT!; valueNum REAL?; valueText TEXT?; recordedAt INTEGER! | index_user_factor_date (date) | none |
| `sleep_stage` | `SleepStageEntity` | sessionId INTEGER!; startMs INTEGER!; endMs INTEGER!; stage TEXT!; createdAt INTEGER!; source TEXT! [default 'GARMIN'] | index_sleep_stage_sessionId_startMs (sessionId, startMs) | sessionId → sleep_session.id CASCADE |
| `quiet_gap` | `QuietGapEntity` | sessionId INTEGER!; startMs INTEGER!; endMs INTEGER!; durationMs INTEGER!; kind TEXT?; meanDbfs REAL?; createdAt INTEGER! | index_quiet_gap_sessionId_startMs (sessionId, startMs) | sessionId → sleep_session.id CASCADE |
| `report` | `ReportSnapshotEntity` | sessionId INTEGER!; generatedAt INTEGER!; appVersion TEXT!; detectorVersion TEXT?; scoreVersion TEXT?; payload TEXT! | index_report_sessionId UNIQUE (sessionId) | sessionId → sleep_session.id CASCADE |
| `model_registry` | `ModelRegistryEntity` | kind TEXT!; version TEXT!; validFromUtc INTEGER!; validToUtc INTEGER? | index_model_registry_kind_validFromUtc (kind, validFromUtc) | none |
| `sleep_experiment` | `SleepExperimentEntity` | hypothesisRuleId TEXT?; playbookActionId TEXT?; metric TEXT!; startedAt INTEGER!; plannedNights INTEGER!; status TEXT!; baselineStatsJson TEXT!; resultJson TEXT?; confirmPhaseJson TEXT?; createdAt INTEGER! | index_sleep_experiment_status (status) | none |

The two triggers are not part of the exported schema (Room does not know them); they are created by the database callback.

Migration chain 1 → 14 (each step is the object MIGRATION_x_y in `data/db/AppDatabase.kt`; all 13 are registered in the MIGRATIONS array). A database created fresh by a v14 build runs none of them.

| Step | Change | Effect on existing data | Reachable only for a database created at |
|---|---|---|---|
| 1→2 | + table raw_payload with unique index (source, endpoint, date) | new table starts empty | v1 |
| 2→3 | + table user_factor with index on date | empty | v1–v2 |
| 3→4 | + acoustic_event.labeledAt INTEGER NULL | existing events read as unlabeled | v1–v3 |
| 4→5 | + acoustic_event.featuresJson TEXT NULL | existing events carry no features | v1–v4 |
| 5→6 | + sleep_session.morningNotes TEXT NULL | old nights have no note | v1–v5 |
| 6→7 | + sleep_session.resumedAfterInterruption INTEGER NOT NULL DEFAULT 0 | old nights read as never resumed | v1–v6 |
| 7→8 | no SQL — declaration only (entity gains defaultValue "0") | none | v1–v7 |
| 8→9 | + table sleep_stage (FK CASCADE on session, index sessionId + startMs) | empty | v1–v8 |
| 9→10 | + table quiet_gap (FK CASCADE, index sessionId + startMs) | empty; older nights have no stored gaps | v1–v9 |
| 10→11 | + audio_clip.appliedGain REAL NOT NULL DEFAULT 2.5, + audio_clip.filePeakDbfs REAL NULL | pre-v11 clips read gain 2.5 and an unknown peak | v1–v10 |
| 11→12 | + table report (unique sessionId, FK CASCADE), + table model_registry (index kind + validFromUtc) | both empty: nights finalized before v12 have no snapshot and no registry rows; readers fall back (DAT-25; detectorVersion from the first snore event, else MODEL_VERSION) | v1–v11 |
| 12→13 | + table sleep_experiment (index on status) | empty | v1–v12 |
| 13→14 | + sleep_stage.source TEXT NOT NULL DEFAULT 'GARMIN' | every existing stage row is labelled GARMIN | v1–v13 |

Other persisted data:

| Store | Location and format | Written by | Removed by |
|---|---|---|---|
| Room database | app-private file sleepanalysis.db (`data/db/AppDatabase.kt` · `NAME`) | Room | never as a file; rows through the flows above |
| sleep_session.captureStats | JSON written by `monitoring/audio/NightStatsMerge.kt` with schema_version 6 ({"schema_version":1} stub when there are no stats); keys read back by `domain/report/CaptureStats.kt`: schema_version, windows_processed, windows_skipped, ambient_dbfs_median, ambient_dbfs_p95, clip_count, thermal_throttle_events, level_gate_skipped, spectral_skipped, saved_at_ms, capture_started_at, thermal_skipped_hops, client_silenced_spans, ambient_level_buckets | saveCaptureProgress, finalizeSession | with the session |
| acoustic_event.featuresJson | JSON of the detector's decision inputs (entity comment: debug and inspection only) | insertEvent | with the session |
| report.payload | headline JSON (section 5) | upsertReportSnapshot | cascade, purge |
| sleep_experiment JSON columns | baselineStatsJson {metric, median, mad, low, high}; resultJson and confirmPhaseJson with schema_version 1 | ExperimentTracker (its area) | purge only |
| Clip files | `files/clips/session_<id>/evt_<type>_<startMs>_<n>.wav`; audio_clip.filePath is relative to files/clips | ClipStore.writeClip (section 1) | deleteNight, delete-all, retention step 1, orphan sweep |
| Session logs | `files/logs/session_<id>.log`, one line per event | SessionLogger.log (section 1) | deleteNight, delete-all, retention step 3 |
| DataStore file settings | integer keys clips_retention_days and session_retention_days (absent = default) | setRetention — no production caller | never |

## 4.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| `DAT-01` | Opening or creating the database installs trg_clinical_baseline_no_update (BEFORE UPDATE ON clinical_baseline) and trg_clinical_baseline_no_delete (BEFORE DELETE ON clinical_baseline); each executes RAISE(ABORT, 'clinical baseline is immutable') | `data/db/AppDatabase.kt` · `createImmutabilityTriggers` | `MigrationTest.migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers` | `tested` |
| `DAT-02` | An UPDATE or a DELETE of a clinical_baseline row is rejected by the trigger (SQLiteException) | `data/db/AppDatabase.kt` · `createImmutabilityTriggers` | `MigrationTest.migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers` | `tested` |
| `DAT-03` | The clinical DAO offers insert, observeAll, all and deleteAll only — no update, no delete by id; deleteAll has one caller, purgeEverythingIncludingClinical | `data/db/dao/Daos.kt` · `ClinicalBaselineDao` | — | `unverified` |
| `DAT-04` | Adding a baseline entry inserts one row (enteredAt defaults to now) on Dispatchers.IO and returns its id | `data/repo/BaselineRepository.kt` · `add` | — | `unverified` |
| `DAT-05` | Schema version 14 has exactly the 12 tables, columns, indexes and foreign keys listed in 4.2.3 | `app/schemas/com.sleepanalysis.data.db.AppDatabase/14.json` | `MigrationTest.migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers` | `tested` |
| `DAT-06` | A v1 database seeded with one sleep_session, one measurement and one clinical_baseline row migrates to v14 with the rows intact (session status and time zone, resumedAfterInterruption 0, measurement value, baseline row) | `data/db/AppDatabase.kt` · `MIGRATIONS` | `MigrationTest.migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers` | `tested` |
| `DAT-07` | 13→14 adds sleep_stage.source TEXT NOT NULL DEFAULT 'GARMIN'; an existing stage row reads GARMIN and a new insert without source stores GARMIN | `data/db/AppDatabase.kt` · `MIGRATION_13_14` | `MigrationTest.migrate13To14_addsStageSource_defaultingLegacyRowsToGarmin` | `tested` |
| `DAT-08` | 10→11 adds audio_clip.appliedGain REAL NOT NULL DEFAULT 2.5 and audio_clip.filePeakDbfs REAL NULL; a pre-v11 clip row reads 2.5 and NULL | `data/db/AppDatabase.kt` · `MIGRATION_10_11` | `MigrationTest.migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers` | `tested` |
| `DAT-09` | Deleting a sleep_session row removes that session's sleep_stage, quiet_gap, audio_clip and report rows; model_registry and sleep_experiment rows are not removed | `app/schemas/com.sleepanalysis.data.db.AppDatabase/14.json` | `MigrationTest.migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers` | `tested` |
| `DAT-10` | The same delete removes the session's acoustic_event rows (CASCADE) and sets measurement.sessionId to NULL on its measurement rows | `app/schemas/com.sleepanalysis.data.db.AppDatabase/14.json` | — | `unverified` |
| `DAT-11` | startSession inserts one row: startedAt = now, endedAt NULL, status RECORDING, timeZone = system default zone id, deviceModel, osVersion, appVersion = package versionName ("?" if unreadable), audioBytesTotal 0, resumedAfterInterruption false | `data/repo/SessionRepository.kt` · `startSession` | — | `unverified` |
| `DAT-12` | The open session is the row with endedAt IS NULL, newest startedAt first, at most one row | `data/db/dao/Daos.kt` · `findOpen` | — | `unverified` |
| `DAT-13` | finalizeSession writes nothing and returns false when the session row is missing or endedAt is already set | `data/repo/SessionRepository.kt` · `finalizeSession` | — | `unverified` |
| `DAT-14` | finalize sets endedAt = now, except for status RECOVERED with evidence of life (last event endMs or heartbeat savedAtMs): then min(now, lastAlive + 60 000 ms) | `data/repo/SessionRepository.kt` · `finalizeSession` | — | `unverified` |
| `DAT-15` | finalize writes PHONE measurement rows: TIME_IN_BED unless RECOVERED with no events; SNORE_EVENT_COUNT and ACOUSTIC_EVENT_COUNT always; SNORE_TIME when at least one window was processed; SNORE_SHARE_OF_NIGHT when not recovered-without-events, captured seconds > 0 and a window was processed; SNORE_SCORE when not recovered-without-events and a window was processed (modelVersion = formula version, notes = score breakdown); SNORE_INTENSITY_PEAK and SNORE_INTENSITY_MEAN when at least one event counts as snore; NOISE_MEDIAN and NOISE_P95 when the engine supplied them | `data/repo/SessionRepository.kt` · `finalizeSession` | — | `unverified` |
| `DAT-16` | finalize stores captureStats (engine stats as JSON with clipCountOverride = clip rows in the table and the heartbeat's captureStartMs; else the heartbeat JSON; else {"schema_version":1}) and audioBytesTotal = SUM of the session's audio_clip.sizeBytes | `data/db/dao/Daos.kt` · `finalize` | — | `unverified` |
| `DAT-17` | saveCaptureProgress replaces captureStats only while endedAt IS NULL and stamps savedAtMs with the current time | `data/repo/SessionRepository.kt` · `saveCaptureProgress` | — | `unverified` |
| `DAT-18` | markResumedAfterInterruption sets resumedAfterInterruption = 1 only while endedAt IS NULL | `data/db/dao/Daos.kt` · `markResumedAfterInterruption` | — | `unverified` |
| `DAT-19` | finalizeOpenAsRecovered finalizes every open session as RECOVERED with null engine stats, stops at the first false result, runs at most 5 times and returns the number finalized | `data/repo/SessionRepository.kt` · `finalizeOpenAsRecovered` | — | `unverified` |
| `DAT-20` | finalize replaces the session's quiet_gap rows in one DAO transaction (delete + insert) | `data/db/dao/Daos.kt` · `QuietGapDao.replaceForSession` | — | `unverified` |
| `DAT-21` | finalize replaces the session's single NIGHT_VALIDITY measurement row (valueText = grade letter, notes = validity notes) | `data/recorder/MeasurementRecorder.kt` · `replaceNightValidity` | — | `unverified` |
| `DAT-22` | Snapshot headline: snoreScore and snoreSharePercent are null for a RECOVERED night without events and for a night with no processed window; a zero-snore completed night stores share 0 and score 0; snoreSeconds is the label-aware snore time | `data/repo/SessionRepository.kt` · `reportSnapshotHeadline` | `ReportSnapshotRefreshTest.a label-corrected recompute lowers the snapshot aggregates`<br>`ReportSnapshotRefreshTest.a RECOVERED night with no events stores no share or score in the snapshot`<br>`ReportSnapshotRefreshTest.a zero-snore completed night stores share 0 and score 0`<br>`ReportSnapshotRefreshTest.a stop during the start delay stores no share or score in the snapshot`<br>`ReportSnapshotRefreshTest.zero processed windows stores no share or score even with windows_skipped` | `tested` |
| `DAT-23` | finalize upserts one report row per session: detectorVersion = modelVersion of the first event counting as snore, else MODEL_VERSION; scoreVersion = snore-score-v4; appVersion = package versionName; payload = headline plus the Garmin sleep window of the wake date | `data/repo/SessionRepository.kt` · `upsertReportSnapshot` | — | `unverified` |
| `DAT-24` | model_registry: a version that differs from the kind's current open row inserts a row with validFromUtc = now and sets validToUtc = now on the kind's other open rows; same version or null → no write | `data/repo/SessionRepository.kt` · `registerModelVersion` | — | `unverified` |
| `DAT-25` | currentModelVersions returns MODEL_VERSION and snore-score-v4 while the registry has no open row for a kind | `data/repo/SessionRepository.kt` · `currentModelVersions` | — | `unverified` |
| `DAT-26` | insertEvent writes the audio_clip row (when a clip path is given), the acoustic_event row and the back-fill of audio_clip.eventId in one transaction | `data/repo/SessionRepository.kt` · `insertEvent` | — | `unverified` |
| `DAT-27` | Event times: startMs = captureBase + draft start, endMs = captureBase + draft end, offsetMs = max(0, startMs − session start) | `data/repo/EventTimes.kt` · `resolveEventTimes` | `EventTimesTest.fresh engine base near session start yields engine offset`<br>`EventTimesTest.resumed engine times are absolute, not session-relative`<br>`EventTimesTest.offset is clamped when the capture base precedes the session row` | `tested` |
| `DAT-28` | labelEvent stores userLabel and labeledAt = now; clearing the label (null) stores NULL for both | `data/db/dao/Daos.kt` · `setUserLabel` | — | `unverified` |
| `DAT-29` | Label recompute rows use the finalize row conditions over the label-aware snore set; no intensity rows without snoring; a still-recording session yields no rows | `data/repo/SessionRepository.kt` · `labelCorrectedSnoreEstimates` | `LabelCorrectedSnoreEstimatesTest.unlabeled events reproduce the finalize aggregates`<br>`LabelCorrectedSnoreEstimatesTest.a COUGH label on the loudest event removes it from every aggregate`<br>`LabelCorrectedSnoreEstimatesTest.a SNORE label includes an event the detector called other noise`<br>`LabelCorrectedSnoreEstimatesTest.no snoring left writes zero rows and no intensity rows`<br>`LabelCorrectedSnoreEstimatesTest.recovered session with no events writes only the count and time rows`<br>`LabelCorrectedSnoreEstimatesTest.still-recording session produces nothing`<br>`LabelCorrectedSnoreEstimatesTest.a zero-window night writes no snore-time share or score rows on recompute` | `tested` |
| `DAT-30` | Only the SNORE_SCORE row of the recompute carries a modelVersion (the formula version); the other rows carry NULL | `data/repo/SessionRepository.kt` · `labelCorrectedSnoreEstimates` | `LabelCorrectedSnoreEstimatesTest.score row alone carries the formula version, matching finalize` | `tested` |
| `DAT-31` | labelEvent on a finalized session replaces the snore-aggregate PHONE rows (notes carry label_corrected=true), recomputes NIGHT_VALIDITY and refreshes the snapshot, all in one transaction; on an open session only the label is stored | `data/repo/SessionRepository.kt` · `labelEvent` | — | `unverified` |
| `DAT-32` | setMorningNotes trims the text; blank text stores NULL | `data/repo/SessionRepository.kt` · `setMorningNotes` | — | `unverified` |
| `DAT-33` | deleteNight deletes the session's clip files first, then the session row (cascades), then that session's log | `data/repo/SessionRepository.kt` · `deleteNight` | `SessionLoggerTest.deleteSessionLog removes only that session's log` | `unverified` |
| `DAT-34` | deleteAllData removes every row of all 12 tables in one transaction (triggers dropped for the purge and recreated) and deletes all clip files | `data/db/AppDatabase.kt` · `purgeEverythingIncludingClinical` | — | `unverified` |
| `DAT-35` | delete-all removes every session log | `data/logs/SessionLogger.kt` · `deleteAll` | `SessionLoggerTest.deleteAll removes every log and reports the count` | `tested` |
| `DAT-36` | At every app start the worker is scheduled as unique periodic work retention, period 24 h, policy KEEP (an existing schedule is kept) | `work/RetentionWorker.kt` · `schedule` | — | `unverified` |
| `DAT-37` | A stored retention value is clamped to 1..365 days on write and on every read; an absent key yields the default | `data/prefs/UserPreferences.kt` · `retention` | `PreferenceBoundsTest.retention days clamp to 1-365`<br>`PreferenceBoundsTest.read-time clamp contains legacy out-of-range stored values, defaults only when absent` | `tested` |
| `DAT-38` | Defaults: clips 7 days, sessions 90 days | `data/prefs/UserPreferences.kt` · `DEFAULT_CLIPS_DAYS` | — | `unverified` |
| `DAT-39` | Step 1: clips with createdAt older than now − clipsDays × DAY_MS — in one transaction acoustic_event.clipId is cleared for those clip ids and the clip rows are deleted; then the files are deleted | `work/RetentionWorker.kt` · `doWork` | — | `unverified` |
| `DAT-40` | Steps 2–3b: sessions with startedAt older than now − sessionDays × DAY_MS are deleted (cascades as DAT-09 and DAT-10); session logs older than the same cutoff are purged; raw_payload rows with fetchedAt older than the cutoff are deleted | `work/RetentionWorker.kt` · `doWork` | `SessionLoggerTest.purge removes only files older than the cutoff` | `unverified` |
| `DAT-41` | Step 4: clip files without an audio_clip row are deleted only when no session is open and the file is at least 3 600 000 ms old | `work/RetentionWorker.kt` · `OrphanSweepPolicy.shouldSweep` | `OrphanSweepPolicyTest.sweep runs only when no session is open`<br>`OrphanSweepPolicyTest.freshly written files are never swept even with no open session`<br>`OrphanSweepPolicyTest.the hour boundary itself is sweepable`<br>`ClipStoreTest.old unreferenced orphans are deleted and their bytes freed`<br>`ClipStoreTest.orphans failing the sweep policy survive the sweep` | `tested` |
| `DAT-42` | measurement, clinical_baseline, user_factor, sleep_experiment and model_registry are never touched by retention | `work/RetentionWorker.kt` · `doWork` | — | `unverified` |
| `DAT-43` | No UI control writes the retention settings; the stored defaults apply unless another writer set the keys | `data/prefs/UserPreferences.kt` · `setRetention` | — | `not implemented` |
| `DAT-44` | Backup is off (android:allowBackup="false") and the root, file, database and sharedpref domains are excluded from cloud-backup and device-transfer | `app/src/main/AndroidManifest.xml` · `allowBackup` | — | `unverified` |
| `DAT-45` | saveForDate replaces all factor rows of that date with the given rows (date normalized), atomically in the DAO; other dates are untouched | `data/repo/FactorLogRepository.kt` · `saveForDate` | `FactorLogRepositoryTest.saving a date replaces its previous entries`<br>`FactorLogRepositoryTest.other dates are untouched by a save` | `tested` |
| `DAT-46` | saveFactorForDate upserts one factorKey for the date and leaves the date's other rows; scale kinds are clamped to 1..5, a null value stays null, other keys are untouched | `data/repo/FactorLogRepository.kt` · `saveFactorForDate` | `FactorLogRepositoryTest.single-key upsert preserves the date's other rows`<br>`FactorLogRepositoryTest.single-key upsert overwrites an existing key without duplicating it`<br>`FactorLogRepositoryTest.single-key save clamps scale factors into the 1-5 range`<br>`FactorLogRepositoryTest.scale row with null valueNum stays null - no invented value, no crash` | `tested` |
| `DAT-47` | removeFactorForDate deletes only that key's row for the date | `data/repo/FactorLogRepository.kt` · `removeFactorForDate` | `FactorLogRepositoryTest.removing a key leaves the date's other rows intact`<br>`FactorLogRepositoryTest.removing a key on one date leaves the same key's row on other dates` | `tested` |
| `DAT-48` | Experiment status transitions are SQL-guarded: finish and finishConfirm only ACTIVE → DONE; beginConfirmPhase only DONE → ACTIVE; updateResult only when the status is not ABANDONED; abandon turns any non-ABANDONED row into ABANDONED | `data/db/dao/Daos.kt` · `SleepExperimentDao` | — | `unverified` |
| `DAT-49` | Experiment nights are the trend nights started at or after sinceMs whose grade is A or B and whose detector and score versions equal the registry's current ones; oldest first, at most 60 | `data/repo/SessionRepository.kt` · `experimentPhaseNights` | — | `unverified` |
| `DAT-50` | Clip files are written as session_<id>/evt_<lowercased type>_<startMs>_<n>.wav under the clips root and stored as a path relative to that root | `data/files/ClipStore.kt` · `writeClip` | `ClipStoreTest.relative paths of written clips are session-scoped and resolvable`<br>`ClipStoreTest.written clip is a readable RIFF wave file`<br>`ClipStoreTest.event type is part of the filename` | `tested` |
| `DAT-51` | Session logs are one file per session, session_<id>.log, with ISO-8601 UTC timestamped lines; logging never throws | `data/logs/SessionLogger.kt` · `log` | `SessionLoggerTest.appends timestamped lines and reads them back`<br>`SessionLoggerTest.timestamps are ISO-8601 UTC and stamped at write time`<br>`SessionLoggerTest.logging never throws even when the root is unwritable` | `tested` |

The factor tests run against a fake in-memory DAO, so they pin the repository logic, not the SQL or the @Transaction behaviour. No test executes finalizeSession, the delete paths, the purge or the worker.

## 4.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| Database file name | sleepanalysis.db | `data/db/AppDatabase.kt` · `NAME` | Room database file in the app's databases directory |
| Schema version | 14, 13 migration steps | `data/db/AppDatabase.kt` · `MIGRATIONS` | any entity change needs version 15, a migration and a new schema export |
| Trigger names | trg_clinical_baseline_no_update, trg_clinical_baseline_no_delete | `data/db/AppDatabase.kt` · `TRIGGER_UPDATE` | names used by the purge (drop and recreate) and by the migration test |
| Trigger abort message | clinical baseline is immutable | `data/db/AppDatabase.kt` · `createImmutabilityTriggers` | text of the SQLiteException |
| MODEL_VERSION | energy-heuristic-v3-peakgate | `data/repo/SessionRepository.kt` · `MODEL_VERSION` | default modelVersion of every inserted event; fallback detector version for the snapshot and the registry |
| SnoreScore.FORMULA_VERSION | snore-score-v4 | `domain/scoring/SnoreScore.kt` · `FORMULA_VERSION` | modelVersion of SNORE_SCORE rows, report.scoreVersion, SCORE registry rows |
| RECOVERY_END_GRACE_MS | 60 000 ms | `data/repo/SessionRepository.kt` · `RECOVERY_END_GRACE_MS` | grace added to the last alive moment of a RECOVERED night |
| Recover-finalize loop bound | 5 | `data/repo/SessionRepository.kt` · `finalizeOpenAsRecovered` | maximum open sessions finalized per confirmation |
| DEFAULT_CLIPS_DAYS, DEFAULT_SESSION_DAYS | 7 days, 90 days | `data/prefs/UserPreferences.kt` · `DEFAULT_CLIPS_DAYS` | retention windows when the keys are absent |
| MIN_RETENTION_DAYS, MAX_RETENTION_DAYS | 1 day, 365 days | `data/prefs/UserPreferences.kt` · `MIN_RETENTION_DAYS` | clamp on write and on read |
| DAY_MS | 86 400 000 ms | `work/RetentionWorker.kt` · `DAY_MS` | converts retention days to a cutoff |
| Worker name and period | retention, 24 h | `work/RetentionWorker.kt` · `UNIQUE_NAME` | unique periodic work, policy KEEP |
| OrphanSweepPolicy.MIN_FILE_AGE_MS | 3 600 000 ms | `work/RetentionWorker.kt` · `MIN_FILE_AGE_MS` | minimum age of an unreferenced clip file before it is swept |
| buildTrends maxNights | 7 | `data/repo/SessionRepository.kt` · `buildTrends` | number of nights read for the trends |
| buildInsights maxNights | 14 | `data/repo/SessionRepository.kt` · `buildInsights` | number of nights read for the insights |
| experimentPhaseNights maxNights | 60 | `data/repo/SessionRepository.kt` · `experimentPhaseNights` | cap on nights read for one experiment phase |
| CaptureStats.HOP_MS | one analysis hop (0.5 s per the comment in finalizeSession) | `domain/report/CaptureStats.kt` · `HOP_MS` | captured seconds = (windowsProcessed + windowsSkipped) × HOP_MS / 1000 |
| appliedGain default | 2.5 | `data/db/entity/Entities.kt` · `appliedGain` | stored gain of clips written without a supplied gain and of pre-v11 rows |
| ClipStore.MAX_CLIP_TARGET_DBFS | −1.0 dBFS | `data/files/ClipStore.kt` · `MAX_CLIP_TARGET_DBFS` | peak target of stored clips (section 1) |
| captureStats schema_version | 6, stub 1 | `monitoring/audio/NightStatsMerge.kt` | JSON version read back by CaptureStats.parse |
| versionName fallback | "?" | `data/repo/SessionRepository.kt` · `versionName` | appVersion when the package info is unreadable |

## 4.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| finalize called for a missing or already-finalized session | returns false; nothing is written (endedAt re-checked inside the transaction) | `data/repo/SessionRepository.kt` · `finalizeSession` |
| finalize throws | the transaction rolls back; MonitorService logs finalize_failed and reports false | `monitoring/service/MonitorService.kt` · `endRecording` |
| RECOVERED night with no events | no TIME_IN_BED, SNORE_SHARE_OF_NIGHT or SNORE_SCORE row; SNORE_EVENT_COUNT and ACOUSTIC_EVENT_COUNT are stored as 0 | `data/repo/SessionRepository.kt` · `finalizeSession` |
| Night with no processed window (stop during the start delay) | no SNORE_TIME, share or score row; the snapshot stores null share and score | `data/repo/SessionRepository.kt` · `finalizeSession` |
| Engine stats lost (process death) | captureStats keeps the heartbeat JSON, or {"schema_version":1} when there is none | `data/repo/SessionRepository.kt` · `finalizeSession` |
| Process death between the clip write and the event write | both rows or none (one transaction); the clip file of a missing row becomes an orphan | `data/repo/SessionRepository.kt` · `insertEvent` |
| Capture time base earlier than the session start | offsetMs is clamped to 0; the absolute times are unchanged | `data/repo/EventTimes.kt` · `resolveEventTimes` |
| finalizeOpenAsRecovered with no open session | returns 0 | `data/repo/SessionRepository.kt` · `finalizeOpenAsRecovered` |
| Resume marking or heartbeat write on a finalized session | no-op (endedAt IS NULL guard in the SQL) | `data/db/dao/Daos.kt` · `markResumedAfterInterruption` |
| labelEvent with an unknown event id | no-op | `data/repo/SessionRepository.kt` · `labelEvent` |
| labelEvent while the session is still open | the label is stored; aggregates, validity and snapshot stay untouched until finalize | `data/repo/SessionRepository.kt` · `rewriteSnoreAggregates` |
| Blank morning note | stored as NULL | `data/repo/SessionRepository.kt` · `setMorningNotes` |
| Snapshot refresh requested for an open session | no row is written | `data/repo/SessionRepository.kt` · `upsertReportSnapshot` |
| UPDATE or DELETE on a clinical_baseline row | SQLite ABORT with 'clinical baseline is immutable'; the repository does not catch it | `data/db/AppDatabase.kt` · `createImmutabilityTriggers` |
| Purge fails midway | the transaction rolls back; the triggers are recreated in a finally block | `data/db/AppDatabase.kt` · `purgeEverythingIncludingClinical` |
| Stored retention days out of range (0 or negative) | clamped to 1..365 on write and on every read, so the cutoff can never lie in the future | `data/prefs/UserPreferences.kt` · `sanitizeRetentionDaysOrDefault` |
| Retention run while a session is open | steps 1 to 3b still run; the orphan sweep is skipped entirely | `work/RetentionWorker.kt` · `OrphanSweepPolicy.shouldSweep` |
| Clip file deletion fails in step 1 | the return value is ignored; the file is swept later as an orphan | `work/RetentionWorker.kt` · `doWork` |
| Clip files younger than 1 h whose session was just deleted by retention | they stay until a later sweep | `work/RetentionWorker.kt` · `doWork` |
| Scale factor outside 1..5 saved from the report card | clamped (STRESS, MORNING_RATING); non-scale keys are untouched | `data/repo/FactorLogRepository.kt` · `saveFactorForDate` |
| DAO failure during a single-key factor save | the exception propagates and the previous rows stay | `data/repo/FactorLogRepository.kt` · `mergeForDate` |

## 4.6 Contract boundaries — what a refactor must not break

**Persisted formats**

- The 12 tables of 4.2.3 with camelCase column names. The exported schema `app/schemas/com.sleepanalysis.data.db.AppDatabase/14.json` (identity hash 993422db94085134f9b93949319b53bc) is what Room validates after a migration.
- Enumerations stored as TEXT: sleep_session.status = RECORDING / COMPLETED / INTERRUPTED / RECOVERED; sleep_experiment.status = ACTIVE / DONE / ABANDONED; sleep_stage.source = GARMIN / WEARABLE; quiet_gap.kind = QUIET / UNCAPTURED / NULL; acoustic_event.userLabel = SNORE / COUGH / SPEECH / MOVEMENT / OTHER_NOT_SNORE; acoustic_event.type holds SNORE and OTHER_NOISE from the detector.
- DataStore file settings: integer keys clips_retention_days and session_retention_days.
- Files: database sleepanalysis.db; clips `files/clips/session_<id>/evt_<type>_<startMs>_<n>.wav` with audio_clip.filePath relative to files/clips; logs `files/logs/session_<id>.log`.
- JSON field names: the captureStats keys listed in 4.2.3; report.payload (section 5); experiment result and confirm JSON with schema_version 1.
- The notes token label_corrected=true on rewritten PHONE rows.

**Version strings stored with data**

- energy-heuristic-v3-peakgate (SessionRepository.MODEL_VERSION): default acoustic_event.modelVersion of every inserted event (the service may pass another version, section 1); fallback detector version in report.detectorVersion and in the registry.
- snore-score-v4 (SnoreScore.FORMULA_VERSION): measurement.modelVersion of SNORE_SCORE rows, report.scoreVersion and SCORE registry rows.
- The NIGHT_VALIDITY row carries NightValidity.VERSION as modelVersion (section 5).
- sleep_session.appVersion and report.appVersion hold the package versionName (0.1.0-phase1 in the current build configuration).
- captureStats schema_version 6 (1 for the stub); Room schema version 14 with the identity hash above.

**Platform contracts**

- `app/src/main/AndroidManifest.xml`: android:allowBackup="false" and android:dataExtractionRules="@xml/data_extraction_rules"; `app/src/main/res/xml/data_extraction_rules.xml` excludes the root, file, database and sharedpref domains from both cloud-backup and device-transfer. No fullBackupContent is set (lint DataExtractionRules flags this for minSdk 30).
- WorkManager: the default WorkManagerInitializer is removed in the manifest and SleepAnalysisApp.workManagerConfiguration installs HiltWorkerFactory; the worker is an @HiltWorker running as unique periodic work retention.
- Room: exportSchema = true; the ksp argument room.schemaLocation points at app/schemas in `app/build.gradle.kts`, and that directory is an androidTest asset source.
- DataStore (settings) and the database are app-private; delete-all does not clear the preferences.

**Cross-area interfaces**

- Section 1 (recording): MonitorService calls startSession, markResumedAfterInterruption, insertEvent, saveCaptureProgress, finalizeSession, clipCountForSession, session, findOpenSession and openSession.
- Home and scheduling: HomeViewModel calls finalizeOpenAsRecovered, findOpenSession, latestSession, savedEventCountForSession, observeSessions and validityGradeFor; ScheduledStartReceiver calls openSession.
- Sections 5 and 6 (report): ReportViewModel calls buildReport, labelEvent and setMorningNotes; LabelingScreen calls clipBackedEventsForLabeling and labelEvent; TrendsScreen calls buildTrends; ValidityCalculator reads sessions, quiet gaps and raw payloads through AppDatabase.
- Insights and experiments: InsightsViewModel calls buildInsights; ExperimentTracker writes sleep_experiment through AppDatabase and reads experimentPhaseNights.
- Section 8 (Garmin and Health Connect): the import managers use RawPayloadDao, SleepStageDao, SleepSessionDao.all and MeasurementRecorder.
- History: HistoryScreen calls deleteNight and deleteAllData.
- Python tools read the database file directly: `tools/export_night_csv.py`, `tools/export_fixtures.py`, `tools/night_trends.py`, `tools/night_loudest.py`.

**Safety-net tests**

- `MigrationTest` (instrumented) — run first after any schema or migration change.
- `FactorLogRepositoryTest`, `EventTimesTest`, `ReportSnapshotRefreshTest`, `LabelCorrectedSnoreEstimatesTest`, `OrphanSweepPolicyTest`, `PreferenceBoundsTest`, `ClipStoreTest`, `SessionLoggerTest`.

## 4.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| `data/repo/SessionRepository.kt` · `sessionsOlderThan` | `dead` | 0 production and 0 test references in app/src, tools and docs (scan row confirmed) | `low` — nothing calls it | superseded by the SQL delete `SleepSessionDao.deleteOlderThan` |
| `data/db/dao/Daos.kt` · `allStartTimes` | `dead` | its only caller is sessionsOlderThan | `low` | remove together with it |
| `data/db/dao/Daos.kt` · `historyForKind` | `dead` | 0 production and 0 test references (scan row confirmed) | `none` | |
| `data/db/dao/Daos.kt` · `versionsAt` | `dead` | 0 production and 0 test references (scan row confirmed) | `none` | |
| `data/db/dao/Daos.kt` · `ReportDao.all` | `dead` | no caller through a ReportDao field or AppDatabase.reportDao; 0 tests | `none` | not in the scan (`all` is a common word) |
| `data/repo/BaselineRepository.kt` · `all` | `dead` | BaselineScreen calls only observeAll and add; SessionRepository.buildReport reads ClinicalBaselineDao.all directly | `none` | not in the scan |
| `data/repo/SessionRepository.kt` · `findOpenSession` | `duplicate` | same query as openSession, which only adds Dispatchers.IO; callers — findOpenSession: MonitorService 2 sites, HomeViewModel; openSession: MonitorService 2 sites, ScheduledStartReceiver | `low` — keep one and re-point 6 call sites | |
| `data/repo/SessionRepository.kt` · `garminMetricsForSessionDay` | `duplicate` | the wake-date from/to window is computed here and again in buildTrends | `low` — extract one helper | |
| `data/repo/SessionRepository.kt` · `reportSnapshotHeadline` | `duplicate` | third copy of the snore-seconds, share, tier-seconds and score math with the finalize row conditions (others: finalizeSession, labelCorrectedSnoreEstimates); only the two pure copies are tested | `medium` — finalizeSession itself has no test | make finalize use one pure builder |
| `data/repo/SessionRepository.kt` · `currentModelVersions` | `over-general` | public, but called only by buildInsights and experimentPhaseNights in the same class | `none` | make it private |
| `data/repo/SessionRepository.kt` · `buildInsights` | `over-general` | buildReport, buildTrends, buildInsights and experimentPhaseNights each have exactly one caller (ReportViewModel, TrendsScreen, InsightsViewModel, ExperimentTracker) and only read rows | `low` — pure move | split row gathering from the lifecycle writes |
| `data/repo/BaselineRepository.kt` · `BaselineRepository` | `over-general` | 3-method wrapper, 2 used; the other class that needs baseline rows (SessionRepository) injects the DAO instead | `low` | settle on one access style |
| `di/AppModule.kt` · `provideReportDao` | `dead` | no constructor parameter of type ReportDao, ModelRegistryDao or SleepExperimentDao exists; the three DAOs are reached through AppDatabase accessors | `none` | same for provideModelRegistryDao and provideSleepExperimentDao; SleepStageDao and QuietGapDao have no provider at all |
| `data/prefs/UserPreferences.kt` · `retentionNow` | `dead` | 0 production and 0 test references; equals retention.first() | `none` | |
| `data/prefs/UserPreferences.kt` · `setRetention` | `dead` | 0 production and 0 test callers; the only writer of the two retention keys | `medium` — PRIVACY_AND_REGULATORY §4, STATUS and NEXT_STEPS P1 present it as the plumbing of a pending settings UI | owner decision (4.8) |
| `app/src/main/res/values/strings.xml` · `retention_worker_note` | `dead` | declared only (one grep hit); lint UnusedResources; the text says "settings in a later phase" | `none` | |
| `data/db/entity/Entities.kt` · `EventType.UNKNOWN` | `dead` | 0 references in app/src/main; EventDetector emits only SNORE and OTHER_NOISE; the only other UNKNOWN literal is a Health Connect stage label | `low` — no reader exists, so an old stored row loses nothing | old rows could still hold the value (4.8) |
| `data/db/entity/Entities.kt` · `ExperimentStatus.ABANDONED` | `dead` | the constant has 0 references, but the literal 'ABANDONED' is written by SleepExperimentDao.abandon and compared by updateResult; stored rows can hold it | `low` for the constant; the string is contract (4.6) | reference the constant from the SQL-facing code, or drop the constant |
| `data/db/entity/Entities.kt` · `enteredAt` | `over-general` | never set explicitly and never read in production (defaults to now); only the MigrationTest seed names it | `medium` — schema column: removal needs v15, a migration and a test change | DATA_MODEL §4 rule 3 promises an "entered <date>" caption; BaselineScreen has no such text |
| `data/db/entity/Entities.kt` · `recordedAt` | `over-general` | never set explicitly and never read; only the v3 migration names it | `medium` — schema column (v15) | |
| `data/db/entity/Entities.kt` · `audioBytesTotal` | `over-general` | written at finalize with SUM(audio_clip.sizeBytes); no app reader; read by `tools/export_night_csv.py` | `medium` — schema column (v15) and CSV export contract | the name suggests recorded audio bytes; it holds clip bytes |
| `data/db/entity/Entities.kt` · `createdAt` | `over-general` | defaulted at insert in 7 tables; only audio_clip.createdAt is queried and indexed; the session, measurement and sleep_stage values are read by the exporter; the acoustic_event, quiet_gap and sleep_experiment values have no reader at all | `medium` — 6 schema columns (v15), 3 of them in the CSV export | not free deletions |
| `data/db/entity/Entities.kt` · `labeledAt` | `over-general` | written by setUserLabel; no app reader; read by `tools/export_fixtures.py` | `medium` — schema column and fixture export | |
| `data/db/entity/Entities.kt` · `isClinical` | `over-general` | with method and periodEnd: written as provenance on every measurement row, read by no Kotlin code in app/src/main; the exporter emits them | `high` — provenance contract (ARCHITECTURE §6) and schema columns | keep unless the provenance contract is dropped |
| `data/db/entity/Entities.kt` · `deviceModel` | `over-general` | with osVersion and appVersion: written at startSession, read only by the exporter | `medium` — schema columns and CSV export | |
| `data/db/dao/Daos.kt` · `clearClipIds` | `doc-drift` | the comment says "alongside the FK's ON DELETE SET NULL"; v14 has no foreign key on acoustic_event.clipId, only on sessionId, so this method is the only thing that clears the link | `none` — comment only; the method is live (RetentionWorker) | fix the comment |
| `data/repo/SessionRepository.kt` · `finalizeOpenAsRecovered` | `doc-drift` | the comment calls it "a guard before a new recording starts"; its only caller is HomeViewModel, and MonitorService refuses a start while a session is open instead | `none` — comment only | |
| `docs/DATA_MODEL.md` §3 to §5 | `doc-drift` | every column is written in snake_case (started_at, audio_bytes_total, entered_at) while the schema uses camelCase (startedAt, audioBytesTotal, enteredAt); `model_id`, `updated_at` and `baseline_id` are cited but do not exist in v14 | `none` — documentation | SQL or tools written from this doc get every column name wrong |
| `docs/ARCHITECTURE.md` §6 | `doc-drift` | item 3: a trigger aborts UPDATE/DELETE on measurement rows with method CLINICAL_MEASURED — the triggers guard clinical_baseline only; item 4: the model version is "resolved from ModelRegistry at write time" and `model_id` is cited — no such column, and the registry is written at finalize; item 1: the measurement DAO is "internal" — it is public, Hilt-provided and read directly by SessionRepository and GarminImportManager | `none` — documentation | |
| `docs/PRIVACY_AND_REGULATORY.md` §4 | `doc-drift` | lists "Clips-only purge" as a deletion control; no user-triggered clip purge exists in app/src/main (no hit for purgeClips, deleteClips or clipsOnly); only RetentionWorker removes clips | `none` — documentation | |
| `docs/DATA_MODEL.md` §6 to §7 | `doc-drift` | the retention table omits raw_payload (follows session retention), the cascading sleep_stage and quiet_gap tables and the tables retention never touches; "thirteen real migrations" — 7→8 runs no SQL; the orphan sweep is described without the open-session and 1 h guards | `none` — documentation | |
| `app/build.gradle.kts` sourceSets comment | `doc-drift` | says the migration test replays "1.json…8.json"; the schemas run to 14 | `none` | |
| `data/db/AppDatabase.kt` · `MIGRATION_7_8` | `legacy-compat` | empty migrate(); exists so Room can walk 7→8 | `high` — required by any database created at v7 | keep; listed to make the chain complete |

Notes (non-normative):

- Two DAO access styles coexist: seven DAOs are injected through Hilt (SleepSessionDao, AcousticEventDao, AudioClipDao, MeasurementDao, ClinicalBaselineDao, RawPayloadDao, UserFactorDao) and five are fetched through AppDatabase accessors (SleepStageDao, QuietGapDao, ReportDao, ModelRegistryDao, SleepExperimentDao).
- All sessions are loaded four times through SleepSessionDao.observeAll().first() inside SessionRepository (plus the ungated observeAll flow behind observeSessions), and again through SleepSessionDao.all in three other classes; observeAll is otherwise used only for the History and Home lists.
- The clip-to-event link is stored twice (acoustic_event.clipId and the unique audio_clip.eventId) and is maintained by insertEvent, setEventId and clearClipIds; the CSV exporter joins on either side.
- Measurement rows written at finalize carry no detector version: only the SNORE_SCORE row has a modelVersion.

## 4.8 Open questions for the owner

| # | Question | Type |
|---|---|---|
| 1 | Retention settings: ship the settings UI that would call setRetention (PRIVACY_AND_REGULATORY §4, STATUS and NEXT_STEPS P1 list it as pending), or drop the setter and correct the docs. Today the stored defaults are the only values in effect. | owner decision |
| 2 | Write-only columns (enteredAt, recordedAt, six createdAt columns, audioBytesTotal, labeledAt, method, isClinical, periodEnd): drop them in a schema v15, or keep them as provenance and export fields? enteredAt is also what the documented "entered <date>" caption would need. | owner decision |
| 3 | Keep the clip-to-event link in both tables, or in one? | owner decision |
| 4 | Can stored acoustic_event rows contain the type UNKNOWN from an older build? The detector in this tree writes only SNORE and OTHER_NOISE. | `UNVERIFIED` |
| 5 | finalizeSession, deleteNight, the purge, ClipStore.deleteAll and deleteSessionClips and the retention steps 1 to 3b have no automated test; DOGFOOD_CHECKLIST §4 (retention actually deletes) is unchecked. | `UNVERIFIED` |
| 6 | Migrations are exercised only as the walk 1→14 and as 13→14; other starting versions are not tested on their own. | `UNVERIFIED` |
| 7 | The exact key set of acoustic_event.featuresJson (the entity comment lists six keys for an older detector; DATA_MODEL §5 shows more). | `UNVERIFIED` |
| 8 | Whether the label names passed by the UI (ReportViewModel and LabelingScreen pass the name of their label enum) are exactly the five UserLabel constants. | `UNVERIFIED` |
