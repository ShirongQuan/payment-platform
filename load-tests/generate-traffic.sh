#!/usr/bin/env bash
#
# generate-traffic.sh
#
# Generates realistic, varied traffic against auth-service/fraud-service/ledger-service so it can
# be observed on the Grafana dashboards: normal auth/capture/reversal flow, idempotency cache hits,
# fraud declines under burst load, circuit breaker open/close transitions, HTTP 5xx errors, and
# dead-letter-topic (DLT) publishes.
#
# Requirements: bash, curl, jq, kcat (`brew install kcat`), uuidgen (macOS built-in)
#
# Usage:
#   ./generate-traffic.sh
#
# Every idempotency key embeds a run-scoped timestamp tag (RUN_TAG, derived from `date +%s`), so
# the whole script can be re-run at any time without idempotency-key collisions against previous
# runs (accountId + idempotencyKey is the uniqueness scope in auth-service).
#
# Phases:
#   1. Bulk authorisations: the first BULK1_COUNT (50) are sent every BULK1_INTERVAL_SECONDS (2s);
#      the next BULK2_COUNT (30) are sent every BULK2_INTERVAL_SECONDS (1s) - the faster cadence is
#      intended to trigger fraud-service velocity-rule declines. Every authorisation and capture
#      request is immediately re-sent with the *same* idempotency key + payload, to generate
#      idempotency cache-hit traffic (reversal requests are sent once only, no duplicate). Odd-suffix
#      keys capture, even-suffix keys reverse.
#   2. Circuit breaker + HTTP 5xx errors: set fraud-service to ALWAYS_503 (every /fraud/check call
#      fails with HTTP 503 - both accumulates failures fast enough to trip auth-service's circuit
#      breaker open, and generates real 5xx samples on fraud-service's own
#      http_server_requests_seconds metrics), fire CB_OPEN_REQUEST_COUNT auths
#      CB_OPEN_INTERVAL_SECONDS apart; hold ALWAYS_503 for CB_HOLD_SECONDS (25s) so the OPEN state is
#      clearly visible and auth-service's waitDurationInOpenState (15s) has elapsed; then reset
#      fraud-service to OFF and fire CB_RECOVERY_REQUEST_COUNT auths CB_RECOVERY_INTERVAL_SECONDS
#      (2s) apart to observe half-open -> closed recovery.
#   3. DLT publish: use kcat to publish records with an invalid eventType directly onto the
#      auth.events topic, per docs/development/runbook.md, so ledger-service routes them straight to
#      the auth.events.ledger.dlt dead-letter topic (non-retryable path).
#   4. Dedup events: delegates to the sibling generate-dedup-events.sh script (same directory) to
#      generate ledger_event_duplicate_skipped_total samples across all event types.
#
# A REST_BETWEEN_STEPS_SECONDS (default 2s) pause is inserted between every top-level step, so
# Grafana panels show a clear gap/boundary between phases instead of one continuous blur.
#
# Fraud-service failure-mode is always reset to OFF at the end (even on error), via an EXIT trap.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:9000}"
FRAUD_BASE_URL="${FRAUD_BASE_URL:-http://localhost:9010}"
LEDGER_BASE_URL="${LEDGER_BASE_URL:-http://localhost:9020}"
KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"

BULK1_COUNT="${BULK1_COUNT:-50}"
BULK1_INTERVAL_SECONDS="${BULK1_INTERVAL_SECONDS:-2}"
BULK2_COUNT="${BULK2_COUNT:-30}"
BULK2_INTERVAL_SECONDS="${BULK2_INTERVAL_SECONDS:-1}"

CB_OPEN_REQUEST_COUNT="${CB_OPEN_REQUEST_COUNT:-10}"
CB_OPEN_INTERVAL_SECONDS="${CB_OPEN_INTERVAL_SECONDS:-1}"
CB_HOLD_SECONDS="${CB_HOLD_SECONDS:-25}"
CB_RECOVERY_REQUEST_COUNT="${CB_RECOVERY_REQUEST_COUNT:-10}"
CB_RECOVERY_INTERVAL_SECONDS="${CB_RECOVERY_INTERVAL_SECONDS:-2}"

DLT_MESSAGE_COUNT="${DLT_MESSAGE_COUNT:-10}"

DEDUP_ROUNDS="${DEDUP_ROUNDS:-10}"
DEDUP_DUPLICATES_PER_EVENT="${DEDUP_DUPLICATES_PER_EVENT:-3}"
DEDUP_INTERVAL_SECONDS="${DEDUP_INTERVAL_SECONDS:-1}"

REST_BETWEEN_STEPS_SECONDS="${REST_BETWEEN_STEPS_SECONDS:-2}"

GBP_ACCOUNT_ID="${GBP_ACCOUNT_ID:-11111111-1111-1111-1111-111111111111}"
USD_ACCOUNT_ID="${USD_ACCOUNT_ID:-22222222-2222-2222-2222-222222222222}"
EUR_ACCOUNT_ID="${EUR_ACCOUNT_ID:-33333333-3333-3333-3333-333333333333}"

# Resolve the directory this script lives in, so generate-dedup-events.sh can be found regardless
# of the caller's current working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for cmd in jq curl kcat uuidgen; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: $cmd is required (brew install $cmd)" >&2
    exit 1
  fi
done

# Run-scoped tag embedded in every idempotency key so keys never collide with a previous run.
# Keep it short - idempotencyKey has a 20-char max across authorisation/capture/reversal endpoints.
RUN_TAG=$(date +%s)
RUN_TAG=${RUN_TAG: -6}

# currency:accountId pairs, cycled through round-robin.
ACCOUNTS=(
  "GBP:${GBP_ACCOUNT_ID}"
  "USD:${USD_ACCOUNT_ID}"
  "EUR:${EUR_ACCOUNT_ID}"
)
NUM_ACCOUNTS=${#ACCOUNTS[@]}

GLOBAL_SEQ=0

reset_fraud_mode() {
  curl -sS -X POST "${FRAUD_BASE_URL}/internal/test/failure-mode" \
    -H "Content-Type: application/json" \
    -d '{"mode":"OFF"}' >/dev/null 2>&1 || true
}
trap reset_fraud_mode EXIT

set_fraud_mode() {
  local mode="$1"
  local resp
  resp=$(curl -sS -w '\n%{http_code}' -X POST "${FRAUD_BASE_URL}/internal/test/failure-mode" \
    -H "Content-Type: application/json" \
    -d "{\"mode\":\"${mode}\"}")
  local code
  code=$(echo "$resp" | tail -n1)
  echo "  -> fraud-service failure-mode set to ${mode} (http=${code})"
}

# Sleep interval so `count` requests are spread evenly over `window` seconds (used only if a
# window-based phase is configured; the bulk phases now use explicit per-request intervals).
interval_for() {
  local window="$1" count="$2"
  awk -v w="$window" -v n="$count" 'BEGIN { if (n<=1) print 0; else printf "%.2f", w/(n-1) }'
}

next_ikey() {
  GLOBAL_SEQ=$((GLOBAL_SEQ + 1))
  # Set (not echo, to avoid a subshell that would lose the GLOBAL_SEQ increment).
  IKEY=$(printf "ikey-%s-%03d" "$RUN_TAG" "$GLOBAL_SEQ")
}

# Sends one authorisation request. Echoes result. Sets AUTH_ID/AUTH_STATUS globals.
send_authorisation() {
  local ikey="$1" account_id="$2" currency="$3"
  local merchant_ref="order-${ikey}"

  local payload
  payload=$(jq -n \
    --arg accountId "$account_id" \
    --arg idempotencyKey "$ikey" \
    --arg currencyCode "$currency" \
    --arg merchantReference "$merchant_ref" \
    '{accountId: $accountId, idempotencyKey: $idempotencyKey, amount: 1.00, currencyCode: $currencyCode, merchantReference: $merchantReference}')

  local resp code body
  resp=$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/authorisations" \
    -H "Content-Type: application/json" \
    -d "$payload")
  code=$(echo "$resp" | tail -n1)
  body=$(echo "$resp" | sed '$d')

  AUTH_ID=""
  AUTH_STATUS=""
  if [ "$code" = "200" ]; then
    AUTH_ID=$(echo "$body" | jq -r '.id // empty')
    AUTH_STATUS=$(echo "$body" | jq -r '.status // empty')
  fi
  echo "[$ikey] account=${currency} http=${code} authId=${AUTH_ID:-n/a} status=${AUTH_STATUS:-n/a}"
}

capture_authorisation() {
  local ikey="$1" auth_id="$2"
  local capture_key="cap-${RUN_TAG}-${ikey##*-}"
  local payload resp code
  payload=$(jq -n --arg idempotencyKey "$capture_key" '{idempotencyKey: $idempotencyKey}')

  resp=$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/authorisations/${auth_id}/captures" \
    -H "Content-Type: application/json" -d "$payload")
  code=$(echo "$resp" | tail -n1)
  echo "[$ikey]   -> capture (odd key) http=${code}"

  # Immediate duplicate: same idempotency key + same payload -> idempotent replay / cache hit.
  resp=$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/authorisations/${auth_id}/captures" \
    -H "Content-Type: application/json" -d "$payload")
  code=$(echo "$resp" | tail -n1)
  echo "[$ikey]   -> capture (odd key) http=${code}   ^-- duplicate (expect idempotent replay / cache hit)"
}

reverse_authorisation() {
  local ikey="$1" auth_id="$2"
  local reverse_key="rev-${RUN_TAG}-${ikey##*-}"
  local payload resp code
  payload=$(jq -n --arg idempotencyKey "$reverse_key" \
    '{idempotencyKey: $idempotencyKey, reasonCode: "CUSTOMER_REQUEST"}')

  resp=$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/authorisations/${auth_id}/reversals" \
    -H "Content-Type: application/json" -d "$payload")
  code=$(echo "$resp" | tail -n1)
  echo "[$ikey]   -> reversal (even key) http=${code}"
}

# ---------------------------------------------------------------------------
# Phase 1: bulk authorisations at a fixed cadence (e.g. 50 @ 2s, then 30 @ 1s). Authorisation and
# capture requests are each duplicated immediately for an idempotency cache hit; reversal is sent
# once only.
# ---------------------------------------------------------------------------
run_bulk_phase() {
  local count="$1" interval="$2" label="$3"

  echo "=== ${label}: ${count} auth requests, ${interval}s apart ==="

  for ((n = 1; n <= count; n++)); do
    local acct_index=$(( (GLOBAL_SEQ) % NUM_ACCOUNTS ))
    local pair="${ACCOUNTS[$acct_index]}"
    local currency="${pair%%:*}"
    local account_id="${pair##*:}"

    local ikey
    next_ikey
    ikey="$IKEY"

    send_authorisation "$ikey" "$account_id" "$currency"
    local first_auth_id="$AUTH_ID" first_status="$AUTH_STATUS"

    # Immediate duplicate: same idempotency key + same payload -> idempotent replay / cache hit.
    send_authorisation "$ikey" "$account_id" "$currency"
    echo "[$ikey]   ^-- duplicate of previous request (expect idempotent replay / cache hit)"

    if [ "$first_status" = "AUTHORISED" ] && [ -n "$first_auth_id" ]; then
      local seq_num=${ikey##*-}
      if (( 10#$seq_num % 2 == 1 )); then
        capture_authorisation "$ikey" "$first_auth_id"
      else
        reverse_authorisation "$ikey" "$first_auth_id"
      fi
    else
      echo "[$ikey]   -> not authorised (status=${first_status:-n/a}), skipping capture/reversal"
    fi

    sleep "$interval"
  done
}

# ---------------------------------------------------------------------------
# Phase 2: circuit breaker open -> held open -> half-open -> closed, using ALWAYS_503
# (also generates HTTP 5xx samples on fraud-service's own metrics while the breaker is closed).
# ---------------------------------------------------------------------------
run_circuit_breaker_phase() {
  echo
  echo "### Testing circuit breaker + HTTP 5xx errors: setting fraud-service mode to ALWAYS_503 ###"
  echo "    (every /fraud/check call now fails with HTTP 503 - this both accumulates failures"
  echo "     fast enough to trip the circuit breaker OPEN, and generates real 5xx samples on"
  echo "     fraud-service's own http_server_requests_seconds_count{status=\"503\"} metric)"
  set_fraud_mode "ALWAYS_503"

  for ((n = 1; n <= CB_OPEN_REQUEST_COUNT; n++)); do
    local acct_index=$(( GLOBAL_SEQ % NUM_ACCOUNTS ))
    local pair="${ACCOUNTS[$acct_index]}"
    local currency="${pair%%:*}"
    local account_id="${pair##*:}"
    local ikey
    next_ikey
    ikey="$IKEY"
    send_authorisation "$ikey" "$account_id" "$currency"
    sleep "$CB_OPEN_INTERVAL_SECONDS"
  done

  echo
  echo "### Holding fraud-service in ALWAYS_503 mode for ${CB_HOLD_SECONDS}s ###"
  echo "    (keeps the circuit breaker visibly OPEN and lets auth-service's 15s"
  echo "     waitDurationInOpenState fully elapse before we test recovery)"
  sleep "$CB_HOLD_SECONDS"

  echo
  echo "### Resetting fraud-service mode to OFF to observe circuit breaker recovery ###"
  echo "    (half-open -> closed as trial calls succeed)"
  set_fraud_mode "OFF"

  for ((n = 1; n <= CB_RECOVERY_REQUEST_COUNT; n++)); do
    local acct_index=$(( GLOBAL_SEQ % NUM_ACCOUNTS ))
    local pair="${ACCOUNTS[$acct_index]}"
    local currency="${pair%%:*}"
    local account_id="${pair##*:}"
    local ikey
    next_ikey
    ikey="$IKEY"
    send_authorisation "$ikey" "$account_id" "$currency"
    sleep "$CB_RECOVERY_INTERVAL_SECONDS"
  done
}

# ---------------------------------------------------------------------------
# Phase 3: DLT publish, per docs/development/runbook.md
# ---------------------------------------------------------------------------
run_dlt_phase() {
  echo
  echo "### Generating dead-letter-topic (DLT) traffic ###"
  echo "    (publishing ${DLT_MESSAGE_COUNT} records with eventType=UNSUPPORTED_EVENT_TYPE"
  echo "     directly onto auth.events via kcat - ledger-service's non-retryable path routes"
  echo "     these straight to auth.events.ledger.dlt; see docs/development/runbook.md)"

  for ((n = 1; n <= DLT_MESSAGE_COUNT; n++)); do
    local event_id agg_id corr_id key
    event_id=$(uuidgen)
    agg_id=$(uuidgen)
    corr_id=$(uuidgen)
    key=$(uuidgen)
    echo '{"test":"payload"}' | kcat -P -b "$KAFKA_BROKER" -t auth.events \
      -k "$key" \
      -H "eventId=$event_id" \
      -H "aggregateType=AUTHORISATION" \
      -H "aggregateId=$agg_id" \
      -H "eventType=UNSUPPORTED_EVENT_TYPE" \
      -H "occurredAt=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" \
      -H "correlationId=$corr_id"
    echo "  -> sent DLT-bound record eventId=$event_id"
    sleep 1
  done

  echo "  -> verify via: curl -s ${LEDGER_BASE_URL}/actuator/prometheus | grep ledger_kafka"
}

# ---------------------------------------------------------------------------
# Phase 4: dedup events, delegates to the sibling generate-dedup-events.sh script.
# ---------------------------------------------------------------------------
run_dedup_events_phase() {
  echo
  echo "### Generating ledger dedup-metric traffic (ledger_event_duplicate_skipped_total) ###"
  echo "    (delegating to generate-dedup-events.sh: ROUNDS=${DEDUP_ROUNDS}"
  echo "     DUPLICATES_PER_EVENT=${DEDUP_DUPLICATES_PER_EVENT} INTERVAL_SECONDS=${DEDUP_INTERVAL_SECONDS})"

  KAFKA_BROKER="$KAFKA_BROKER" \
  LEDGER_BASE_URL="$LEDGER_BASE_URL" \
  ROUNDS="$DEDUP_ROUNDS" \
  DUPLICATES_PER_EVENT="$DEDUP_DUPLICATES_PER_EVENT" \
  INTERVAL_SECONDS="$DEDUP_INTERVAL_SECONDS" \
    "${SCRIPT_DIR}/generate-dedup-events.sh"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
echo "== payment-platform test-data generator =="
echo "Run tag:        ${RUN_TAG} (embedded in every idempotency key this run)"
echo "Auth base URL:  ${BASE_URL}"
echo "Fraud base URL: ${FRAUD_BASE_URL}"
echo "Ledger base URL:${LEDGER_BASE_URL}"
echo "Accounts:       ${ACCOUNTS[*]}"
echo "==========================================="

echo
echo "### Phase 1: bulk authorisations (${BULK1_COUNT} every ${BULK1_INTERVAL_SECONDS}s, then ${BULK2_COUNT} every ${BULK2_INTERVAL_SECONDS}s) ###"
run_bulk_phase "$BULK1_COUNT" "$BULK1_INTERVAL_SECONDS" "Phase 1a (steady traffic)"
sleep "$REST_BETWEEN_STEPS_SECONDS"

echo
echo "### Speeding up to every ${BULK2_INTERVAL_SECONDS}s for the next ${BULK2_COUNT} requests, to trigger fraud-service velocity-rule declines ###"
run_bulk_phase "$BULK2_COUNT" "$BULK2_INTERVAL_SECONDS" "Phase 1b (burst traffic)"
sleep "$REST_BETWEEN_STEPS_SECONDS"

run_circuit_breaker_phase
sleep "$REST_BETWEEN_STEPS_SECONDS"

run_dlt_phase
sleep "$REST_BETWEEN_STEPS_SECONDS"

run_dedup_events_phase

echo
echo "==========================================="
echo "Done. Sent $((BULK1_COUNT + BULK2_COUNT)) bulk auths (x2 for idempotency replay, plus x2 on their capture calls),"
echo "$((CB_OPEN_REQUEST_COUNT + CB_RECOVERY_REQUEST_COUNT)) circuit-breaker/5xx-phase auths,"
echo "${DLT_MESSAGE_COUNT} DLT-bound Kafka records, and ${DEDUP_ROUNDS} rounds of dedup-event traffic"
echo "(via generate-dedup-events.sh)."
echo "fraud-service failure-mode has been reset to OFF."

