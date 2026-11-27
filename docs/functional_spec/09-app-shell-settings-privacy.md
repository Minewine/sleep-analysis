# 9. App shell, settings, secondary screens, privacy and build

**Scope:** app entry (`app/src/main/java/com/sleepanalysis/MainActivity.kt`, `app/src/main/java/com/sleepanalysis/SleepAnalysisApp.kt`), `app/src/main/java/com/sleepanalysis/ui/AppRoot.kt`, `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt`, `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt`, `app/src/main/java/com/sleepanalysis/ui/history/HistoryScreen.kt`, ~~app/src/main/java/com/sleepanalysis/ui/baseline/BaselineScreen.kt~~, ~~app/src/main/java/com/sleepanalysis/ui/factorlog/FactorLogScreen.kt~~ (both removed in consolidation T7), `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt`, `app/src/main/java/com/sleepanalysis/ui/theme/Theme.kt`, `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` (structure + ~~debug toggles~~ (removed in the 2026-11-22 consolidation — T3) + noise-filter UI), `app/src/main/AndroidManifest.xml`, the Gradle files, `ci/permission-allowlist.txt`, `.github/workflows/ci.yml`. Cross-references: recording-control settings (master switch, schedule switch/times, start delay) → section 1; Garmin key / Garmin import / Health Connect import UI → section 8; noise-filter effect on the report → section 6.
**Requirement prefix:** APP
**Status values:** `tested` = a unit/instrumented test asserts it · `device-verified` = evidence from a real night or device run exists · `unverified` = implemented, no test and no device evidence · `not implemented` = designed or documented only.

## N.1 What the user gets

1. A dark-by-default single-activity Compose app: Hilt injects the activity, edge-to-edge is enabled, and the theme follows the system (dark unless the system is explicitly light). [APP-01]
2. First launch shows a scrollable disclaimer ("not a medical device", estimates can be wrong, see a doctor for symptoms) plus what-is-recorded and phone-placement notes; tapping "I understand — accept" requests notification permission (Android 13+) and then microphone permission, and stores acceptance in DataStore. [APP-03]
3. Until acceptance is stored the app shows only onboarding; after it, the navigation graph with Home as start destination. [APP-02]
4. History lists every recorded night newest-first with start time, duration (or "in progress") and status; tapping a card opens the report; a per-row delete icon and a "Delete all" action each ask for confirmation first. [APP-06][APP-07][APP-08]
5. A clinical baseline screen: read-only list of the user's own PSG lab results plus an add dialog; entries are insert-only — "correcting" means adding a new entry; the night report shows them for comparison. [APP-09][APP-10][APP-11]
6. A daily factor log: any calendar date, seven boolean lifestyle factors (alcohol with units, late meal, caffeine after 14:00, exercise, medication/device, sick, slept on back), stress and morning-rating 1–5 chips, and free-text notes; Save replaces that date's rows. [APP-12]
7. A Settings screen ordered: recording controls, Garmin API key, Garmin import, Health Connect import, a standing disclaimer card, and a debug card with two experiment toggles (enter-1-windows, skip capture wakelock) — the toggles were removed in consolidation T3; the debug card is gone. [APP-16][APP-17 (removed)][APP-18 (removed)]
8. Privacy posture: no cloud backup or device-transfer of any storage domain, an ongoing microphone notification while recording, app-private storage, and deletion controls (one night / everything including the clinical baseline). [APP-21][APP-22][APP-08]
9. Build-time privacy gates that fail the build: a permission allowlist, forbidden medical-claim terms in strings, a personal-data git-hygiene check, and a single-network-host allowlist; CI runs all four plus unit tests, lint, an emulator migration test and the Python tooling on every PR. [APP-23][APP-24][APP-25][APP-26][APP-27]

## N.2 How it works today

### N.2.1 Components

| Component | File | Responsibility |
|---|---|---|
| `SleepAnalysisApp` | `app/src/main/java/com/sleepanalysis/SleepAnalysisApp.kt` | `@HiltAndroidApp`; `Configuration.Provider` with `HiltWorkerFactory` for on-demand WorkManager; `onCreate` schedules the retention worker once per process |
| `MainActivity` | `app/src/main/java/com/sleepanalysis/MainActivity.kt` | `@AndroidEntryPoint` `ComponentActivity`; `enableEdgeToEdge()`; sets `SleepAnalysisTheme { AppRoot() }` |
| `AppRoot` / `MainViewModel` | `app/src/main/java/com/sleepanalysis/ui/AppRoot.kt` | Maps `UserPreferences.disclaimerAcceptedAt` to null / false / true: spinner, onboarding, nav graph |
| `AppNavHost` / `Routes` | `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt` | All 8 routes (10 before consolidation T7 removed the baseline and dailylog routes), start destination `home` |
| `OnboardingScreen` / `OnboardingViewModel` | `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt` | Disclaimer + permission requests + `acceptDisclaimer()` |
| `HistoryScreen` / `HistoryViewModel` | `app/src/main/java/com/sleepanalysis/ui/history/HistoryScreen.kt` | Session list, delete night / delete all with confirmations |
| ~~BaselineScreen / BaselineViewModel / AddBaselineDialog~~ | ~~app/src/main/java/com/sleepanalysis/ui/baseline/BaselineScreen.kt~~ | removed in consolidation T7 — insert-only clinical baseline list + entry dialog; file deleted |
| ~~FactorLogScreen / FactorLogViewModel / FactorLogUiState~~ | ~~app/src/main/java/com/sleepanalysis/ui/factorlog/FactorLogScreen.kt~~ | removed in consolidation T7 — daily factor form (the factor-log repository and the report's morning-card editing survive) |
| `Formatting` | `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt` | Date/time/duration/dBFS/minutes-of-day helpers; two `Locale.getDefault()` formatters frozen at class init (Lint `ConstantLocale`) |
| `SleepAnalysisTheme` | `app/src/main/java/com/sleepanalysis/ui/theme/Theme.kt` | Hardcoded Material3 dark + minimal light palette; dark unless `isSystemInDarkTheme()` is false |
| `SettingsScreen` / `SettingsViewModel` | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` | Card list (~~incl. debug toggles~~ — removed in consolidation T3); permission gates refreshed on every ON_RESUME |
| recording notification | `app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt` | The mic-active indicator: ongoing FGS notification, channel `monitoring`, id 41 |
| check tasks | `app/build.gradle.kts` | `checkPermissionAllowlist`, `checkForbiddenTerms`, `checkRepoHygiene`, `checkNetworkHosts`, `evalFixtures`; all wired into `:app:check` |

### N.2.2 Flows

#### Flow: start-up and gating
1. The launcher activity (`app/src/main/java/com/sleepanalysis/MainActivity.kt`) enables edge-to-edge and composes the app root (`app/src/main/java/com/sleepanalysis/ui/AppRoot.kt`), whose view model collects the DataStore flow UserPreferences.disclaimerAcceptedAt.
2. Null (DataStore still loading) → centered spinner; false → the onboarding screen (`app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt`); otherwise → the nav graph (`app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt`).
3. The application class (`app/src/main/java/com/sleepanalysis/SleepAnalysisApp.kt`) schedules the retention worker at every process start (the worker itself is section 3's scope).

#### Flow: first launch / disclaimer
1. The onboarding screen renders the `onboarding_disclaimer` string (wellness/research tool, not a medical device, no apnea diagnosis, estimates unreliable, see a clinician if loud snoring / witnessed pauses / gasping / daytime sleepiness — speak to a doctor), plus "What this app records" and "Placement matters" sections. (The string was reworded in consolidation T8; the code wins.)
2. Accept button: on API 33+ the notification launcher requests POST_NOTIFICATIONS; its result callback (regardless of grant outcome) launches the microphone launcher for RECORD_AUDIO; on API < 33 the mic request is launched directly.
3. The mic launcher's result callback calls `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt` · `accept` → `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `acceptDisclaimer` → DataStore key `disclaimer_accepted_at` = current epoch millis. The write happens when the mic dialog completes — even if the user denied mic permission; the missing permission is instead surfaced by Home's pre-flight checklist.
4. The app root then recomposes into the nav graph. There is no in-app way to revoke acceptance: `acceptDisclaimer` has no other caller and nothing clears the key (delete-all touches DB/files/logs only).

#### Flow: navigation (route table)

| Route | Arguments | Navigated from | Navigates to |
|---|---|---|---|
| `home` (start) | — | — | `report/{id}`, `history`, `settings` (`baseline` and `dailylog` — removed in consolidation T7) |
| `report/{sessionId}` | `Long` | home card, history card, trends, insights | back (pop); `report/{id}/log`; `report/{id}/label` |
| `report/{sessionId}/log` | `Long` | report | back |
| `report/{sessionId}/label` | `Long` | report | back |
| `history` | — | home | back; `report/{id}`, `trends`, `insights` |
| `trends` | — | history | back; `report/{id}` |
| `insights` | — | history | back; `report/{id}` |
| ~~baseline~~ | — | ~~home~~ | removed in consolidation T7 |
| `settings` | — | home | back |
| ~~dailylog~~ | — | ~~home~~ | removed in consolidation T7 |

Sub-screens read the session id from their own back-stack entry; the report, labeling and session-log screens receive it via their nav arguments / Hilt saved-state.

#### Flow: delete one night / everything
1. Delete icon → confirmation dialog → `app/src/main/java/com/sleepanalysis/ui/history/HistoryScreen.kt` · `deleteNight` → `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `deleteNight`: clip files first (so rows cannot outlive clips) → session row delete (child rows cascade) → session log delete; snackbar confirms.
2. "Delete all" → confirmation dialog → `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `deleteAllData`: `app/src/main/java/com/sleepanalysis/data/db/AppDatabase.kt` · `purgeEverythingIncludingClinical` (deletes every row in one transaction; before consolidation T7 it also dropped and recreated the two baseline immutability triggers) → all clips deleted → all session logs deleted; snackbar confirms.

#### Flow: ~~clinical baseline entry~~ — removed in consolidation T7
1. ~~The baseline screen lists every row of the baseline repository ...~~ through 4. ~~Consumption: buildReport passes all baseline rows into the report snapshot (ReportAssembler.baseline), and the report screen renders them as a comparison section.~~ — gone: BaselineScreen, BaselineRepository, ClinicalBaselineDao, the clinical_baseline table and both immutability triggers were dropped (migration 15→16); the report no longer renders a clinical comparison section.

#### Flow: ~~daily factor log~~ — removed in consolidation T7 (screen only)
1. ~~The factor-log screen defaults the date field to today ...~~ through 3. ~~Save → the repository's saveForDate ...~~ — the screen and its view model were removed; FactorLogRepository and its tests remain (saveForDate now has no production caller).
4. Other readers/writers (unchanged): the report's morning card upserts single keys via `app/src/main/java/com/sleepanalysis/data/repo/FactorLogRepository.kt` · `saveFactorForDate` (scale values clamped to 1..5, other rows untouched) and `removeFactorForDate` on clear; the report snapshot and insight rules read factors for the session's wake date (sections 6 and 7; the playbook and N-of-1 experiment tracker readers were removed in consolidation T7).

#### Flow: Settings screen (structure, top to bottom)
1. Recording controls card — master switch, schedule switch, start-delay chips (0/15/30/45/60/90 minutes) and noise-filter chips (Off / ≥ 0.50 / ≥ 0.70 / ≥ 0.90), both chip sets defined in `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt`, start/end time rows (disabled unless scheduling is fully on), equal-time rejection text, next-scheduled-start line, exact-alarm and notification warnings with fix buttons (shown only when scheduling is on and the respective grant is missing), timing/battery notes, "skip tonight". Persisted values and scheduling semantics → section 1; noise-filter report effect → section 6.
2. Garmin key card (title + privacy note precede it): masked preview, password field, save (`app/src/main/java/com/sleepanalysis/data/secure/GarminKeyStore.kt` · `save` on an IO dispatcher) and clear → section 8.
3. Garmin import card: date field (default today — the code comment records the wake-date incident), import button disabled unless the date matches yyyy-MM-dd and no import is running; renders importing / success / not-ready / error states with per-endpoint HTTP status lines (`app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `endpointStatusLabel`: 2xx ok, 404 not-ready, negative = no HTTP verdict, any other positive HTTP code = failed via the `else` branch) → section 8.
4. Health Connect import card: if unavailable, note + permanently disabled button; otherwise date field, import button, state rendering, and a grant button that launches the HC permission contract when the state is permission-missing → section 8.
5. Disclaimer card: the `report_disclaimer` string ("Not a diagnostic device…") on a surface-variant card.
6. ~~Debug card: the two toggles below~~ — removed in consolidation T3 (the debug toggles and the card no longer exist).

Exact-alarm (Android 12+ only) and notification grants are re-read on every ON_RESUME via a lifecycle observer registered inside the settings screen composable, so returning from the system screens updates the warnings immediately.

#### Flow: build and CI
1. Every PR (and pushes to main) runs three jobs: android (`./gradlew :app:check :app:assembleDebug :app:assembleDebugAndroidTest`, retried once because lint workers crash nondeterministically), instrumented (API 34 pixel_5 emulator, connectedDebugAndroidTest), python (ruff check, pytest via uv).
2. `:app:check` includes the four custom gates (see N.3) plus unit tests and lint; they fail the build on drift so a policy change cannot land silently.

### N.2.3 State and stored data

- **DataStore keys** (written by this area): `disclaimer_accepted_at` (Long epoch ms; absent = onboarding pending — the only gating state), ~~`debug_enter_windows_1` (Boolean, default false)~~, ~~`debug_skip_capture_wakelock` (Boolean, default false = wakelock held)~~ (both keys removed in consolidation T3 — no longer read or written), `noise_display_min_confidence` (Double, sanitized at read and write by PreferenceBounds in `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt`). Recording/schedule/skip keys → section 1; import keys → section 8.
- **Room tables touched by this area's UI**: ~~clinical_baseline (columns id, studyDate, ...) — insert-only via UI~~ (removed in consolidation T7); `user_factor` (columns `id`, `date` ISO yyyy-MM-dd, `factorKey`, `valueNum`, `valueText` — the recordedAt column was dropped in migration 15→16) — full-date replace via UI (the report's morning card).
- ~~**DB triggers**: trg_clinical_baseline_no_update, trg_clinical_baseline_no_delete — BEFORE UPDATE / BEFORE DELETE on clinical_baseline, recreated after purge and after migrations~~ — removed in consolidation T7 (migration 15→16 drops triggers and table).
- **Version strings stored with data**: events carry the model version from SessionRepository.MODEL_VERSION (~~suffixed `+dbg1` for the whole session when enter-1 debug is on~~ — no longer written since consolidation T3; stored `+dbg1` rows stay readable); ~~`recording_start` session-log lines carry the wakelock arm marker (`""` / `wl=off` / `wl=off-delay-covered`)~~ (no longer written since T3; old logs containing the markers stay parseable by the Python tools).
- **Manifest contracts**: ten permissions (INTERNET, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, WAKE_LOCK, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, health.READ_SLEEP); `allowBackup="false"`; `dataExtractionRules` pointing at `app/src/main/res/xml/data_extraction_rules.xml` (excludes `root`/`file`/`database`/`sharedpref` in both `cloud-backup` and `device-transfer`); WorkManager default initializer removed via the androidx.startup provider; HC permissions-rationale intent filter plus the Android 14 permissions-usage activity alias on the launcher activity; exported launcher activity; non-exported microphone foreground service and three receivers (boot receiver also listens for TIMEZONE_CHANGED / TIME_SET).
- **Notification contract**: channel id `monitoring` (importance LOW), ongoing recording notification id 41 with the mic icon, chronometer and a stop action; the pre-capture delay notification reuses the same id.
- **Room schema export**: the KSP room.schemaLocation argument exports to `app/schemas/com.sleepanalysis.data.db.AppDatabase/`, which currently holds 1.json through 16.json; that dir is also an androidTest asset dir so the migration test replays the history.
- **Build config**: namespace/applicationId `com.sleepanalysis`, compileSdk 36, minSdk 30, targetSdk 35, versionCode 1, versionName `0.1.0-phase1`, Java/JVM 17, Compose enabled, release buildType minified with the default optimize proguard file plus `proguard-rules.pro`, release signing created only when all four RELEASE_* env vars exist (otherwise unsigned); dependency versions centralized in `gradle/libs.versions.toml`; `gradle.properties` sets a 6 GB heap, parallel and caching builds, nonTransitiveRClass.

## N.3 Requirements

| ID | Requirement | Where | Pinned by | Status |
|---|---|---|---|---|
| APP-01 | Launching the app shows a Compose hierarchy under the app theme, which uses the dark palette unless the system theme is explicitly light | `app/src/main/java/com/sleepanalysis/ui/theme/Theme.kt` · `SleepAnalysisTheme` | — | `unverified` |
| APP-02 | While `disclaimer_accepted_at` is absent the nav graph is not composed: loading shows a spinner, otherwise onboarding; only acceptance composes the nav graph | `app/src/main/java/com/sleepanalysis/ui/AppRoot.kt` · `AppRoot` | — | `unverified` |
| APP-03 | Accepting stores `disclaimer_accepted_at` = current epoch ms in DataStore; on API 33+ the POST_NOTIFICATIONS request precedes the RECORD_AUDIO request | `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt` · `accept` | — | `unverified` |
| APP-04 | Acceptance is written when the mic permission dialog completes, even if the permission was denied; missing mic is surfaced later by Home's pre-flight checklist | `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt` · `OnboardingScreen` | — | `unverified` |
| APP-05 | No in-app action can reset acceptance: `acceptDisclaimer` is the only writer of the key and delete-all does not clear DataStore | `app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt` · `acceptDisclaimer` | — | `unverified` |
| APP-06 | History lists all sessions newest-first (startedAt DESC), each row showing formatted start time, duration or "in progress", and lowercase status; tapping a row opens its report | `app/src/main/java/com/sleepanalysis/ui/history/HistoryScreen.kt` · `HistoryScreen` | — | `unverified` |
| APP-07 | Deleting one night requires a confirmation dialog; it deletes the session's clip files first, then the DB rows (cascade), then the session log, and shows a snackbar | `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `deleteNight` | — | `unverified` |
| APP-08 | Deleting all data requires a confirmation dialog and removes every row (the clinical baseline went with consolidation T7), all clips and all session logs | `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `deleteAllData` | `MigrationTest.migrate1ToLatest_preservesRows_dropsClinicalBaselineAndWriteOnlyColumns` (purge path untested) | `unverified` (was `tested` partial: the trigger recreation it pinned was removed in consolidation T7) |
| APP-09 | ~~The baseline UI is insert-only ...~~ — removed in consolidation T7 (BaselineRepository, BaselineScreen, the table and the triggers dropped by migration 15→16) | ~~app/src/main/java/com/sleepanalysis/data/repo/BaselineRepository.kt · add~~ | — | removed |
| APP-10 | ~~Baseline dialog validates the study date ...~~ — removed in consolidation T7 (file deleted) | ~~app/src/main/java/com/sleepanalysis/ui/baseline/BaselineScreen.kt · AddBaselineDialog~~ | — | removed |
| APP-11 | ~~Every stored clinical baseline is carried into the night report snapshot ...~~ — removed in consolidation T7 (the report no longer renders a clinical comparison section) | ~~app/src/main/java/com/sleepanalysis/domain/report/ReportAssembler.kt · baseline~~ | — | removed |
| APP-12 | ~~The factor log offers 7 boolean factors ... Save replaces the date's rows atomically ...~~ — removed in consolidation T7 (FactorLogScreen and FactorLogViewModel deleted; FactorLogRepository.saveForDate kept, now without a production caller) | ~~app/src/main/java/com/sleepanalysis/ui/factorlog/FactorLogScreen.kt · save~~ | `FactorLogRepositoryTest.saving a date replaces its previous entries` | removed (repository logic still `tested`) |
| APP-13 | ~~A change of date reloads the form ...~~ — removed in consolidation T7 (screen deleted) | ~~app/src/main/java/com/sleepanalysis/ui/factorlog/FactorLogScreen.kt · loadDate~~ | — | removed |
| APP-14 | Per-key upserts from the report's morning card clamp 1–5 scale factors into 1..5, keep other rows of the date intact, and removal leaves the rest untouched | `app/src/main/java/com/sleepanalysis/data/repo/FactorLogRepository.kt` · `saveFactorForDate` | `FactorLogRepositoryTest.single-key save clamps scale factors into the 1-5 range`; `FactorLogRepositoryTest.removing a key leaves the date's other rows intact` | `tested` |
| APP-15 | Formatting builds its date/time formatters from the default locale once at class init, so a locale change mid-session has no effect until process restart; the zone is always the system zone at format time | `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt` · `Formatting` | `CaptureWindowLabelTest` (asserts which instants get paired through Formatting.time) | `tested` (indirectly) |
| APP-16 | Settings renders exactly five groups in order: recording controls, Garmin key, Garmin import, Health Connect import, disclaimer (~~a sixth debug card~~ — removed in consolidation T3) | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `SettingsScreen` | — | `unverified` |
| APP-17 | ~~The enter-1 debug toggle persists `debug_enter_windows_1` ...~~ — removed in consolidation T3 (toggle, Settings card and MonitorService.activeModelVersion dropped; the `+dbg1` suffix stays readable in stored data) | ~~app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt · activeModelVersion~~ | — | removed |
| APP-18 | ~~The capture-wakelock experiment toggle persists `debug_skip_capture_wakelock` ...~~ — removed in consolidation T3 (the debug toggles were dropped; the `wl=off` / `wl=off-delay-covered` markers stay contract for stored logs) | ~~app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt · captureWakelockMode~~ | ~~UserPreferencesDefaultsTest.capture wakelock experiment flag passes stored values through~~ (test class removed) | removed |
| APP-19 | Exact-alarm and notification warnings appear only while scheduling is active and the respective grant is missing; both are re-evaluated on every ON_RESUME; fix buttons open the system screens (exact-alarm intent null below Android 12) | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `refreshExactAlarmPermission` | — | `unverified` |
| APP-20 | The noise-filter chips (Off / 0.5 / 0.7 / 0.9) persist `noise_display_min_confidence`, sanitized at write and read; 0.0 disables the filter; report effect → section 6 | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `setNoiseMinConfidence` | `PreferenceBoundsTest.confidence clamps to the configured bounds`; `PreferenceBoundsTest.NaN confidence falls back to the default instead of poisoning the clamp` | `tested` |
| APP-21 | No storage domain leaves the device: `allowBackup="false"` plus data-extraction rules excluding `root`/`file`/`database`/`sharedpref` under both cloud-backup and device-transfer | `app/src/main/res/xml/data_extraction_rules.xml` · `data-extraction-rules` | — | `unverified` |
| APP-22 | While recording, an ongoing notification (channel `monitoring`, id 41, mic icon, chronometer, stop action) is the mic-active indicator | `app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt` · `buildRecordingNotification` | — | `unverified` |
| APP-23 | The merged debug manifest's permissions must equal `ci/permission-allowlist.txt` exactly (10 app-declared + 2 merger additions); any difference fails the build | `app/build.gradle.kts` · `checkPermissionAllowlist` | — | `unverified` (enforced on every CI run) |
| APP-24 | strings.xml must not contain the claim terms ahi / apnea(s) / arousal index(es) / sleep stage(s) / desaturation(s) outside the approved strings (`onboarding_disclaimer` plus the two baseline-field names, which stayed in the approved set even though the baseline_field strings were removed from strings.xml in consolidation T7) | `app/build.gradle.kts` · `checkForbiddenTerms` | — | `unverified` (enforced on every CI run) |
| APP-25 | No git-tracked file may match the personal-data patterns (night-data/, .wav, .db, .db-wal, .db-shm, .preferences_pb, session_*.log, chart_*.png) | `app/build.gradle.kts` · `checkRepoHygiene` | — | `unverified` (enforced on every CI run) |
| APP-26 | Every http(s) URL host in main Kotlin sources (comments stripped) must be personal.connectapi.garmin.com | `app/build.gradle.kts` · `checkNetworkHosts` | — | `unverified` (enforced on every CI run) |
| APP-27 | CI on every PR runs the Gradle check+assembles (with one lint retry), the Room migration test on an API 34 emulator, and ruff + pytest over the Python tooling | `.github/workflows/ci.yml` · `jobs` | — | `unverified` |
| APP-28 | The evalFixtures task re-runs the fixture-eval unit test against a local fixtures directory (skips silently when absent; -PsleepFixturesDir overrides) as part of the verification group | `app/build.gradle.kts` · `evalFixtures` | `FixtureEvalRunTest` | `tested` |

## N.4 Constants and thresholds

| Constant | Value | Defined in | Effect |
|---|---|---|---|
| `ENTER_WINDOWS` | 2 (consecutive active hops) | `app/src/main/java/com/sleepanalysis/monitoring/detection/DetectorConfig.kt` · `ENTER_WINDOWS` | Normal event-open rule; ~~the enter-1 debug toggle replaces it with 1 for the next session~~ (removed in the 2026-11-22 consolidation — T3) |
| ~~DEFAULT_DEBUG_SKIP_CAPTURE_WAKELOCK~~ | ~~false~~ | ~~app/src/main/java/com/sleepanalysis/data/prefs/UserPreferences.kt · DEFAULT_DEBUG_SKIP_CAPTURE_WAKELOCK~~ — removed in the 2026-11-22 consolidation (T3; the toggle and constant no longer exist) | ~~Absent flag means wakelock held (control arm of the A/B experiment)~~ |
| `START_DELAY_OPTIONS` | 0, 15, 30, 45, 60, 90 (minutes) | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `START_DELAY_OPTIONS` | Start-delay chip presets |
| `NOISE_FILTER_OPTIONS` | 0.0, 0.5, 0.7, 0.9 (confidence) | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `NOISE_FILTER_OPTIONS` | Low-confidence-noise display filter presets; 0.0 = off |
| date regex | `\d{4}-\d{2}-\d{2}` | ~~app/src/main/java/com/sleepanalysis/ui/baseline/BaselineScreen.kt · AddBaselineDialog~~ (removed in consolidation T7); same regex in ~~FactorLogScreen~~ (removed in T7) and the import date fields | Gate for save/import buttons |
| date/time patterns | `EEE d MMM yyyy, HH:mm` / `HH:mm` | `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt` · `Formatting` | Report/history display format |
| `NOTIFICATION_ID` / `CHANNEL_ID` | 41 / `monitoring` | `app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt` · `companion` | Recording and delay notifications share them |
| compile/min/target SDK | 36 / 30 / 35; versionName `0.1.0-phase1` | `app/build.gradle.kts` · `defaultConfig` | Build targets; lint flags OldTargetApi (35 vs 36) |

## N.5 Failure handling and edge cases

| Situation | Behaviour | Where |
|---|---|---|
| User denies mic permission during onboarding | Acceptance is stored anyway; Home's pre-flight checklist later reports the missing grant | `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt` · `OnboardingScreen` |
| Notification permission denied (API 33+) | Onboarding still proceeds to the mic request; scheduled starts would silently not notify — Settings shows a warning + fix button when scheduling is on | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `refreshNotificationPermission` |
| ~~Study date not yyyy-MM-dd~~ | ~~Save blocked, inline error, field stays editable~~ — removed in consolidation T7 (baseline screen deleted) | ~~app/src/main/java/com/sleepanalysis/ui/baseline/BaselineScreen.kt · AddBaselineDialog~~ |
| ~~Factor-log save with invalid date~~ | ~~Nothing written, no crash (tested)~~ — removed in consolidation T7 (factor-log screen deleted) | ~~app/src/main/java/com/sleepanalysis/ui/factorlog/FactorLogScreen.kt · save~~ |
| Schedule start == end time | Change rejected inline, error text shown, old value kept | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `setScheduleStart` |
| ~~Delete-all with immutability triggers active~~ | ~~Triggers are dropped before the delete and recreated afterwards ...~~ — removed in consolidation T7 (the purge is now a plain transaction; the triggers no longer exist) | `app/src/main/java/com/sleepanalysis/data/db/AppDatabase.kt` · `purgeEverythingIncludingClinical` |
| Exact-alarm permission missing (Android 12+) | Warning + button opening the system exact-alarm screen; the intent builder returns null below Android 12 (no permission needed there) | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `exactAlarmRequestIntent` |
| Health Connect unavailable | Import button permanently disabled with an explanatory note | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `SettingsScreen` |
| Garmin import date invalid or import running | Button disabled | `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `SettingsScreen` |
| Locale changed while the app runs | Date/time strings keep the old locale until the process restarts (the formatters are immutable vals) | `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt` · `Formatting` |
| ~~Debug enter-1 enabled mid-experiment~~ | ~~Only sessions started after the toggle carry the `+dbg1` provenance; stored data stays distinguishable~~ — removed in the 2026-11-22 consolidation (T3; `+dbg1` rows written before removal stay readable) | ~~app/src/main/java/com/sleepanalysis/monitoring/service/MonitorService.kt · activeModelVersion~~ |
| Backup attempted (cloud or device transfer) | All four storage domains excluded in both rule sets; `allowBackup="false"` as belt-and-braces | `app/src/main/res/xml/data_extraction_rules.xml` · `data-extraction-rules` |

## N.6 Contract boundaries — what a refactor must not break

**Persisted formats**
- DataStore keys with their types/defaults: `disclaimer_accepted_at` (Long, absent = onboarding gate — APP-02 depends on absence, not false), ~~`debug_enter_windows_1`~~, ~~`debug_skip_capture_wakelock`~~ (both removed in consolidation T3 — nothing writes or reads them anymore), `noise_display_min_confidence`.
- ~~clinical_baseline column names/semantics and the two trigger names~~ (removed in consolidation T7); `user_factor` columns (`date` ISO string, `factorKey` strings like ALCOHOL, STRESS, MORNING_RATING, NOTES, … defined in `app/src/main/java/com/sleepanalysis/domain/factors/DailyFactor.kt`; the recordedAt column was dropped in migration 15→16).
- Exported Room schema JSONs under `app/schemas/com.sleepanalysis.data.db.AppDatabase/` (1.json through 16.json; androidTest assets for the migration test).
- Formatting output patterns (report/history strings) — visual contract of every date shown.

**Version strings stored with data**
- SessionRepository.MODEL_VERSION (~~and its `+dbg1` suffix~~ — not written since consolidation T3, stored suffixes stay readable); ~~`recording_start` arm markers `wl=off` / `wl=off-delay-covered` in session logs~~ (no longer written since T3; old logs containing them stay parseable by the Python tools); versionName/versionCode identify APKs.

**Platform contracts**
- All ten manifest permissions + the two merger-added ones pinned by `ci/permission-allowlist.txt`; microphone foreground-service type; notification channel `monitoring` and id 41; removed WorkManager default initializer (required by Hilt's WorkManager integration); HC rationale intent filter and permissions-usage alias; BOOT_COMPLETED / TIMEZONE_CHANGED / TIME_SET receiver actions; `allowBackup="false"` + extraction rules.

**Cross-area interfaces**
- the Routes constants and report/reportLog/reportLabeling builders in `app/src/main/java/com/sleepanalysis/ui/navigation/AppNavHost.kt` consumed by home, report, history, trends and insights callbacks.
- the formatting helpers in `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt`, used by report, home, history and settings screens.
- `app/src/main/java/com/sleepanalysis/data/repo/SessionRepository.kt` · `deleteNight` and `deleteAllData` called from the history screen's view model in `app/src/main/java/com/sleepanalysis/ui/history/HistoryScreen.kt` (deletion semantics shared with the service's discard path).
- The shared ~~debug~~/noise DataStore keys are read by the monitoring service (sections 1/2) and the report builder (section 6) — the two debug keys were removed in consolidation T3 and are read nowhere anymore; Settings' scheduler calls (arm, skipNextStart, nextStartOrNull) belong to section 1.
- ~~Baseline rows flow from app/src/main/java/com/sleepanalysis/data/repo/BaselineRepository.kt · add/reads into the report snapshot (section 6)~~ (removed in consolidation T7); factor rows are written by the report morning card via the factor-log repository's per-key upsert and read by insights (section 7; the experiments reader was removed in consolidation T7).

**Safety-net tests**
`FactorLogRepositoryTest`, UserPreferencesDefaultsTest, `PreferenceBoundsTest`, `ScheduleSkipHistoryTest`, `CaptureWindowLabelTest`, `MigrationTest` (instrumented; it validated the drop of the clinical baseline and the write-only columns in consolidation T7's migrations — the immutability triggers it used to re-check no longer exist; FactorLogViewModelTest was removed with the screen).

## N.7 Consolidation candidates

| Candidate | Kind | Evidence | Risk to remove | Note |
|---|---|---|---|---|
| app/src/main/res/values/strings.xml · home_view_report; same file · `home_battery_hint_title`, `home_battery_hint_body`, `home_battery_hint_button` | `dead` | grep -rn over app/src, tools/, analysis/ finds zero references outside res/values (Lint UnusedResources agrees); the battery-exemption feature itself still exists but uses `home_preflight_battery` in the Home pre-flight checklist | `low` — no code path or test references them | The battery-hint strings were superseded by the pre-flight checklist row; the feature lives on |
| app/src/main/res/values/strings.xml · validity_title; same file · retention_worker_note, `report_garmin_window`, `report_interrupted_note`, `experiment_active_body` | `dead` | Same grep: zero references anywhere incl. tests and tools | `low` — unreferenced by any code | ~~`experiment_active_title` IS used (Insights); only its body variant is orphaned — check what changed before deleting~~ — superseded: all `experiment_*` strings were deleted (the body variant in consolidation T1, `experiment_active_title` with the playbook catalog in T6); zero `experiment_*` strings remain in strings.xml |
| Onboarding text claims "the app has no internet permission at all" | `doc-drift` | `app/src/main/res/values/strings.xml` · `onboarding_what_body` vs the INTERNET permission in `app/src/main/AndroidManifest.xml` (declared for Garmin pulls) — a user-facing privacy claim contradicted by the manifest | `none` (as deletion) | DEFECT, see N.8; the string must be corrected, not the permission removed |
| ~~app/src/main/java/com/sleepanalysis/ui/factorlog/FactorLogScreen.kt · SwitchRow~~; `app/src/main/java/com/sleepanalysis/ui/home/HomeScreen.kt` · `CheckRow`; `app/src/main/java/com/sleepanalysis/ui/settings/SettingsScreen.kt` · `TimeRow` | `duplicate` | Reference-scan table C grouped them; the same label-left / control-right Row pattern (the FactorLogScreen row was removed in consolidation T7, leaving two) | `low` — cosmetic differences only (Checkbox vs TextButton) | One shared row composable would replace both |
| `app/src/main/java/com/sleepanalysis/ui/AppRoot.kt` · `MainViewModel` vs `app/src/main/java/com/sleepanalysis/ui/onboarding/OnboardingScreen.kt` · `OnboardingViewModel` | `duplicate` | Both map the same DataStore flow to a Boolean accepted state with the same expression | `low` — two tiny flows | Could share one mapper; keeping both is also defensible |
| `gradle/libs.versions.toml` · compose-ui-tooling-preview; same file · `compose-ui-tooling` | `dependency` | grep -rn "@Preview" over app/src/main finds no hits; no tooling-preview import exists | `low` — ui-tooling is debugImplementation only; ui-tooling-preview ships to release unused | Drop both unless Compose previews are planned |
| `app/build.gradle.kts` · `sourceSets` comment "replays the real 1.json…8.json" | `doc-drift` | `app/schemas/com.sleepanalysis.data.db.AppDatabase/` holds 1.json through 16.json (14 pre-T7); `MigrationTest.migrate13To14_addsStageSource_defaultingLegacyRowsToGarmin` exists | `none` | Comment is stale, code is correct |
| `app/src/main/AndroidManifest.xml` · `activity` attribute turnScreenOn=false | `dead` | false is the platform default; no code reads it | `none` | Cosmetic |
| `app/src/main/java/com/sleepanalysis/ui/components/Formatting.kt` · `formatDateTime`; same file · `formatDuration`, `formatMinutesOfDay` | `over-general` | Single-line aliases delegating to the Formatting object; screens import both styles | `low` | Pick one entry point during consolidation |
| Missing android:fullBackupContent for the minSdk-30 window (Lint DataExtractionRules finding) | `legacy-compat` | minSdk 30 but dataExtractionRules applies from Android 12; on API 30 only `allowBackup="false"` protects | `low` — allowBackup=false already disables backup entirely; the rules file is belt-and-braces | Add fullBackupContent only if API 30 devices matter — owner call |

Free-text: the four Gradle gates are all real (each fails the build on drift; none "checks nothing"). The Settings screen is a good consolidation target overall — its view model holds 12 optimistic state flows that could collapse into one data class, and the recording-controls card duplicates persistence logic that section 1 also models. The debug toggles were removed in consolidation T3 — nothing writes the `+dbg1` / `wl=off` markers anymore; the markers stay permanent provenance in data written before T3, so readers must keep parsing them.

## N.8 Open questions for the owner

1. ~~**DEFECT — stale privacy claim in onboarding.** The `onboarding_what_body` string tells the user the app "has no internet permission at all", but the manifest declares INTERNET for the Garmin pull (and the network-host gate enforces exactly that host). The onboarding text must be reworded; until then the app's first screen makes a false privacy statement.~~ — resolved: the string was reworded in consolidation T8 (D-29).
2. ~~Keep or drop the two debug toggles? Both are live experiments (enter-windows-1 provenance `+dbg1`; capture-wakelock A/B with `wl=off` log markers). Removing the toggles is easy; removing the markers from stored data/log parsing is a legacy-compat decision.~~ — resolved: both toggles were dropped in consolidation T3; the markers remain legacy-compat in stored data/logs and readers stay tolerant.
3. Should acceptance of the disclaimer require an actually granted mic permission (today a denial still stores acceptance, APP-04)?
4. UNVERIFIED: no test or device evidence exists for the onboarding permission sequence, history deletions, baseline dialog validation, or the notification-id/channel contract — all marked `unverified` in N.3; the dogfood checklist was not consulted for device evidence.
5. ~~UNVERIFIED: whether the `experiment_active_body` / validity_title strings were orphaned intentionally (feature reworked) or their UI was lost — owner should confirm before deletion.~~ — resolved: the strings were deleted (consolidation T1 for `experiment_active_body` and validity_title; the experiment-title string went with the playbook catalog in T6).
