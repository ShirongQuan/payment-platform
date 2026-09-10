#!/usr/bin/env bash
#
# generate-dedup-events.sh
#
# Generates test data for the Grafana metric:
#   sum by (eventType) (rate(ledger_event_duplicate_skipped_total{application="ledger-service"}[5m]) * 60)
#
# How it works: ledger-service's Kafka consumer deduplicates by eventId (see
# ProcessedEventRepository / AuthorisationAuthorisedHandler|CapturedHandler|ReversedHandler). Each
# handler does an atomic "insert-or-do-nothing" on processed_event(event_id); if a record with the
# same eventId is delivered again, the insert affects 0 rows and the handler short-circuits,
# incrementing ledger_event_duplicate_skipped_total{eventType=...} instead of reprocessing it.
#
# So the most direct way to generate this metric is exactly what you'd expect: publish the SAME
# Kafka record (same eventId, same headers/payload) to auth.events more than once. This script does
# that for all three ledger-relevant event types (AUTHORISATION_AUTHORISED/CAPTURED/REVERSED), since
# the Grafana query groups by eventType, and repeats it over several rounds so the metric shows up
# as a genuine rate over time rather than a single instantaneous blip.
#
# Requirements: bash, curl, jq, kcat (`brew install kcat`), uuidgen (macOS built-in)
#
# IMPORTANT kcat gotcha: kcat -P treats each LINE of stdin as a separate Kafka message. Pretty-
# printed JSON (e.g. `jq -n` without `-c`) is multi-line and gets split into multiple malformed
# records, which land straight in the DLT instead of testing dedup. This script always uses
# `jq -nc` (compact, single-line) to avoid that.
#
# Usage:
#   ./generate-dedup-events.sh
#   ROUNDS=5 DUPLICATES_PER_EVENT=3 INTERVAL_SECONDS=2 ./generate-dedup-events.sh

set -euo pipefail

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
LEDGER_BASE_URL="${LEDGER_BASE_URL:-http://localhost:9020}"
ACCOUNT_ID="${ACCOUNT_ID:-11111111-1111-1111-1111-111111111111}"

ROUNDS="${ROUNDS:-5}"
DUPLICATES_PER_EVENT="${DUPLICATES_PER_EVENT:-2}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-2}"

for cmd in jq curl kcat uuidgen; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: $cmd is required (brew install $cmd)" >&2
    exit 1
  fi
done

echo "== ledger dedup-metric test-data generator =="
echo "Kafka broker:   ${KAFKA_BROKER}"
echo "Ledger base URL:${LEDGER_BASE_URL}"
echo "Rounds:         ${ROUNDS} (one AUTHORISED + CAPTURED + REVERSED event per round)"
echo "Duplicates:     ${DUPLICATES_PER_EVENT} extra deliveries per event (beyond the first)"
echo "==========================================="

# Publishes $2 to auth.events with the given headers, $DUPLICATES_PER_EVENT extra times (same
# eventId each time), after an initial first-time delivery.
publish_with_duplicates() {
  local event_type="$1" event_id="$2" agg_id="$3" corr_id="$4" occurred_at="$5" payload="$6"
  local key
  key=$(uuidgen)

  echo "[$event_type] eventId=$event_id -> first delivery (should process normally)"
  echo "$payload" | kcat -P -b "$KAFKA_BROKER" -t auth.events -k "$key" \
    -H "eventId=$event_id" -H "aggregateType=AUTHORISATION" -H "aggregateId=$agg_id" \
    -H "eventType=$event_type" -H "occurredAt=$occurred_at" -H "correlationId=$corr_id"

  sleep 1

  for ((d = 1; d <= DUPLICATES_PER_EVENT; d++)); do
    echo "[$event_type] eventId=$event_id -> duplicate delivery #${d} (should increment ledger_event_duplicate_skipped_total)"
    echo "$payload" | kcat -P -b "$KAFKA_BROKER" -t auth.events -k "$key" \
      -H "eventId=$event_id" -H "aggregateType=AUTHORISATION" -H "aggregateId=$agg_id" \
      -H "eventType=$event_type" -H "occurredAt=$occurred_at" -H "correlationId=$corr_id"
  done
}

for ((r = 1; r <= ROUNDS; r++)); do
  echo
  echo "### Round ${r}/${ROUNDS} ###"

  NOW=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)

  # --- AUTHORISATION_AUTHORISED ---
  AUTH_ID=$(uuidgen)
  EVENT_ID=$(uuidgen)
  CORR_ID=$(uuidgen)
  PAYLOAD=$(jq -nc \
    --arg authorisationId "$AUTH_ID" --arg accountId "$ACCOUNT_ID" \
    --arg idempotencyKey "dedup-auth-${r}" --arg merchantReference "order-dedup-${r}" \
    --arg currencyCode "GBP" --arg status "AUTHORISED" --arg createdAt "$NOW" \
    '{authorisationId:$authorisationId, accountId:$accountId, idempotencyKey:$idempotencyKey, merchantReference:$merchantReference, amount:1.00, currencyCode:$currencyCode, status:$status, createdAt:$createdAt}')
  publish_with_duplicates "AUTHORISATION_AUTHORISED" "$EVENT_ID" "$AUTH_ID" "$CORR_ID" "$NOW" "$PAYLOAD"

  # --- AUTHORISATION_CAPTURED ---
  EVENT_ID=$(uuidgen)
  CORR_ID=$(uuidgen)
  PAYLOAD=$(jq -nc \
    --arg authorisationId "$AUTH_ID" --arg accountId "$ACCOUNT_ID" \
    --arg idempotencyKey "dedup-cap-${r}" --arg currencyCode "GBP" --arg status "CAPTURED" \
    --arg capturedAt "$NOW" \
    '{authorisationId:$authorisationId, accountId:$accountId, amount:1.00, currencyCode:$currencyCode, idempotencyKey:$idempotencyKey, status:$status, capturedAt:$capturedAt}')
  publish_with_duplicates "AUTHORISATION_CAPTURED" "$EVENT_ID" "$AUTH_ID" "$CORR_ID" "$NOW" "$PAYLOAD"

  # --- AUTHORISATION_REVERSED ---
  EVENT_ID=$(uuidgen)
  CORR_ID=$(uuidgen)
  PAYLOAD=$(jq -nc \
    --arg authorisationId "$AUTH_ID" --arg accountId "$ACCOUNT_ID" \
    --arg idempotencyKey "dedup-rev-${r}" --arg currencyCode "GBP" --arg status "REVERSED" \
    --arg reversedAt "$NOW" --arg reasonCode "CUSTOMER_REQUEST" \
    '{authorisationId:$authorisationId, accountId:$accountId, amount:1.00, currencyCode:$currencyCode, idempotencyKey:$idempotencyKey, status:$status, reversedAt:$reversedAt, reasonCode:$reasonCode}')
  publish_with_duplicates "AUTHORISATION_REVERSED" "$EVENT_ID" "$AUTH_ID" "$CORR_ID" "$NOW" "$PAYLOAD"

  sleep "$INTERVAL_SECONDS"
done

echo
echo "==========================================="
echo "Done. Published ${ROUNDS} rounds x 3 event types, each with 1 first-time delivery +"
echo "${DUPLICATES_PER_EVENT} duplicate deliveries."
echo
echo "Verify with:"
echo "  curl -s ${LEDGER_BASE_URL}/actuator/prometheus | grep ledger_event_duplicate_skipped_total"
echo
echo "Grafana/Prometheus query:"
echo "  sum by (eventType) (rate(ledger_event_duplicate_skipped_total{application=\"ledger-service\"}[5m]) * 60)"

