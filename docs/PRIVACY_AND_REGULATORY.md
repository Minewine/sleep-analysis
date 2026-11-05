# Privacy & Regulatory — SleepAnalysis

Rules governing data handling, storage, deletion, disclaimer language, and the wellness/medical-device boundary. Binding for all phases.

Related: [Measurement reliability](MEASUREMENT_RELIABILITY.md) · [Architecture](ARCHITECTURE.md) · [Data model](DATA_MODEL.md) · [Roadmap](ROADMAP.md)

---

## 1. On-device processing rules

1. **Audio never leaves the device.** Recording, feature extraction, inference, and event detection all run locally ([Architecture](ARCHITECTURE.md) §5). There is no upload path.
2. **Network posture (amended in Phase 3):** the app originally shipped with no `INTERNET` permission. Phase 3 added **one** networked feature — on-demand pulls from the **Garmin Personal API** — under these binding rules (full detail: [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md) §8):
   - exactly one host (`personal.connectapi.garmin.com`), auditable as a single constant in `GarminClient`;
   - user-triggered only (no background sync, no polling);
   - **read-only**: the request carries the user's API key/token and a date — no audio or derived sleep data is ever sent anywhere;
   - no telemetry, no analytics, no crash-reporting, no other network use.
3. **Health Connect** (later Phase 3) is on-device IPC and does not move audio. Garmin file import uses the Storage Access Framework on user-picked files.
4. **No telemetry.** No analytics, no crash-reporting SDKs, no ads, no identifiers. Debugging is via local logs (debug builds only) and `capture_stats` in the session row.
5. **No accounts.** No authentication of our own, no identifiers; the Garmin key is the user's own credential stored Keystore-encrypted on-device.
6. **Models are bundled.** ML models ship inside the APK/asset bundle; there is no model download path.

## 2. Data inventory — what exists and where

| Data | Location | Persisted | Notes |
|---|---|---|---|
| Raw audio stream | Memory (10 s ring buffer) | Never | Only event-triggered slices are written |
| Event clips (WAV) | `filesDir/clips/<session>/` (app-private) | 7 days default | ≤ 10 s each, capped ~150/night (~19 MB per 50 clips ≈ 48 MB/night) |
| Per-session log files (text) | `filesDir/logs/session_<id>.log` (app-private) | With their session (90 days default) | Timestamped lifecycle/diagnostic lines only — never audio, never sensor payloads |
| Database (sessions, events, measurements, reports, baseline) | App-private Room DB | Sessions/events 90 days; measurements indefinite; baseline until delete-all | WAL mode |
| Clinical source documents | Referenced by path only (`clinical_baseline.source_document`) | Until delete-all | The app never copies the PDF into its storage |
| Preferences (retention settings, disclaimer acceptance, detection debug toggle; experimental toggle arrives in Phase 2) | DataStore | Until cleared | |

Storage security posture: app sandbox + Android file-based encryption (device lock screen required). `android:allowBackup="false"` **and** `res/xml/data_extraction_rules.xml`, which excludes the `root`, `file`, `database` and `sharedpref` domains from both **cloud backup** and **device-to-device transfer** — no app data syncs to Google's device backup or migrates to a new device (the rules are belt-and-braces alongside `allowBackup`, keeping the exclusion explicit if the flag is ever revisited). App-level clip encryption (SQLCipher or AES for clip files) is an evaluated option in Phase 2 ([Architecture](ARCHITECTURE.md) §10); the DB never contains audio bytes.

**Repo hygiene:** this repository must never contain real sleep audio, device databases, Garmin API responses, or personal reports. Such data lives outside the repo and is passed into analysis tooling by path (e.g. `NIGHT_DATA`, `NIGHT_REPORT_OUT` env vars for `analysis/night_report.py`). `.gitignore` blocks `night-data/`, `analysis/chart_*.png`, `*.wav`, `*.db`, `*.db-wal`, `*.db-shm`, `*.preferences_pb`, and `session_*.log`; a pre-commit guard (`.githooks/pre-commit`, enable with `git config core.hooksPath .githooks`) fails commits that stage those patterns. Clinical PDFs and any document containing real names are out of scope for the repo as well.

## 3. Permissions

| Permission | Type | Why | Notes |
|---|---|---|---|
| `RECORD_AUDIO` | Runtime | Overnight capture | Requested after disclaimer acceptance, with rationale screen |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Manifest | Monitor service | Mic FGS type declared (API 30+; runtime type enforcement API 34+) |
| `POST_NOTIFICATIONS` | Runtime (API 33+) | Ongoing recording notification | Part of start flow |
| `WAKE_LOCK` | Manifest | Partial wake lock held for the recording session | Reliability over battery for overnight capture; quality gate ≤ 10 %/8 h |
| `SCHEDULE_EXACT_ALARM` | Manifest | Scheduled nightly recording: exact bedtime/end alarms | Inexact ~10-minute fallback when not granted (Android 12+ can withhold it); no data implications |
| `RECEIVE_BOOT_COMPLETED` | Manifest | Re-arm the nightly schedule after reboot or timezone/clock changes | Receivers never record from boot — they only post the bedtime notification / re-arm alarms |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Manifest | Lets the system battery-exemption dialog open directly from the app | No data implications; sideloaded personal app |
| `INTERNET` | Manifest (Phase 3 amendment) | Garmin Personal API pulls, user-triggered only | Single host, read-only, no telemetry — rules in [GARMIN_INTEGRATION.md](GARMIN_INTEGRATION.md) §8 |
| *(nothing else — no location, no storage beyond defaults, no contacts, etc.)* | | | |

The merged manifest (library manifests merged in) contains two additional entries this app never declares: `ACCESS_NETWORK_STATE` (network connectivity state only — pulled in by WorkManager; grants no data access) and the app-namespace `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature-protected placeholder androidx core generates for non-exported receivers). Both are allowlisted in `ci/permission-allowlist.txt` and enforced by the `checkPermissionAllowlist` CI gate: any merged permission outside that file fails the build.

Battery-optimization exemption is requested with an in-app rationale (sideloaded personal app; Play's exemption policy not applicable, the rationale UX is kept anyway).

## 4. Retention controls and deletion

Defaults ([Data model](DATA_MODEL.md) §6): clips 7 days, sessions/events 90 days, measurements persist, clinical baseline until delete-all. Stored in DataStore and read by the retention worker; the Settings UI to change them is still pending (NEXT_STEPS P1).

User controls (Phase 1 ships all of these):

- **Per-night delete** — cascades report → events → clips → session; measurements survive with session link nulled.
- **Clips-only purge** — delete audio while keeping numeric history.
- **Bulk delete-all** — wipes DB *including clinical baseline* and all clip files; double confirmation explicitly naming the clinical data loss; bypasses clinical-immutability triggers through the single maintenance path.
- **Export-then-delete** — export must be available before deletion so erasure rights don't conflict with data retention needs.

Right-to-erasure framing (Swiss revFADP / EU GDPR): the user is the data subject and controller's sole user; all personal data is deletable in one gesture, and processing happens locally under active use.

## 5. Disclaimer language (copy-ready)

### 5.1 First-run (full text, must accept before mic permission)

> **SleepAnalysis is a personal wellness and research tool — not a medical device.**
>
> It does not diagnose, treat, or prevent any disease, including sleep apnea. It estimates simple measures like snoring from microphone sound; these estimates can be wrong and are not a substitute for a sleep study (polysomnography) or advice from a qualified clinician.
>
> If you regularly experience loud snoring, witnessed breathing pauses, gasping during sleep, or excessive daytime sleepiness, speak to a doctor.

### 5.2 Morning report footer (always visible)

> Estimates from on-device microphone analysis. Not a diagnostic device. For sleep-health questions, talk to a clinician.

### 5.3 Experimental measurements (Tier 2 badge + caption, Phase 2+)

> **Experimental.** Derived from sound patterns; not a breathing measurement. Audible pauses are not apneas, and acoustic events are not arousals.

### 5.4 Clinical baseline screen

> Values shown here were clinically measured during sleep studies at the listed lab and date. They are entered read-only and are never compared one-to-one with phone estimates — the two are different kinds of measurement.

### 5.5 Cross-source comparison view (Phase 3+)

> Correlation and context — not equivalence. Phone estimates, wearable data, and clinical measurements come from different methods.

### 5.6 Wording rules (summary; full table in [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §3)

| Never say | Say instead |
|---|---|
| "apnea", "hypopnea", "AHI", "respiratory event index" (about phone data) | "breathing-pattern breaks (experimental indicator)" |
| "arousal(s)" (about acoustic events) | "acoustic events" |
| "sleep stages" (about phone data) | — (not computed) |
| "you have mild OSA" / any diagnosis | "17 breathing-pattern breaks were detected (experimental). See a clinician for assessment." |
| "monitors your breathing" | "detects snoring and acoustic events" |

A lint rule flags forbidden clinical terms in `strings.xml` outside approved disclaimer strings ([Architecture](ARCHITECTURE.md) §6).

## 6. Swiss / EU medical-device considerations

**Why conservative claims matter legally:** under **EU MDR 2017/745** (and the Swiss **MedDO**, with **ODim** governing IVDs), software is a medical device if it has a *medical purpose* — and software providing information used to diagnose or decide treatment is typically Class IIa under MDCG 2019-11 Rule 11. An app that claimed to "detect sleep apnea" or reported an "AHI" without conformity assessment, CE/CH marking, and a quality system would be an illegal device on the market — and, being honest, the phone also cannot deliver those numbers reliably ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) M15).

**The boundary SleepAnalysis stays inside:**

- Positioning: personal **wellness and research tool** for the user's own data.
- Functions: recording, acoustic event detection, trend tracking, self-logged factors, display of clinically measured values with citations.
- Functions never attempted: diagnosis, screening claims directed at third parties, treatment recommendations, triage, scoring equivalent to clinical indices.

**Practical rules:**

1. Any new UI copy, export template, or README change gets a 30-second check against §5.6 and [MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §3.
2. Tier-2 indicators exist for personal exploration only; they must never appear in exports as findings.
3. Clinician-facing exports describe the tool ("personal acoustic sleep logger") and embed disclaimers; they present observations, never conclusions.
4. If this project ever pivots toward consumers or diagnostic claims, it becomes a regulatory project (MDR conformity, notified body, quality system) — treat that as a new project, not a feature.

**Data protection:** Swiss revFADP (in force Sept 2023) / GDPR principles are satisfied largely by design: lawfulness (user is the subject and sole user), data minimization (audio kept only as short clips), storage limitation (retention defaults), integrity & confidentiality (app sandbox + FBE), and erasure/access rights (delete-all, local export with provenance).

## 7. Data export requirements (Phase 3)

- **User-initiated, local only:** Android share sheet to a user-chosen destination. No upload path exists or is added silently.
- **Formats:** CSV + JSON for data (full provenance columns: source, method, tier, unit, confidence, model id — see [Data model](DATA_MODEL.md) §3.2); PDF for human-readable morning/period reports.
- **Disclaimers embedded:** every export carries the §5.2 footer and, per page/section, the tier framing of its contents.
- **Claim filter:** exports pass the same `ClaimPolicy` filter as the report — Tier-2 values appear only if the experimental toggle is on, and are labeled; Tier-3 values cannot exist.
- **Provenance completeness:** any consumer of an export can tell, for every number, whether it was clinically measured, algorithmically estimated, device-reported, or user-entered.

## 8. Privacy decision log

| Decision | Phase | Rationale |
|---|---|---|
| No INTERNET permission | 1 | Strongest possible privacy guarantee; nothing needed network |
| **INTERNET added for Garmin Personal API pull only** | **3 (first slice)** | User-requested wearable data source; single host, user-triggered, read-only; audio still never leaves the device. Full rules: GARMIN_INTEGRATION.md §8 |
| API key stored in Android Keystore (AES-GCM), never in code/config | 3 | No secrets in source; key material never leaves the hardware keystore |
| Event clips only, never full-night audio | 1 | Data minimization; storage math (~115 MB/h raw) |
| `allowBackup = false` + `data_extraction_rules.xml` (root/file/database/sharedpref excluded from cloud-backup and device-transfer) | 1 | Keep audio-derived data and the Garmin ciphertext out of cloud device backups and cross-device migration |
| No analytics/crash SDKs | 1 | Single user; local `capture_stats` suffice |
| Clinical rows insert-only + trigger | 1 | Audit integrity of the platform's anchor data |
| Clinical rows removable only via delete-all | 1 | Erasure right without weakening immutability in normal use |
| Tier-2 data excluded from exports by default | 2 | Prevent experimental indicators leaking into clinician-facing material |
| Health Connect (on-device IPC) acceptable | 3 | No network, user-granted, data stays local |
| Sonar requires explicit opt-in + warnings | 5, if built | Ultrasound annoyance/pet safety; device variance |
