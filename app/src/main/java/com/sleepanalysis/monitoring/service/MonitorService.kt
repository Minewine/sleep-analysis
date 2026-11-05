package com.sleepanalysis.monitoring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sleepanalysis.MainActivity
import com.sleepanalysis.R
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.logs.SessionLogger
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.domain.policy.StartDelayPolicy
import com.sleepanalysis.domain.report.CaptureStats
import com.sleepanalysis.monitoring.audio.AudioEngine
import com.sleepanalysis.monitoring.audio.StatsCarry
import com.sleepanalysis.monitoring.audio.withCarry
import com.sleepanalysis.monitoring.detection.SnoreDetector
import com.sleepanalysis.monitoring.schedule.BedtimeNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Overnight foreground service with `microphone` type (docs/ARCHITECTURE.md §4).
 *
 * Battery/thermal trade-offs (documented in README known limitations):
 *  - A partial wake lock is held for the whole session. Continuous AudioRecord capture usually
 *    keeps the SoC's audio path awake anyway, but the lock makes capture reliable across OEMs —
 *    missed audio is the worst failure mode for this product. Battery cost is measured in the
 *    Phase 1 dogfood (quality gate: ≤ 10 %/8 h) and the lock will be dropped if soak tests show
 *    the audio path alone is sufficient.
 *  - Thermal throttling: at MODERATE+ the engine analyzes every 2nd hop (0.5 s → 1.0 s) while
 *    the ring buffer keeps advancing, so clip capture is unaffected.
 */
@AndroidEntryPoint
class MonitorService : Service() {

    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var clipStore: ClipStore
    @Inject lateinit var snoreDetector: SnoreDetector
    @Inject lateinit var stateHolder: MonitorStateHolder
    @Inject lateinit var logger: SessionLogger
    @Inject lateinit var prefs: com.sleepanalysis.data.prefs.UserPreferences

    // A bug in any service coroutine must never kill the process mid-night (night 1 dogfood:
    // an uncaught exception in the heartbeat crashed the whole recording). Log and keep the
    // engine running; the supervisor keeps sibling coroutines alive.
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        logger.log(activeSessionId, "service_coroutine_crash", "${t.javaClass.simpleName}: ${t.message}")
        Log.e(TAG, "service coroutine crashed", t)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashHandler)

    /**
     * Event/clip persistence outlives [serviceScope]: onDestroy cancels the service scope after
     * the engine's final flush, and events delivered by that flush must still reach the DB.
     * Fire-and-forget scope that is intentionally never cancelled (idle threads retire).
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    /**
     * The active engine. @Volatile and assigned BEFORE start(): the capture thread delivers
     * events as soon as audio starts, and it reads the engine's capture-time base — a
     * not-yet-visible field would silently fall back to the session start and backdate events.
     */
    @Volatile private var engine: AudioEngine? = null

    /**
     * Serializes begin/end so a sticky restart, a user tap, and a capture error can never
     * interleave two engines or two finalizations (check-then-act on [stateHolder] alone is
     * not atomic across the binder thread and the Default dispatcher).
     */
    private val beginEndMutex = kotlinx.coroutines.sync.Mutex()

    /** Cumulative counters from heartbeat-saved stats of earlier engines of this session. */
    @Volatile private var statsCarry: StatsCarry? = null

    @Volatile private var activeSessionId = -1L
    @Volatile private var sessionStartEpochMs = 0L

    /** When audio capture actually began (after the start delay) — persisted via capture stats. */
    @Volatile private var captureStartAtMs = 0L
    @Volatile private var activeModelVersion = SessionRepository.MODEL_VERSION

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (startCommandFor(intent?.action)) {
            StartCommand.STOP -> serviceScope.launch {
                endRecording(SessionStatus.COMPLETED, intent?.getStringExtra(EXTRA_STOP_SOURCE) ?: SOURCE_MANUAL)
            }
            StartCommand.START_NEW_SESSION -> beginRecording(
                existingSessionId = null,
                source = intent?.getStringExtra(EXTRA_START_SOURCE) ?: SOURCE_MANUAL,
            )
            StartCommand.RESUME_OPEN_SESSION -> serviceScope.launch { resumeInterruptedSession() }
            StartCommand.SURFACE_INTERRUPTED -> serviceScope.launch { surfaceInterruptionOrStop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // System kill: stop capture. The session row stays open and is surfaced by the Home
        // interrupted-night banner (HomeViewModel.init), where the user finalizes or resumes
        // it — never silently auto-recovered.
        runCatching { engine?.stop() }
        engine = null
        unregisterThermalListener()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun beginRecording(existingSessionId: Long?, source: String = SOURCE_MANUAL) {
        // Pure in-memory re-check FIRST: a redundant start while already Recording/Starting/
        // Delayed must return WITHOUT re-promoting — a fresh startForeground() would re-post
        // the FGS notification with a new setWhen and reset the chronometer mid-night. The
        // authoritative re-check inside the lock below still covers the racy arrivals.
        val current = stateHolder.state.value
        if (current is MonitorState.Recording || current is MonitorState.Starting ||
            current is MonitorState.Delayed
        ) return

        // Foreground promotion must happen promptly after startForegroundService() — and before
        // ANY early return below: stopSelf() without a prior startForeground() is a
        // RemoteServiceException on API 26+, and the mic-permission/storage bails below are
        // reachable (permission revoked after onboarding, storage filled up). Without
        // RECORD_AUDIO [startInForeground] promotes type-less on API < 34 and skips the
        // promotion entirely on API 34+ (it would throw there) — the mic bail below then stops
        // the service, which is crash-safe in both cases (see [startInForeground]).
        startInForeground(System.currentTimeMillis())

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // This is also the promotion-skipped path on API 34+ (no startForeground happened
            // there at all — see [startInForeground]): stopSelf() is crash-safe on 34+ (Android
            // 12+ tolerates it after startForegroundService()), and below 34 the type-less
            // promotion is removed again by stopForegroundStop() first.
            stateHolder.set(MonitorState.StartError(getString(R.string.onboarding_mic_rationale)))
            stopForegroundStop()
            stopSelf()
            return
        }
        val stat = StatFs(filesDir.absolutePath)
        if (stat.availableBytes < MIN_FREE_BYTES) {
            stateHolder.set(MonitorState.StartError(getString(R.string.service_error_low_storage)))
            stopForegroundStop()
            stopSelf()
            return
        }

        // Foreground promotion already happened (top of this method); the Starting state itself
        // is set here, inside the locked body (after the authoritative re-check).
        serviceScope.launch {
            beginEndMutex.withLock {
                // Authoritative re-check: another start may hold/have held the lock first.
                val current = stateHolder.state.value
                if (current is MonitorState.Recording || current is MonitorState.Starting ||
                    current is MonitorState.Delayed
                ) return@withLock
                stateHolder.set(MonitorState.Starting)
                try {
                    // Master switch (design: recording controls) — defense in depth: Home disables
                    // the button and the scheduler never arms when it is off, and
                    // [resumeInterruptedSession] applies the same switch to the interrupted-
                    // notification resume. Only gates starting, never force-stops an active night.
                    if (existingSessionId == null && !prefs.recordingEnabledNow()) {
                        Log.w(TAG, "start blocked: recording is disabled (source=$source)")
                        stateHolder.set(MonitorState.Idle)
                        stopForegroundStop()
                        stopSelf()
                        return@withLock
                    }
                    if (existingSessionId == null) {
                        // Never allow two open sessions: finalize anything stale from a killed process.
                        val stale = sessionRepository.finalizeOpenAsRecovered()
                        if (stale > 0) logger.log(-1L, "stale_session_finalized", "count=$stale")
                    }
                    val sessionId = existingSessionId ?: sessionRepository.startSession()
                    val session = sessionRepository.session(sessionId)
                    if (session == null) {
                        stateHolder.set(MonitorState.StartError(getString(R.string.service_error_session_missing)))
                        stopForegroundStop()
                        stopSelf()
                        return@withLock
                    }
                    if (session.endedAt != null) {
                        // A concurrent finalize (banner confirm / stale cleanup) won the race for
                        // this session — recording into a closed row would strand its data.
                        logger.log(sessionId, "start_aborted", "reason=session_already_finalized")
                        stateHolder.set(MonitorState.Idle)
                        stopForegroundStop()
                        stopSelf()
                        return@withLock
                    }
                    activeSessionId = sessionId
                    sessionStartEpochMs = session.startedAt
                    // Resuming after process death: carry the counters the heartbeat persisted for
                    // earlier engines of this session, and count already-saved clips against the
                    // per-night clip cap (otherwise a resumed engine gets a fresh 50-clip budget).
                    statsCarry = if (existingSessionId != null) {
                        StatsCarry.from(CaptureStats.parse(session.captureStats))
                    } else {
                        null
                    }
                    if (existingSessionId != null) {
                        // Explicit, report-visible provenance: this night was interrupted and is
                        // being resumed. The RECOVERED status only covers a death with no resume.
                        sessionRepository.markResumedAfterInterruption(sessionId)
                        logger.log(sessionId, "session_resumed", "after=process_death")
                    }
                    acquireWakeLock()
                    registerThermalListener()
                    logger.log(
                        sessionId, "recording_start",
                        "source=$source storageFreeMB=${freeStorageMb()} thermal=${thermalName()}" +
                            (statsCarry?.let { " resumed windows=${it.windowsProcessed}" } ?: "")
                    )
                    BedtimeNotification.cancel(this@MonitorService) // one-tap bedtime flow consumed
                    InterruptedNotification.cancel(this@MonitorService) // resume no longer pending

                    // Configurable start delay (night-2 dogfood idea): the session and time-in-bed
                    // begin at the tap, but audio capture waits so falling-asleep noise stays out
                    // of events and clips. The wait runs OUTSIDE the begin/end mutex so Stop,
                    // the scheduled end, and finalize remain responsive during it.
                    val delayMinutes = prefs.startDelayMinutesNow()
                    val captureStartAt = StartDelayPolicy.captureStartAtMs(delayMinutes, session.startedAt)
                    val remainingMs = captureStartAt - System.currentTimeMillis()
                    if (remainingMs > 0) {
                        logger.log(sessionId, "start_delay", "minutes=$delayMinutes captureStartsAt=$captureStartAt")
                        stateHolder.set(MonitorState.Delayed(sessionId, session.startedAt, captureStartAt))
                        updateDelayNotification(captureStartAt)
                        val existing = existingSessionId
                        serviceScope.launch {
                            delay(remainingMs)
                            beginCapture(existing, sessionId, captureStartAt)
                        }
                        return@withLock
                    }
                    beginCaptureLocked(existingSessionId, sessionId, captureStartAt)

                    if (existingSessionId != null) {
                        // The session predates this engine — count the notification from its start.
                        updateChronometer(session.startedAt)
                    }
                } catch (t: Throwable) {
                    statsCarry = null
                    releaseWakeLock()
                    logger.log(activeSessionId, "start_failed", "${t.javaClass.simpleName}: ${t.message}")
                    stateHolder.set(MonitorState.StartError(t.message ?: "Failed to start recording"))
                    stopForegroundStop()
                    stopSelf()
                }
            }
        }
    }

    /**
     * Starts the actual audio engine. Called either inline from [beginRecording] (no delay) or
     * after the delay elapses — always with [beginEndMutex] held. Re-validates the world first:
     * during the delay a Stop, the scheduled end, or a finalize may have closed the session.
     */
    private suspend fun beginCaptureLocked(existingSessionId: Long?, sessionId: Long, captureStartAtMs: Long) {
        val session = sessionRepository.session(sessionId)
        if (session == null || session.endedAt != null) {
            // A concurrent finalize (stop during delay, banner confirm) won the race —
            // recording into a closed row would strand its data.
            logger.log(sessionId, "capture_start_aborted", "reason=session_finalized")
            stateHolder.set(MonitorState.Idle)
            stopForegroundStop()
            stopSelf()
            return
        }
        val debugEnter1 = prefs.debugEnterWindows1Now()
        activeModelVersion = if (debugEnter1) "${SessionRepository.MODEL_VERSION}+dbg1" else SessionRepository.MODEL_VERSION
        val initialClipCount = if (existingSessionId != null) {
            sessionRepository.clipCountForSession(sessionId)
        } else {
            0
        }
        val newEngine = AudioEngine(
            sessionId = sessionId,
            detector = snoreDetector,
            clipStore = clipStore,
            listener = engineListener,
            config = AudioEngine.Config(
                enterWindows = if (debugEnter1) 1 else com.sleepanalysis.monitoring.detection.DetectorConfig.ENTER_WINDOWS,
                initialClipCount = initialClipCount,
            ),
        )
        // Visible to the capture thread before the first samples arrive (see field docs).
        engine = newEngine
        this.captureStartAtMs = captureStartAtMs
        newEngine.start()
        // The thermal listener was registered before this engine existed, and the system does
        // not re-broadcast the current status — a phone already ≥ MODERATE would record
        // unthrottled until the next status change. Apply it to the fresh engine now.
        applyCurrentThermalStatusToEngine()
        logger.log(sessionId, "detector_config", "enterWindows=${if (debugEnter1) 1 else 2} model=$activeModelVersion")
        startHeartbeat()
        stateHolder.set(MonitorState.Recording(sessionId, session.startedAt))
        updateChronometer(session.startedAt)
    }

    /**
     * Runs after the start delay elapses. The mutex was released for the wait, so the world may
     * have moved on: only proceed when the state is still Delayed for THIS session with THIS
     * capture start (a stop during the delay sets Finished/Idle; a sticky restart re-enters
     * beginRecording with a freshly computed capture start).
     */
    private suspend fun beginCapture(existingSessionId: Long?, sessionId: Long, expectedCaptureStartAt: Long) {
        beginEndMutex.withLock {
            val current = stateHolder.state.value
            if (!canBeginCaptureAfterDelay(current, sessionId, expectedCaptureStartAt)) {
                logger.log(sessionId, "capture_start_skipped", "state=${current::class.simpleName}")
                return@withLock
            }
            beginCaptureLocked(existingSessionId, sessionId, expectedCaptureStartAt)
        }
    }

    private suspend fun endRecording(status: String, source: String = SOURCE_MANUAL) {
        beginEndMutex.withLock {
            heartbeatJob?.cancel()
            heartbeatJob = null
            // A stop during the start delay: session closes with zero capture — say so in the log.
            if (stateHolder.state.value is MonitorState.Delayed) {
                logger.log(activeSessionId, "start_delay_cancelled", "source=$source")
            }
            val e = engine
            // Cumulative stats (earlier engines of this session + this engine) for both the log
            // and the session row; the DB remains the source of truth for clip counts/bytes.
            val stats = e?.stats()?.withCarry(statsCarry)
            statsCarry = null
            // stop() flushes still-open events on the capture thread; the listener reads
            // [engine] for their time base, so the field is nulled only after it returns.
            e?.stop()
            unregisterThermalListener()
            releaseWakeLock()

            val sessionId = activeSessionId
            activeSessionId = -1
            engine = null
            if (sessionId > 0) {
                stats?.let {
                    logger.log(
                        sessionId, "recording_stop",
                        "source=$source status=$status windows=${it.windowsProcessed} skipped=${it.windowsSkipped} " +
                            "clips=${it.clipCount} runClipBytes=${it.clipBytesTotal} gateSkip=${it.levelGateSkipped} " +
                            "thermalEvents=${it.thermalThrottleEvents} " + batteryTelemetry()
                    )
                }
                val finalized = try {
                    sessionRepository.finalizeSession(
                        sessionId, status, stats, activeModelVersion,
                        captureStartAtMs = captureStartAtMs,
                    )
                } catch (t: Throwable) {
                    logger.log(sessionId, "finalize_failed", "${t.javaClass.simpleName}: ${t.message}")
                    false
                }
                if (finalized) {
                    stateHolder.set(MonitorState.Finished(sessionId))
                } else {
                    // Another owner (banner confirm, stale cleanup) already closed the row —
                    // do not present this stop as a fresh report.
                    logger.log(sessionId, "finalize_skipped", "reason=already_finalized")
                    stateHolder.set(MonitorState.Idle)
                }
            } else {
                stateHolder.set(MonitorState.Idle)
            }
            stopForegroundStop()
            stopSelf()
        }
    }

    /**
     * START_STICKY restart after process death (null intent). Capture is deliberately NOT
     * auto-resumed: a microphone foreground service started from the background gets no
     * while-in-use mic access on Android 11+ — AudioRecord initializes cleanly and records
     * silence, so `onCaptureError` never fires and the night completes empty. Instead the open
     * session stays open (the Home interrupted-night banner offers finalize) and a
     * high-priority notification asks the user to tap to resume/restart — the tap is the
     * user-interaction path that grants mic access.
     *
     * The sticky restart of a previously-foreground service is delivered with
     * startForegroundService() semantics, so the service MUST call startForeground() before it
     * stops ("did not then call startForeground" crash on API 26+): [startInForeground] runs
     * first, and [stopForegroundStop] removes the FGS notification again before [stopSelf] —
     * the high-priority interrupted notification (id 1043) survives as the user-facing signal.
     */
    private suspend fun surfaceInterruptionOrStop() {
        startInForeground(System.currentTimeMillis())
        val open = sessionRepository.findOpenSession()
        if (open != null && activeSessionId < 0) {
            InterruptedNotification.show(this)
        }
        stopForegroundStop()
        stopSelf()
    }

    /**
     * Interrupted-notification Resume action: the user-tap path that both grants mic access
     * (Android 11+) and keeps the same-session resume plumbing (statsCarry,
     * markResumedAfterInterruption) intact. Gated by [resumeDecision].
     */
    private suspend fun resumeInterruptedSession() {
        val open = sessionRepository.findOpenSession()
        when (resumeDecision(openSessionId = open?.id ?: 0L, recordingEnabled = prefs.recordingEnabledNow())) {
            ResumeDecision.RESUME -> open?.let {
                beginRecording(existingSessionId = it.id, source = SOURCE_RESUMED)
            }
            ResumeDecision.BLOCKED -> {
                // Master switch gates manual starts, scheduled starts AND resume: with recording
                // disabled capture must not start. The session stays open and the interrupted
                // notification stays up — it remains the user's re-entry point, and the Home
                // banner flow (finalize/resume) still works. A lingering tap must never stop the
                // service under a live engine, though (beginRecording's re-check would have
                // returned early instead).
                Log.w(TAG, "resume blocked: recording is disabled (session=${open?.id})")
                val current = stateHolder.state.value
                if (current !is MonitorState.Recording && current !is MonitorState.Starting &&
                    current !is MonitorState.Delayed
                ) {
                    stopSelf()
                }
            }
            ResumeDecision.STALE -> {
                // The user tapped Resume after the session was already finalized elsewhere
                // (banner confirm, stale cleanup): nothing to resume — drop the stale banner.
                Log.w(TAG, "resume ignored: no open session (already finalized)")
                InterruptedNotification.cancel(this)
                stopSelf()
            }
        }
    }

    private val engineListener = object : AudioEngine.Listener {
        override fun onEventFinalized(
            draft: com.sleepanalysis.monitoring.detection.EventDetector.EventDraft,
            clipRelativePath: String?,
            clipDurationMs: Long,
            clipSizeBytes: Long,
        ) {
            val sessionId = activeSessionId
            // Absolute-time base: the epoch of THIS engine's first sample. draft.startMs is
            // relative to the engine; adding the session start would backdate events of any
            // engine resumed after process death (night 1 dogfood bug).
            val captureTimeBase = engine?.captureStartEpochMs()?.takeIf { it > 0 }
                ?: sessionStartEpochMs
            val sessionStart = sessionStartEpochMs
            if (sessionId <= 0) return
            persistScope.launch {
                runCatching {
                    sessionRepository.insertEvent(
                        sessionId = sessionId,
                        sessionStartEpochMs = sessionStart,
                        captureTimeBaseEpochMs = captureTimeBase,
                        draft = draft,
                        clipRelativePath = clipRelativePath,
                        clipDurationMs = clipDurationMs,
                        clipSizeBytes = clipSizeBytes,
                        modelVersion = activeModelVersion,
                    )
                }.onFailure {
                    logger.log(sessionId, "event_persist_failed", "${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }

        override fun onCaptureError(throwable: Throwable) {
            logger.log(activeSessionId, "capture_error", "${throwable.javaClass.simpleName}: ${throwable.message}")
            serviceScope.launch { endRecording(SessionStatus.INTERRUPTED) }
        }

        override fun onLog(event: String, detail: String) {
            logger.log(activeSessionId, event, detail)
        }
    }

    /**
     * Promotes the service to foreground with the recording notification, so a bail path's
     * stopSelf() can never trigger the RemoteServiceException("did not call startForeground")
     * crash on API 26+. Without RECORD_AUDIO the promotion splits by API level:
     *  - API < 34: promotes type-less (`0`) — legal pre-34 — and the caller's mic-permission
     *    bail stops the service right after.
     *  - API 34+: type-less would be fatal — ServiceCompat passes the type straight through
     *    (Api34Impl), and on API 34+ with targetSdk 34+ the platform requires at least one
     *    manifest-declared type / resolves `microphone`, throwing (SecurityException) without
     *    RECORD_AUDIO. So NO startForeground happens at all; the caller's mic bail stops the
     *    service instead, which is safe there: Android 12 (S) and above tolerate stopSelf()
     *    after startForegroundService() without a prior startForeground().
     */
    private fun startInForeground(startedAt: Long) {
        val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted && Build.VERSION.SDK_INT >= 34) return
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildRecordingNotification(startedAt),
            if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    /**
     * Re-posts the recording notification with the chronometer anchored to [startedAt]. After a
     * sticky resume the session predates the new engine — the timer must count the whole night.
     */
    private fun updateChronometer(startedAt: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, buildRecordingNotification(startedAt)
            )
        }
    }

    /**
     * During the start delay: a live countdown to audio capture (chronometer counting DOWN from
     * [captureStartsAtMs], with the absolute time as text fallback for launchers that don't
     * render countdowns). Mic access is already held — only the reading is deferred.
     */
    private fun updateDelayNotification(captureStartsAtMs: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, buildDelayNotification(captureStartsAtMs)
            )
        }
    }

    /**
     * Content intent for the monitoring notifications: tapping reopens the app. Built with the
     * same immutable pattern as [com.sleepanalysis.monitoring.schedule.BedtimeNotification].
     */
    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildDelayNotification(captureStartsAtMs: Long): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val startsAt = java.time.Instant.ofEpochMilli(captureStartsAtMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_delay_title))
            .setContentText(getString(R.string.notif_delay_text, startsAt))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(captureStartsAtMs)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_stop_action), stopIntent)
            .build()
    }

    private fun buildRecordingNotification(startedAt: Long): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_stop_action), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).also {
            it.acquire(MAX_SESSION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun registerThermalListener() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            val throttled = ThermalPolicy.isThrottled(status)
            engine?.setThrottled(throttled)
            logger.log(
                activeSessionId, "thermal",
                "status=${thermalName(status)} throttled=$throttled"
            )
        }
        pm.addThermalStatusListener(mainExecutor, listener)
        thermalListener = listener
        applyCurrentThermalStatusToEngine()
    }

    /**
     * Applies the CURRENT thermal status to the active engine: the system does not re-broadcast
     * the status when a listener registers, and the engine is created after registration —
     * without this a phone already ≥ MODERATE records unthrottled until the next status change.
     */
    private fun applyCurrentThermalStatusToEngine() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        engine?.setThrottled(ThermalPolicy.isThrottled(pm.currentThermalStatus))
    }

    private fun unregisterThermalListener() {
        thermalListener?.let {
            runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .removeThermalStatusListener(it)
            }
        }
        thermalListener = null
    }

    private fun stopForegroundStop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Every 15 minutes: windows progress + free storage, so mid-night stalls are diagnosable.
     * Also persists cumulative stats into the session row — if the process dies and START_STICKY
     * resumes the session, the counters of engines before the death are carried forward instead
     * of being lost. A stats failure must never take down the heartbeat (or the process): each
     * beat is individually guarded.
     */
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching {
                    val e = engine ?: return@runCatching
                    val s = e.stats().withCarry(statsCarry)
                    val freeMb = freeStorageMb()
                    val warn = if (freeMb < STORAGE_WARN_MB) " STORAGE_LOW" else ""
                    logger.log(
                        activeSessionId, "heartbeat",
                        "windows=${s.windowsProcessed} skipped=${s.windowsSkipped} " +
                            "clips=${s.clipCount} gateSkip=${s.levelGateSkipped} freeMB=$freeMb " +
                            batteryTelemetry() + warn
                    )
                    val sessionId = activeSessionId
                    if (sessionId > 0) {
                        sessionRepository.saveCaptureProgress(sessionId, s, captureStartAtMs)
                    }
                }.onFailure {
                    logger.log(activeSessionId, "heartbeat_failed", "${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
    }

    private fun freeStorageMb(): Long = runCatching {
        StatFs(filesDir.absolutePath).availableBytes / (1024 * 1024)
    }.getOrDefault(-1L)

    /**
     * Battery percentage and charging state for the session log. The ≤ 10 %/8 h quality gate
     * (MVP_SCOPE A3) was never measurable because nothing recorded the level — every
     * heartbeat and the stop line now carry it, so a night's drain is one grep away.
     */
    private fun batteryTelemetry(): String = runCatching {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        "batt=${level}% charging=$charging"
    }.getOrDefault("batt=?%")

    private fun thermalName(status: Int = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus): String =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            else -> "EMERGENCY"
        }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIFICATION_ID = 41
        private const val WAKELOCK_TAG = "SleepAnalysis::capture"
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val MAX_SESSION_MS = 14L * 60 * 60 * 1000 // wake-lock safety bound
        private const val HEARTBEAT_INTERVAL_MS = 15L * 60 * 1000
        private const val STORAGE_WARN_MB = 500L

        const val ACTION_START = "com.sleepanalysis.action.START"
        const val ACTION_STOP = "com.sleepanalysis.action.STOP"

        /** Resume the open interrupted session (interrupted-notification action; user-tap path). */
        const val ACTION_RESUME = "com.sleepanalysis.action.RESUME"

        /** Optional provenance on start/stop intents ("manual" default, "schedule", "resumed"). */
        const val EXTRA_START_SOURCE = "start_source"
        const val EXTRA_STOP_SOURCE = "stop_source"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_SCHEDULE = "schedule"
        const val SOURCE_RESUMED = "resumed"

        fun start(context: Context, source: String = SOURCE_MANUAL) {
            val intent = Intent(context, MonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_START_SOURCE, source)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, source: String = SOURCE_MANUAL) {
            context.startService(
                Intent(context, MonitorService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_STOP_SOURCE, source)
            )
        }
    }
}
