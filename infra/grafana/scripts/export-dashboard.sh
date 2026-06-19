#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <dashboard_uid> <output_json> [grafana_url] [user] [password]" >&2
  exit 1
fi

DASHBOARD_UID="$1"
OUTPUT_FILE="$2"
GRAFANA_URL="${3:-http://localhost:3000}"
GRAFANA_USER="${4:-${GRAFANA_ADMIN_USER:-admin}}"
GRAFANA_PASSWORD="${5:-${GRAFANA_ADMIN_PASSWORD:-password}}"

RAW_JSON=$(curl -sS -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" "${GRAFANA_URL}/api/dashboards/uid/${DASHBOARD_UID}")

python3 - "$RAW_JSON" "$OUTPUT_FILE" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
out_file = sys.argv[2]

dashboard = payload.get("dashboard")
if dashboard is None:
    message = payload.get("message", "Dashboard payload missing 'dashboard'")
    raise SystemExit(f"Error exporting dashboard: {message}")

dashboard["id"] = None
with open(out_file, "w", encoding="utf-8") as f:
    json.dump(dashboard, f, indent=2)
    f.write("\n")

print(f"Exported dashboard to {out_file}")
PY

