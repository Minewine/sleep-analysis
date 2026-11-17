#!/usr/bin/env bash
# Snapshot the app's SQLite database (db + wal + shm) off the phone into
# ~/sleep-local/backups/<timestamp>/ via adb run-as (debug build required).
# Keeps the newest 30 snapshots. Exits 0 with a note when no device is
# attached; exits 1 when a pull produced an unusable database.
#
# Usage: backup_db.sh [serial]   (SLEEP_BACKUP_ROOT overrides the output root)
set -euo pipefail

SERIAL="${1:-}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB=(-s "$SERIAL"); fi

if ! "${ADB[@]} get-state" >/dev/null 2>&1; then
  echo "backup: no device attached — skipped"
  exit 0
fi

ROOT="${SLEEP_BACKUP_ROOT:-$HOME/sleep-local/backups}"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
OUT="$ROOT/$STAMP"
mkdir -p "$OUT"

for f in sleepanalysis.db sleepanalysis.db-wal sleepanalysis.db-shm; do
  "${ADB[@]}" exec-out run-as com.sleepanalysis cat "databases/$f" > "$OUT/$f" 2>/dev/null || true
done

# A missing -wal/-shm is fine (checkpointed DB); a missing or empty main db is not.
if [[ ! -s "$OUT/sleepanalysis.db" ]]; then
  echo "backup: pull failed — main db missing or empty (debug build? device locked?)"
  exit 1
fi

# quick_check + session count on the recovered copy; 0 sessions is the
# truncated-pull signature the exporter also guards against.
status=0
python3 -I - "$OUT" <<'PY' || status=1
import sqlite3
import sys

path = sys.argv[1] + "/sleepanalysis.db"
conn = sqlite3.connect(path)
check = conn.execute("PRAGMA quick_check").fetchone()[0]
rows = conn.execute("SELECT COUNT(*) FROM sleep_session").fetchone()[0]
print(f"backup: quick_check={check} sleep_session={rows}")
if check != "ok" or rows == 0:
    sys.exit(1)
PY
if [[ "$status" -ne 0 ]]; then
  echo "backup: pulled database failed validation"
  exit 1
fi

# Retention: newest 30 snapshots.
ls -1dt "$ROOT"/*/ 2>/dev/null | tail -n +31 | xargs -r rm -rf

echo "backup: $OUT"
