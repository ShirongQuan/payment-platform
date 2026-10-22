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
#   ./generate-traffic.sh START_STEP
#
# START_STEP (optional, default 1): top-level step number to start from, skipping every earlier
# step entirely (no Return-key pause before the first step that actually runs). Steps:
#   1 = Phase 1 (bulk authorisations)
#   2 = Phase 2 (concurrency conflicts)
#   3 = Phase 3 (circuit breaker open -> circuit breaker recovery -> fail-open demo)
#   4 = Phase 4 (dedup events)
#   5 = Phase 5 (dead-letter-topic publish)
#   6 = Phase 6 (fraud-service latency/TimeLimiter demo: baseline auth, then DELAY_2S timeout)
#   7 = Phase 7 (fraud-service recovery: reset to OFF, confirm breaker CLOSED, real fraud checks, final step)
# Every step from START_STEP onward still runs in order, each still followed by the usual
# Return-key pause (or REST_BETWEEN_STEPS_SECONDS sleep in non-interactive mode) before the next
# one. e.g. `./generate-traffic.sh 2` skips Phase 1 and starts immediately at Phase 2
# (concurrency conflicts), then still pauses for Return before Phase 3, 4, 5, 6, and 7.
#
# Every idempotency key embeds a run-scoped timestamp tag (RUN_TAG, derived from `date +%s`), so
# the whole script can be re-run at any time without idempotency-key collisions against previous
# runs (accountId + idempotencyKey is the uniqueness scope in auth-service).
#
# Accounts: GBP_ACCOUNT_ID (11111111-1111-1111-1111-111111111111) is the only UUID configured in
# fraud-service's fraud.fail-open.trusted-account-ids, so it is used EXCLUSIVELY by the fail-open
# demo (phase 3c-b) and never appears in the general round-robin traffic below. By default the
# script auto-provisions 3 brand-new, dedicated accounts (one per currency: GBP/USD/EUR) via
# POST /accounts + POST /accounts/{id}/deposits at startup, and uses those for every other phase
# (bulk, circuit breaker open/recovery). Set AUTO_PROVISION_ROUND_ROBIN_ACCOUNTS=false plus
# ROUND_ROBIN_{GBP,USD,EUR}_ACCOUNT_ID to reuse specific pre-existing accounts instead.
#
# Phases:
#   1. Bulk authorisations: BULK1_COUNT (50) requests are sent every BULK1_INTERVAL_SECONDS (2s).
#      Every authorisation and capture request is immediately re-sent with the *same* idempotency
#      key + payload, to generate idempotency cache-hit traffic (reversal requests are sent once
#      only, no duplicate). Odd-suffix keys capture, even-suffix keys reverse. After each auth set,
#      ledger-service is queried via GET /authorisations/{authorisationId}. After the bulk phase
#      completes, ledger-service is queried via GET /accounts/{accountId}/events once per
#      configured account to generate HTTP metrics.
#   2. Concurrency conflicts: delegates to the sibling generate-concurrency-conflicts.sh script
#      (same directory) to generate auth_concurrency_conflict_total{operation, type} samples
#      (type=idempotency_race and type=optimistic_lock) via truly concurrent (backgrounded + wait)
#      authorise/capture/reverse requests.
#   3. Circuit breaker + HTTP 5xx errors, split into three sub-steps with a Return-key pause
#      in between:
#        3a. Set fraud-service to ALWAYS_503 (every /fraud/check call fails with HTTP 503 - both
#            accumulates failures fast enough to trip auth-service's circuit breaker open, and
#            generates real 5xx samples on fraud-service's own http_server_requests_seconds
#            metrics). Send paced authorisations with fresh idempotency keys (CB_OPEN_INTERVAL_SECONDS
#            apart) until the breaker opens, then continue sending a few more requests after OPEN;
#            hold ALWAYS_503 for CB_HOLD_SECONDS (25s) so the OPEN state is clearly visible and
#            auth-service's waitDurationInOpenState (15s) has elapsed.
#        3b. Restore fraud-service to OFF, then simply fire CB_RECOVERY_REQUEST_COUNT auths
#            CB_RECOVERY_INTERVAL_SECONDS (2s) apart - no active polling/confirmation beforehand,
#            just real traffic - and observe resilience4j's own half-open -> closed recovery play
#            out naturally as the requests succeed.
#        3c. Fail-open policy demo, starting from the confirmed CLOSED breaker left by 3b: set
#            fraud-service to ALWAYS_503 again, then send two deterministic requests with fresh
#            idempotency keys, so FraudOrchestrator's fail-open policy (trusted account + amount <=
#            fraud.fail-open.max-amount) is the only thing that can distinguish their outcomes:
#              i.  An ineligible request from an untrusted account (USD) -> expect DECLINE
#                  (fail-open does not apply).
#              ii. An eligible request from a trusted account (GBP), within the configured
#                  fail-open amount limit (£2, <= 5.00) -> expect APPROVE via the fail-open policy.
#            A Return-key pause follows step ii (outage still active) so the approved result can be
#            explained while fraud-service is still down, before fraud-service is reset to OFF.
#   4. Dedup events: delegates to the sibling generate-dedup-events.sh script (same directory) to
#      generate ledger_event_duplicate_skipped_total samples across all event types.
#   5. DLT publish: use kcat to publish records with an invalid eventType directly onto the
#      auth.events topic, per docs/development/runbook.md, so ledger-service routes them straight to
#      the auth.events.ledger.dlt dead-letter topic (non-retryable path).
#   6. Fraud-service latency/TimeLimiter demo, split into two sub-steps with a Return-key pause
#      in between:
#        6a. Baseline: send one fresh authorisation with fraud-service healthy (mode OFF), to show
#            a normal, fast, real fraud-service check succeeding.
#        6b. Set fraud-service mode to DELAY_2S (every /fraud/check call now sleeps 2s before
#            responding), then send a comparable authorisation (same account/currency/amount as
#            6a) using a different fresh idempotency key. auth-service's fraudService TimeLimiter
#            is 250ms, so this call times out and is treated as UNAVAILABLE -> DECLINE (this
#            account is not in fraud.fail-open.trusted-account-ids, unlike phase 3c's dedicated
#            fail-open demo). fraud-service is deliberately left in DELAY_2S mode afterwards -
#            phase 7 below is responsible for resetting it.
#   7. Fraud-service recovery: reset fraud-service mode to OFF, actively drive the fraudService
#      circuit breaker to a confirmed CLOSED state (reusing the same wait_for_fraud_circuit_breaker_closed
#      helper as phase 3c), then send a few fresh authorisations that each perform a real,
#      successful fraud-service check end to end.
#
# Between top-level steps, the script can pause for keyboard Return (interactive mode), or fall
# back to a REST_BETWEEN_STEPS_SECONDS (default 2s) sleep when non-interactive.
#
# Fraud-service failure-mode is always reset to OFF at the end (even on error), via an EXIT trap.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:9000}"
FRAUD_BASE_URL="${FRAUD_BASE_URL:-http://localhost:9010}"
LEDGER_BASE_URL="${LEDGER_BASE_URL:-http://localhost:9020}"
KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"

BULK1_COUNT="${BULK1_COUNT:-50}"
BULK1_INTERVAL_SECONDS="${BULK1_INTERVAL_SECONDS:-2}"

CB_OPEN_REQUEST_COUNT="${CB_OPEN_REQUEST_COUNT:-10}"
CB_OPEN_INTERVAL_SECONDS="${CB_OPEN_INTERVAL_SECONDS:-1}"
CB_HOLD_SECONDS="${CB_HOLD_SECONDS:-25}"
CB_RECOVERY_SETTLE_SECONDS="${CB_RECOVERY_SETTLE_SECONDS:-16}"
CB_RECOVERY_SETTLE_MAX_ATTEMPTS="${CB_RECOVERY_SETTLE_MAX_ATTEMPTS:-3}"
CB_RECOVERY_REQUEST_COUNT="${CB_RECOVERY_REQUEST_COUNT:-10}"
CB_RECOVERY_INTERVAL_SECONDS="${CB_RECOVERY_INTERVAL_SECONDS:-2}"

DLT_MESSAGE_COUNT="${DLT_MESSAGE_COUNT:-10}"

FRAUD_RECOVERY_REQUEST_COUNT="${FRAUD_RECOVERY_REQUEST_COUNT:-3}"
FRAUD_RECOVERY_INTERVAL_SECONDS="${FRAUD_RECOVERY_INTERVAL_SECONDS:-1}"

DEDUP_ROUNDS="${DEDUP_ROUNDS:-1}"
DEDUP_DUPLICATES_PER_EVENT="${DEDUP_DUPLICATES_PER_EVENT:-2}"
DEDUP_INTERVAL_SECONDS="${DEDUP_INTERVAL_SECONDS:-1}"

CONCURRENCY_ROUNDS="${CONCURRENCY_ROUNDS:-1}"
CONCURRENCY_PARALLEL_REQUESTS="${CONCURRENCY_PARALLEL_REQUESTS:-6}"
CONCURRENCY_INTERVAL_SECONDS="${CONCURRENCY_INTERVAL_SECONDS:-2}"

REST_BETWEEN_STEPS_SECONDS="${REST_BETWEEN_STEPS_SECONDS:-2}"
PAUSE_FOR_ENTER="${PAUSE_FOR_ENTER:-true}"

GBP_ACCOUNT_ID="${GBP_ACCOUNT_ID:-11111111-1111-1111-1111-111111111111}"
USD_ACCOUNT_ID="${USD_ACCOUNT_ID:-22222222-2222-2222-2222-222222222222}"
EUR_ACCOUNT_ID="${EUR_ACCOUNT_ID:-33333333-3333-3333-3333-333333333333}"

# GBP_ACCOUNT_ID above is the only entry in fraud-service's fraud.fail-open.trusted-account-ids
# config - it is reserved EXCLUSIVELY for the fail-open demo's "eligible" sub-step (phase 3c-b).
# It must never appear in the general round-robin ACCOUNTS list below: whenever fraud-service is
# forced unavailable (circuit-breaker-open phase, fail-open demo), the fail-open policy would
# silently convert that account's expected DECLINE into an AUTHORISED response for small amounts -
# the circuit breaker itself still records the underlying call as a failure either way, but the
# visible auth outcome for that account would misleadingly look like a healthy approval, muddying
# the circuit-breaker-open/recovery demos. So instead, 3 brand-new, dedicated test accounts are
# auto-provisioned below (one per currency) purely for general round-robin traffic, leaving
# GBP_ACCOUNT_ID completely untouched by anything except the fail-open demo. Set
# AUTO_PROVISION_ROUND_ROBIN_ACCOUNTS=false and supply ROUND_ROBIN_{GBP,USD,EUR}_ACCOUNT_ID instead
# to reuse specific pre-existing accounts (e.g. USD_ACCOUNT_ID/EUR_ACCOUNT_ID above) if preferred.
AUTO_PROVISION_ROUND_ROBIN_ACCOUNTS="${AUTO_PROVISION_ROUND_ROBIN_ACCOUNTS:-true}"
ROUND_ROBIN_ACCOUNT_FUNDING_AMOUNT="${ROUND_ROBIN_ACCOUNT_FUNDING_AMOUNT:-100000.00}"
ROUND_ROBIN_GBP_ACCOUNT_ID="${ROUND_ROBIN_GBP_ACCOUNT_ID:-}"
ROUND_ROBIN_USD_ACCOUNT_ID="${ROUND_ROBIN_USD_ACCOUNT_ID:-}"
ROUND_ROBIN_EUR_ACCOUNT_ID="${ROUND_ROBIN_EUR_ACCOUNT_ID:-}"

# Resolve the directory this script lives in, so generate-dedup-events.sh can be found regardless
# of the caller's current working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for cmd in jq curl kcat uuidgen; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: $cmd is required (brew install $cmd)" >&2
    exit 1
  fi
done

# Optional first positional arg: top-level step number (1-7) to start from - see the Usage/START_STEP
# comment near the top of this file for the full step list.
START_STEP="${1:-1}"
if ! [[ "$START_STEP" =~ ^[0-9]+$ ]] || [ "$START_STEP" -lt 1 ] || [ "$START_STEP" -gt 7 ]; then
  echo "ERROR: START_STEP must be an integer between 1 and 7 (got: '${START_STEP}')" >&2
  echo "Usage: $0 [START_STEP]   (1=Phase 1, 2=Phase 2, 3=Phase 3, 4=Phase 4, 5=Phase 5, 6=Phase 6, 7=Phase 7)" >&2
  exit 1
fi

# Run-scoped tag embedded in every idempotency key so keys never collide with a previous run.
# Keep it short - idempotencyKey has a 20-char max across authorisation/capture/reversal endpoints.
RUN_TAG=$(date +%s)
RUN_TAG=${RUN_TAG: -6}

# Creates a fresh, well-funded, ACTIVE account for the given currency and sets
# PROVISIONED_ACCOUNT_ID to it. Used to build the round-robin ACCOUNTS list below without ever
# touching GBP_ACCOUNT_ID (reserved for the fail-open demo only - see the comment above it).
provision_fresh_account() {
  local currency="$1"
  local create_resp account_id deposit_resp
  create_resp=$(curl -sS -X POST "${BASE_URL}/accounts" \
    -H "Content-Type: application/json" \
    -d "$(jq -nc --arg currencyCode "$currency" '{currencyCode:$currencyCode}')")
  account_id=$(echo "$create_resp" | jq -r '.accountId // empty')
  if [ -z "$account_id" ]; then
    echo "ERROR: failed to provision a fresh ${currency} round-robin test account, response: $create_resp" >&2
    exit 1
  fi
  deposit_resp=$(curl -sS -X POST "${BASE_URL}/accounts/${account_id}/deposits" \
    -H "Content-Type: application/json" \
    -d "$(jq -nc --arg amount "$ROUND_ROBIN_ACCOUNT_FUNDING_AMOUNT" --arg currencyCode "$currency" \
      '{amount:($amount|tonumber), currencyCode:$currencyCode}')")
  echo "  -> provisioned ${currency} round-robin test account ${account_id}, deposited ${ROUND_ROBIN_ACCOUNT_FUNDING_AMOUNT} ${currency}"
  echo "     $(echo "$deposit_resp" | jq -c '{availableBalance, reservedBalance, status}' 2>/dev/null || echo "$deposit_resp")"
  PROVISIONED_ACCOUNT_ID="$account_id"
}

if [ "$AUTO_PROVISION_ROUND_ROBIN_ACCOUNTS" = "true" ]; then
  echo "Auto-provisioning 3 fresh, dedicated round-robin test accounts (GBP/USD/EUR)..."
  provision_fresh_account "GBP"
  ROUND_ROBIN_GBP_ACCOUNT_ID="$PROVISIONED_ACCOUNT_ID"
  provision_fresh_account "USD"
  ROUND_ROBIN_USD_ACCOUNT_ID="$PROVISIONED_ACCOUNT_ID"
  provision_fresh_account "EUR"
  ROUND_ROBIN_EUR_ACCOUNT_ID="$PROVISIONED_ACCOUNT_ID"
elif [ -z "$ROUND_ROBIN_GBP_ACCOUNT_ID" ] || [ -z "$ROUND_ROBIN_USD_ACCOUNT_ID" ] || [ -z "$ROUND_ROBIN_EUR_ACCOUNT_ID" ]; then
  echo "ERROR: AUTO_PROVISION_ROUND_ROBIN_ACCOUNTS=false requires all of ROUND_ROBIN_GBP_ACCOUNT_ID," >&2
  echo "       ROUND_ROBIN_USD_ACCOUNT_ID and ROUND_ROBIN_EUR_ACCOUNT_ID to be set." >&2
  exit 1
else
  echo "Using pre-existing round-robin accountIds supplied via ROUND_ROBIN_*_ACCOUNT_ID env vars."
fi

# currency:accountId pairs, cycled through round-robin. Deliberately excludes GBP_ACCOUNT_ID - see
# the comment above it.
ACCOUNTS=(
  "GBP:${ROUND_ROBIN_GBP_ACCOUNT_ID}"
  "USD:${ROUND_ROBIN_USD_ACCOUNT_ID}"
  "EUR:${ROUND_ROBIN_EUR_ACCOUNT_ID}"
)
NUM_ACCOUNTS=${#ACCOUNTS[@]}

# Untrusted account used for the fail-open demo's "ineligible" sub-step (phase 3c-a) - any account
# other than GBP_ACCOUNT_ID works (fraud-service only trusts that one UUID), so we simply reuse the
# round-robin USD account rather than provisioning yet another dedicated one.
UNTRUSTED_ACCOUNT_ID="${UNTRUSTED_ACCOUNT_ID:-$ROUND_ROBIN_USD_ACCOUNT_ID}"

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

wait_for_next_step() {
  # Prompt only when stdin is a TTY; otherwise fall back to a deterministic sleep for automation.
  if [ "$PAUSE_FOR_ENTER" = "true" ] && [ -t 0 ]; then
    echo
    read -r -p "Press Return to continue to next step..."
  else
    sleep "$REST_BETWEEN_STEPS_SECONDS"
  fi

  # Clear the previous step's output so each step starts with a clean screen.
  # Only do this when stdout is an actual terminal (not when redirected/piped to a log file).
  if [ -t 1 ]; then
    clear
  fi
}

next_ikey() {
  GLOBAL_SEQ=$((GLOBAL_SEQ + 1))
  # Set (not echo, to avoid a subshell that would lose the GLOBAL_SEQ increment).
  IKEY=$(printf "ikey-%s-%03d" "$RUN_TAG" "$GLOBAL_SEQ")
}

# Sends one authorisation request. Echoes result. Sets AUTH_ID/AUTH_STATUS globals.
# amount defaults to 1.00 if not given (4th arg).
send_authorisation() {
  local ikey="$1" account_id="$2" currency="$3" amount="${4:-1.00}"
  local merchant_ref="order-${ikey}"

  local payload
  payload=$(jq -n \
    --arg accountId "$account_id" \
    --arg idempotencyKey "$ikey" \
    --argjson amount "$amount" \
    --arg currencyCode "$currency" \
    --arg merchantReference "$merchant_ref" \
    '{accountId: $accountId, idempotencyKey: $idempotencyKey, amount: $amount, currencyCode: $currencyCode, merchantReference: $merchantReference}')

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

query_ledger_account_events() {
  local context_label="$1" account_id="$2"
  local resp code

  resp=$(curl -sS -w '\n%{http_code}' "${LEDGER_BASE_URL}/accounts/${account_id}/events")
  code=$(echo "$resp" | tail -n1)
  echo "[${context_label}] -> ledger GET /accounts/${account_id}/events http=${code}"
}

query_ledger_authorisation() {
  local ikey="$1" auth_id="$2"
  local resp code

  resp=$(curl -sS -w '\n%{http_code}' "${LEDGER_BASE_URL}/authorisations/${auth_id}")
  code=$(echo "$resp" | tail -n1)
  echo "[$ikey]   -> ledger GET /authorisations/${auth_id} http=${code}"
}

# Returns "1.0" if auth-service currently reports its fraudService circuit breaker as CLOSED
# (via the resilience4j-micrometer gauge resilience4j_circuitbreaker_state{name="fraudService",
# state="closed"}), empty/"0.0" otherwise. Used to drive the breaker to a known-clean state
# before timing-sensitive demo phases (see wait_for_fraud_circuit_breaker_closed below).
fraud_circuit_breaker_closed_value() {
  curl -sS "${BASE_URL}/actuator/prometheus" 2>/dev/null \
    | grep 'resilience4j_circuitbreaker_state{' \
    | grep 'name="fraudService"' \
    | grep 'state="closed"' \
    | awk '{print $NF}'
}

# Actively drives auth-service's fraudService circuit breaker to CLOSED, instead of passively
# sleeping. This matters because resilience4j's HALF_OPEN state is call-count-gated, not
# time-gated: once HALF_OPEN, it just waits for permittedNumberOfCallsInHalfOpenState (3) real
# calls to complete before evaluating CLOSED vs OPEN again - no amount of sleeping resolves a
# pending partial trial batch on its own; only firing actual calls does. If a warm-up call flips it
# back OPEN, we wait poll_interval_seconds (> the 15s waitDurationInOpenState) before the next
# warm-up attempt, so that attempt is guaranteed to land after the wait duration has elapsed. Used
# by the fail-open demo phase (3c, to confirm the breaker is CLOSED before deliberately forcing
# fraud-service unavailable again) and by the recovery phase (7, after fraud-service's DELAY_2S
# timeout demo).
wait_for_fraud_circuit_breaker_closed() {
  local max_attempts="${1:-3}" poll_interval_seconds="${2:-16}"

  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    local closed_value
    closed_value=$(fraud_circuit_breaker_closed_value)
    if [ "$closed_value" = "1.0" ]; then
      echo "    -> fraudService circuit breaker reports CLOSED (checked via ${BASE_URL}/actuator/prometheus)"
      return 0
    fi

    echo "    -> fraudService circuit breaker not yet CLOSED (attempt ${attempt}/${max_attempts});"
    echo "       firing a warm-up auth to progress it, then waiting ${poll_interval_seconds}s"
    local acct_index=$(( GLOBAL_SEQ % NUM_ACCOUNTS ))
    local pair="${ACCOUNTS[$acct_index]}"
    local currency="${pair%%:*}"
    local account_id="${pair##*:}"
    local ikey
    next_ikey
    ikey="$IKEY"
    send_authorisation "$ikey" "$account_id" "$currency"
    sleep "$poll_interval_seconds"
  done

  echo "    -> WARNING: fraudService circuit breaker still not reporting CLOSED after ${max_attempts} attempts;"
  echo "       proceeding anyway - the recovery phase below may show a few more declines before it stabilises"
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
      query_ledger_authorisation "$ikey" "$first_auth_id"
    elif [ -n "$first_auth_id" ]; then
      echo "[$ikey]   -> not authorised (status=${first_status:-n/a}), skipping capture/reversal"
      query_ledger_authorisation "$ikey" "$first_auth_id"
    else
      echo "[$ikey]   -> not authorised (status=${first_status:-n/a}), skipping capture/reversal"
      echo "[$ikey]   -> ledger authorisation query skipped (no authorisationId returned by auth-service)"
    fi

    sleep "$interval"
  done

  echo "=== ${label}: ledger account-events reads for metric traffic ==="
  for pair in "${ACCOUNTS[@]}"; do
    local account_id="${pair##*:}"
    query_ledger_account_events "${label}" "$account_id"
  done

  echo
  echo ">>> ${label} complete: sent ${count} bulk auths (x2 for idempotency replay, plus x2 on their capture calls)."
}

# ---------------------------------------------------------------------------
# Phase 3a: trip the circuit breaker open, using ALWAYS_503 (also generates HTTP 5xx samples on
# fraud-service's own metrics while the breaker is closed).
# ---------------------------------------------------------------------------
run_circuit_breaker_open_phase() {
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
  echo ">>> Phase 3a complete: sent ${CB_OPEN_REQUEST_COUNT} circuit-breaker/5xx-phase auths, held ALWAYS_503 for ${CB_HOLD_SECONDS}s."
}

# ---------------------------------------------------------------------------
# Phase 3b: restore fraud-service to OFF and observe half-open -> closed recovery.
#
# This phase runs directly after 3a's forced ALWAYS_503 hold, so the fraudService circuit breaker
# should simply be OPEN (no stray partial trial batches to worry about yet). We don't actively poll
# /probe for a confirmed CLOSED state here (that's only needed by phase 3c, which deliberately
# forces fraud-service unavailable again and needs a clean starting point) - here we just fire a
# steady stream of real traffic and let resilience4j's own half-open -> closed transition play out
# naturally as the requests succeed.
# ---------------------------------------------------------------------------
run_circuit_breaker_recovery_phase() {
  echo
  echo "### Resetting fraud-service mode to OFF to observe circuit breaker recovery ###"
  echo "    (half-open -> closed as trial calls succeed; sending traffic and watching it recover,"
  echo "     no active polling/confirmation - just real requests)"
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

  echo
  echo ">>> Phase 3b complete: sent ${CB_RECOVERY_REQUEST_COUNT} circuit-breaker-recovery auths."
}

# ---------------------------------------------------------------------------
# Phase 3c: fail-open policy demo. Starts from the confirmed CLOSED breaker that phase 3b leaves
# behind, then forces fraud-service to ALWAYS_503 (unavailable) again for both requests below, so
# FraudOrchestrator's fail-open policy is the only thing that can distinguish their outcomes:
#   a. ineligible: untrusted account, fraud-service unavailable -> fail-open does NOT apply -> DECLINE
#   b. eligible: trusted account + amount within the fail-open max-amount (5.00) -> fail-open DOES
#      apply -> APPROVE
# A Return-key pause follows step b (outage still active) before fraud-service is reset to OFF, so
# the approved-while-down result can be shown/inspected first.
# ---------------------------------------------------------------------------
run_fail_open_demo_phase() {
  echo
  echo "### Confirming fraudService circuit breaker is CLOSED before the fail-open demo ###"
  wait_for_fraud_circuit_breaker_closed "$CB_RECOVERY_SETTLE_MAX_ATTEMPTS" "$CB_RECOVERY_SETTLE_SECONDS"

  echo
  echo "### Demonstrating fail-open policy (trusted account + tiny-amount transactions) ###"
  echo "    (setting fraud-service mode to ALWAYS_503 so fraud-service is unavailable for both"
  echo "     requests below - the fail-open policy is the only thing that can approve through this)"
  set_fraud_mode "ALWAYS_503"

  echo
  echo "--- Step 3c-a: ineligible - untrusted account (USD ${UNTRUSTED_ACCOUNT_ID}), fraud-service unavailable ---"
  echo "    (expect DECLINE: account is not in fraud.fail-open.trusted-account-ids, so the"
  echo "     fail-open policy does not apply)"
  local ikey
  next_ikey
  ikey="$IKEY"
  send_authorisation "$ikey" "$UNTRUSTED_ACCOUNT_ID" "USD"
  echo "[$ikey]   ^-- expect status=DECLINED (reason includes FRAUD_SERVICE_UNAVAILABLE, no fail-open)"

  wait_for_next_step

  echo
  echo "--- Step 3c-b: eligible - trusted account (GBP ${GBP_ACCOUNT_ID}), amount=2.00, fraud-service unavailable ---"
  echo "    (expect APPROVE: trusted account + amount <= fraud.fail-open.max-amount (5.00) ->"
  echo "     fail-open policy applies)"
  next_ikey
  ikey="$IKEY"
  send_authorisation "$ikey" "$GBP_ACCOUNT_ID" "GBP" "2.00"
  echo "[$ikey]   ^-- expect status=AUTHORISED (reason includes FRAUD_UNAVAILABLE_TRUSTED_TINY_AMOUNT)"

  wait_for_next_step

  echo
  echo "### Resetting fraud-service mode to OFF after fail-open demo ###"
  set_fraud_mode "OFF"

  echo
  echo ">>> Phase 3c complete: 2 fail-open-policy-demo auths sent (1 declined untrusted-account, 1 approved trusted-account)."
}

# ---------------------------------------------------------------------------
# Phase 5: DLT publish, per docs/development/runbook.md
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

  echo
  echo ">>> Phase 5 complete: sent ${DLT_MESSAGE_COUNT} DLT-bound Kafka records."
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

  echo
  echo ">>> Phase 4 complete: ${DEDUP_ROUNDS} rounds of dedup-event traffic sent (via generate-dedup-events.sh)."
}

# ---------------------------------------------------------------------------
# Phase 2: concurrency conflicts, delegates to the sibling generate-concurrency-conflicts.sh script.
# ---------------------------------------------------------------------------
run_concurrency_conflicts_phase() {
  echo
  echo "### Generating auth-service concurrency-conflict traffic (auth_concurrency_conflict_total) ###"
  echo "    (delegating to generate-concurrency-conflicts.sh: ROUNDS=${CONCURRENCY_ROUNDS}"
  echo "     PARALLEL_REQUESTS=${CONCURRENCY_PARALLEL_REQUESTS} INTERVAL_SECONDS=${CONCURRENCY_INTERVAL_SECONDS})"

  BASE_URL="$BASE_URL" \
  ROUNDS="$CONCURRENCY_ROUNDS" \
  PARALLEL_REQUESTS="$CONCURRENCY_PARALLEL_REQUESTS" \
  INTERVAL_SECONDS="$CONCURRENCY_INTERVAL_SECONDS" \
    "${SCRIPT_DIR}/generate-concurrency-conflicts.sh"

  echo
  echo ">>> Phase 2 complete: ${CONCURRENCY_ROUNDS} rounds of concurrency-conflict traffic sent (via generate-concurrency-conflicts.sh)."
}

# ---------------------------------------------------------------------------
# Phase 6: fraud-service latency/TimeLimiter demo.
#   6a. Baseline - one fresh authorisation with fraud-service healthy (fast, real fraud check).
#   6b. Set fraud-service mode to DELAY_2S, then send a *comparable* authorisation (same
#       account/currency/amount as 6a) with a different fresh idempotency key. auth-service's
#       fraudService TimeLimiter is 250ms, so the 2s delay times out and is treated as
#       UNAVAILABLE -> DECLINE (this account is not in fraud.fail-open.trusted-account-ids).
#       fraud-service is deliberately left in DELAY_2S mode afterwards - phase 7 resets it.
# ---------------------------------------------------------------------------
run_fraud_latency_baseline_step() {
  echo
  echo "### Phase 6a: baseline - fresh authorisation with fraud-service healthy (fast, real fraud check) ###"
  local pair="${ACCOUNTS[0]}"
  FRAUD_LATENCY_DEMO_CURRENCY="${pair%%:*}"
  FRAUD_LATENCY_DEMO_ACCOUNT_ID="${pair##*:}"

  local ikey
  next_ikey
  ikey="$IKEY"
  send_authorisation "$ikey" "$FRAUD_LATENCY_DEMO_ACCOUNT_ID" "$FRAUD_LATENCY_DEMO_CURRENCY"
  echo "[$ikey]   ^-- expect status=AUTHORISED via a fast, real fraud-service check"

  echo
  echo ">>> Phase 6a complete: 1 fraud-latency-demo baseline auth sent (fast, real fraud check)."
}

run_fraud_latency_timeout_step() {
  echo
  echo "### Phase 6b: setting fraud-service mode to DELAY_2S (every /fraud/check call now sleeps 2s) ###"
  echo "    (auth-service's fraudService TimeLimiter is 250ms, so this call times out and is"
  echo "     treated as UNAVAILABLE - expect DECLINE, since this account is not in"
  echo "     fraud.fail-open.trusted-account-ids)"
  set_fraud_mode "DELAY_2S"

  local currency="${FRAUD_LATENCY_DEMO_CURRENCY:-${ACCOUNTS[0]%%:*}}"
  local account_id="${FRAUD_LATENCY_DEMO_ACCOUNT_ID:-${ACCOUNTS[0]##*:}}"
  local ikey
  next_ikey
  ikey="$IKEY"
  send_authorisation "$ikey" "$account_id" "$currency"
  echo "[$ikey]   ^-- expect status=DECLINED (reason includes FRAUD_SERVICE_UNAVAILABLE, via DELAY_2S timeout)"
  echo
  echo "    (fraud-service intentionally left in DELAY_2S mode here - phase 7 resets it to OFF)"

  echo
  echo ">>> Phase 6b complete: 1 fraud-latency-demo DELAY_2S timeout auth sent (DECLINE expected); fraud-service left in DELAY_2S mode."
}

# ---------------------------------------------------------------------------
# Phase 7: fraud-service recovery. Resets fraud-service mode to OFF, actively drives the
# fraudService circuit breaker to a confirmed CLOSED state (reusing the same
# wait_for_fraud_circuit_breaker_closed helper as phase 3c), then sends a few fresh
# authorisations that each perform a real, successful fraud-service check end to end.
# ---------------------------------------------------------------------------
run_fraud_recovery_phase() {
  echo
  echo "### Resetting fraud-service mode to OFF after the latency demo ###"
  set_fraud_mode "OFF"

  echo "    (driving the fraudService circuit breaker to a confirmed CLOSED state before the"
  echo "     final verification requests below)"
  wait_for_fraud_circuit_breaker_closed "$CB_RECOVERY_SETTLE_MAX_ATTEMPTS" "$CB_RECOVERY_SETTLE_SECONDS"

  echo
  echo "### Sending ${FRAUD_RECOVERY_REQUEST_COUNT} fresh authorisations with fraud-service healthy ###"
  for ((n = 1; n <= FRAUD_RECOVERY_REQUEST_COUNT; n++)); do
    local acct_index=$(( GLOBAL_SEQ % NUM_ACCOUNTS ))
    local pair="${ACCOUNTS[$acct_index]}"
    local currency="${pair%%:*}"
    local account_id="${pair##*:}"
    local ikey
    next_ikey
    ikey="$IKEY"
    send_authorisation "$ikey" "$account_id" "$currency"
    echo "[$ikey]   ^-- expect status=AUTHORISED via a real, successful fraud-service check"
    sleep "$FRAUD_RECOVERY_INTERVAL_SECONDS"
  done

  echo
  echo ">>> Phase 7 complete: ${FRAUD_RECOVERY_REQUEST_COUNT} fraud-recovery auths sent (real, successful fraud checks); fraud-service failure-mode reset to OFF."
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
echo "== payment-platform test-data generator =="
echo "Run tag:        ${RUN_TAG} (embedded in every idempotency key this run)"
echo "Auth base URL:  ${BASE_URL}"
echo "Fraud base URL: ${FRAUD_BASE_URL}"
echo "Ledger base URL:${LEDGER_BASE_URL}"
echo "Accounts:       ${ACCOUNTS[*]} (round-robin, general traffic)"
echo "Trusted acct:   ${GBP_ACCOUNT_ID} (fail-open demo only)"
if [ "$START_STEP" -gt 1 ]; then
  echo "Start step:     ${START_STEP} (skipping steps 1-$((START_STEP - 1)) entirely)"
fi
echo "==========================================="

if [ "$START_STEP" -le 1 ]; then
  echo
  echo "### Phase 1: bulk authorisations (${BULK1_COUNT} every ${BULK1_INTERVAL_SECONDS}s) ###"
  run_bulk_phase "$BULK1_COUNT" "$BULK1_INTERVAL_SECONDS" "Phase 1 (bulk authorisations)"
  wait_for_next_step
fi

if [ "$START_STEP" -le 2 ]; then
  run_concurrency_conflicts_phase
  wait_for_next_step
fi

if [ "$START_STEP" -le 3 ]; then
  run_circuit_breaker_open_phase
  wait_for_next_step

  run_circuit_breaker_recovery_phase
  wait_for_next_step

  run_fail_open_demo_phase
  wait_for_next_step
fi

if [ "$START_STEP" -le 4 ]; then
  run_dedup_events_phase
  wait_for_next_step
fi

if [ "$START_STEP" -le 5 ]; then
  run_dlt_phase
  wait_for_next_step
fi

if [ "$START_STEP" -le 6 ]; then
  run_fraud_latency_baseline_step
  wait_for_next_step

  run_fraud_latency_timeout_step
  wait_for_next_step
fi

if [ "$START_STEP" -le 7 ]; then
  run_fraud_recovery_phase
fi

echo
echo "==========================================="
echo "All requested steps (starting from step ${START_STEP}) are complete - see the \">>>\" summary line"
echo "printed after each step above for exactly what that step did."

