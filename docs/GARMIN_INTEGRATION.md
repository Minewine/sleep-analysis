# Garmin Personal API Integration

Phase 3 data source (first slice): on-demand pull of sleep, HRV, Body Battery, and daily-summary data for a single date from Garmin's **Personal API**, mapped into the platform's provenance-stamped Measurement model. The measurement hierarchy ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md)) is unchanged — Garmin values are **device-reported vendor estimates**, displayed as "Garmin estimate", never merged numerically with phone acoustics or clinical data.

Related: [Privacy & regulatory](PRIVACY_AND_REGULATORY.md) (network posture amendment) · [Data model](DATA_MODEL.md) (raw payloads) · [Architecture](ARCHITECTURE.md) (WearableSource extension point)

---

## 1. Scope

| In | Out (later phases) |
|---|---|
| Manual import for one date (Settings + Report quick action; the report anchors on the **wake date**, DATA_MODEL §5.3) | Background/scheduled sync |
| Sleep duration, vendor sleep score, stage totals — deep/light/REM/awake (`/sleep-service/sleep/dailySleep`) | Garmin OAuth (consumer program) — Personal API key only |
| HRV rMSSD nightly average + readings (`/hrv-service/hrv/{date}`) | Multi-day batch import |
| Body Battery (`/wellness-service/wellness/bodyBattery/reports/daily`, fallback from daily summary) | Steps/activity detail (fetched in the daily summary, not imported) |
| Daily summary: resting HR, respiration, **SpO₂ levels only** (desaturation counts are never imported — M12/M17), awakenings, nap time, sleep need, sleep-score factor sub-scores (`/usersummary-service/v2/dailysummaries`) | Server-side anything |
| Sleep stress + daily stress (`/usersummary-service/stats/stress/daily`) | |
| Raw JSON stored verbatim for every endpoint that returned 2xx | |

## 2. Key storage (security model)

- The Personal API key is entered in **Settings → Garmin Personal API** and encrypted with an **Android Keystore AES-256-GCM key** (`data/secure/GarminKeyStore.kt`). Ciphertext (IV ‖ GCM ciphertext, Base64) lives in an app-private preferences file; key material never leaves AndroidKeyStore.
- Implementation note: deliberately built on AndroidKeyStore directly rather than the deprecated `androidx.security-crypto` library.
- The plaintext key is never logged, never written to the database, and never included in crash data (there is no crash reporting).
- Settings shows only a masked preview (last 4 characters). Save replaces, Clear removes.
- **Operational caution:** treat the key like a password. If it has ever been shared (chat, screenshot), rotate it in Garmin Connect.

## 3. Auth flow

1. `POST https://personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token` with header `Authorization: ApiKey <key>`, empty body, and **`Accept: */*`** — the endpoint returns a **bare JWT string**, not JSON. Requesting `application/json` here yields **HTTP 406 once the key passes auth** (night-2 dogfood bug: invalid keys 401 before content negotiation, so the bug only ever appeared with a real key). Data GETs keep `Accept: application/json`.
2. Response: a bare JWT (`header.payload.signature`); expiry comes from its `exp` claim. Tolerant fallback shapes: JSON `token` → `access_token` → `data.token` with `expiresAt`/`expiresInSeconds`.
3. The bearer token is cached in memory until its reported expiry — or **5 minutes** when none is reported (conservative default). 4xx/5xx from the exchange surfaces a typed error; a 401/403 means "key rejected — check it in Garmin Connect".
4. Data requests carry `Authorization: Bearer <token>`. On any `401` the cached token is dropped, re-exchanged once, and the request retried.

## 4. Endpoints and mapping

| Endpoint (base `https://personal.connectapi.garmin.com`) | Param convention | Extracted metrics (MeasurementType) | Unit |
|---|---|---|---|
| `/sleep-service/sleep/dailySleep` | `?date=YYYY-MM-DD` | `VENDOR_SLEEP_DURATION` (`sleepTimeSeconds`, fallback `sleepTimeMinutes`×60), `VENDOR_SLEEP_SCORE` (`sleepScore.value`), score factors (`sleepScore.factorValues/factors` → duration/quality/stress/timing sub-scores, unknown keys skipped), stages per M13 (`deep/light/rem/awakeSleepSeconds`), `VENDOR_AVG_HEART_RATE_SLEEP` (`avgHeartRate`), `VENDOR_RESPIRATION_AVG_SLEEP` (`averageRespirationValue`), `VENDOR_SLEEP_STRESS_AVG` (`avgSleepStress`), `VENDOR_SLEEP_AWAKENINGS` (`awakeCount`), `VENDOR_NAP_TIME` (`napTimeSeconds`), `VENDOR_SPO2_AVG_SLEEP`/`VENDOR_SPO2_LOWEST_SLEEP` (levels only — `numberOfEventsBelowThreshold` is deliberately NOT imported, M12/M17), `VENDOR_SLEEP_NEED` (`sleepNeed.baseline`) | SECONDS, INDEX_0_100, BPM, BREATHS_PER_MINUTE, PERCENT, COUNT, MINUTES |
| `/hrv-service/hrv/{date}` | path | `VENDOR_HRV_RMSSD` (`hrvSummary.lastNightAvg`) | MS |
| `/wellness-service/wellness/bodyBattery/reports/daily` | `?startDate=&endDate=` (verified 2026-11-02: `?date=` is not a recognized param) | `VENDOR_BODY_BATTERY` (`bodyBatteryMostRecentValue`, else last `charged` in report array) | INDEX_0_100 |
| `/usersummary-service/v2/dailysummaries` | `?startDate=&endDate=` | `VENDOR_RESTING_HEART_RATE` (`restingHeartRate`), body-battery fallback | BPM, INDEX_0_100 |
| `/usersummary-service/stats/stress/daily/{from}/{to}` | path range | `VENDOR_STRESS_DAILY_AVG` (`stressAverage`, fallback `averageStressLevel`) | INDEX_0_100 |

**Assumption to verify live:** query parameters use `date=YYYY-MM-DD` where the path does not carry the date. Garmin's Personal API surface has shifted between revisions; if a first live response 404s or returns an unexpected shape, fix the parameter convention in `GarminClient` — the raw JSON (below) makes any remapping a pure code change with no data loss.

## 5. Provenance and raw storage

Every normalized metric is written through `MeasurementRecorder.recordGarminEstimate()`:

| Field | Value |
|---|---|
| `sourceType` | `GARMIN` |
| `method` | `DEVICE_REPORTED` |
| `isClinical` | `false` |
| `tier` | `ESTABLISHED` (as device-reported pass-through, per M10–M13/M18) |
| `sessionId` | `null` — imported outside any session; joined to reports by day window |
| `periodStart`/`periodEnd` | local-day bounds of the imported date |
| `unit` | from `MeasurementType` |
| `confidence` | `null` (vendor does not expose one) |
| `modelVersion` | `null` (vendor algorithm, not ours) |
| `notes` | `"Garmin Personal API, date <date>"` |

Raw responses are stored verbatim in the **`raw_payload`** table (schema v2, migration `MIGRATION_1_2`), one row per `(source, endpoint, date)` with `fetchedAt`. Re-importing a date **replaces** that date's raw rows (upsert) and re-records the metrics — historical provenance of a *corrected mapping* will come from reprocessing later phases; for now, latest import wins per type per date.

Parsing (`GarminJsonParsers`) is deliberately tolerant: bounded-depth key search, null on absence, empty result on malformed payloads — never a crash on shape drift.

## 6. Import flow and failure semantics

```
User taps "Import from Garmin" (Report: this night's date · Settings: any date)
  → key present?  no → Error state (no network call)
  → token exchange → 5 GETs (each fails independently)
  → HTTP 200 bodies stored in raw_payload
  → tolerant parse → dedupe by type (dedicated endpoint beats summary fallback)
  → recordGarminEstimate per metric
  → Success(metrics, endpoints) with per-endpoint HTTP statuses listed in Settings
```

- **Core flows never block or depend on Garmin.** Recording and the acoustic report run identically with no key, no network, or a failed import; failures surface only in the import state (Settings + report card).
- Network: `HttpURLConnection` only, 10 s connect / 20 s read timeouts, per-endpoint `try/catch` so one dead endpoint doesn't abort the others.
- The morning report shows the Garmin section only when metrics exist for the session's calendar day, and offers the one-tap import when they don't.

## 7. UI labeling rules

- Section header: **"Garmin (device-reported estimates)"** with standing note: *"Vendor algorithms, shown as context next to the acoustic estimates above — never compared numerically with clinical data."*
- Every value row repeats **"(Garmin estimate)"** — vendor data is context, not evidence ([MEASUREMENT_RELIABILITY.md](MEASUREMENT_RELIABILITY.md) §4).
- Garmin metrics never feed the snore score, breathing anything, or any phone-derived aggregate (asserted by `ReportAssemblerTest`).

## 8. Network posture amendment (privacy)

This is the app's first networked feature. The rules that keep the privacy story intact ([PRIVACY_AND_REGULATORY.md](PRIVACY_AND_REGULATORY.md) §1, amended):

1. **One host only**: `personal.connectapi.garmin.com`. No analytics, no crash reporting, no other domains — auditable in `GarminClient` (single `BASE` constant).
2. **User-triggered only**: no background sync, no polling, no auto-import.
3. **Request payload**: nothing but the API key/token and the date. **No audio or derived sleep data is ever sent anywhere** — this is a read-only pull of the user's own Garmin data.
4. The manifest gains `INTERNET` with a comment stating the above; the privacy decision log records the change.

## 9. Verification checklist (needs a real device + real key)

- [ ] Key entry: save → masked preview appears; app restart → still stored (Keystore round-trip); clear → gone.
- [ ] Import with no key → immediate "No Garmin API key stored" error, no network attempt.
- [ ] Import with a wrong key → "Garmin rejected the API key (HTTP 401/403)".
- [ ] Airplane-mode import → per-endpoint failure, friendly message, no crash.
- [ ] Real import → Settings lists per-endpoint HTTP statuses; raw rows exist; report shows Garmin rows labeled "(Garmin estimate)".
- [ ] **First live run: verify the `date` parameter convention against actual responses** (§4 assumption) and adjust `GarminClient`/parsers if needed — raw payloads make remapping lossless.
- [ ] Import for a night, then open that night's report → Garmin section populated; open another night's report → empty section with import affordance.

## 10. Files

| File | Role |
|---|---|
| `data/secure/GarminKeyStore.kt` | Keystore-encrypted key storage |
| `data/garmin/GarminClient.kt` | Token exchange + endpoint GETs (single host constant) |
| `data/garmin/GarminJsonParsers.kt` | Tolerant response → metric mapping (JVM-tested) |
| `data/garmin/GarminImportManager.kt` | Orchestration, persistence, UI state machine |
| `data/db/entity/Entities.kt` → `RawPayloadEntity` | Raw JSON storage (schema v2) |
| `ui/settings/SettingsScreen.kt` | Key management + dated import |
| `ui/report/ReportScreen.kt` → `GarminCard` | Report section + per-night quick import |
| `docs/MEASUREMENT_RELIABILITY.md` | M18 row (Body Battery) + vendor-labeling rules |
