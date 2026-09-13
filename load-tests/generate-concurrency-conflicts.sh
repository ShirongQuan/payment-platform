#!/usr/bin/env bash
#
# generate-concurrency-conflicts.sh
#
# Generates test data for the Grafana metric:
#   sum by (operation, type) (rate(auth_concurrency_conflict_total{application="auth-service"}[5m]) * 60)
#
# This script exercises the type=idempotency_race concurrency conflict: two (or more) requests
# share the same idempotency key and race on the unique DB constraint
# (account_id/authorisation_id, event_type, idempotency_key). Exactly one insert wins; the rest
# are caught as ConcurrentIdempotencyRaceException and safely resolved by re-reading and
# replaying the winner's response (HTTP 200, identical body for every racer).
#
# Note: authorise/capture/reverse all mutate the account's balance *before* the idempotency-key
# uniqueness check is reached (account balance is flushed first so the correct
# AUTHORISED/DECLINED status can be computed). This means genuinely concurrent *duplicate* (same
# idempotency key) requests that also change the account balance will almost always race on the
# account's optimistic-lock version first instead, surfacing as type=optimistic_lock rather than
# type=idempotency_race. The one reliable way to isolate a pure type=idempotency_race sample is a
# scenario where the account balance is *not* touched at all: authorise declined due to
# insufficient funds ("Account in DB will remain intact" - see
# AuthorisationTransactionalExecutorImpl) is exactly that case, so this script deliberately uses
# an amount that exceeds the account's available balance for the race.
#
# Per round, this script runs:
#   1. Idempotency-race round (authorise only): N concurrent requests, same idempotency key, an
#      amount deliberately larger than the account's available balance -> guaranteed DECLINED,
#      account untouched, so the only possible race is on the event-table unique constraint ->
#      type=idempotency_race. All N requests get HTTP 200 with an identical (DECLINED) body.
#
# Known gap: capture/reverse always mutate the account when transitioning a valid AUTHORISED
# authorisation, so a genuine capture/reverse duplicate-key race under true concurrency will (per
# the interaction above) surface as type=optimistic_lock, not type=idempotency_race - there is no
# reliable way to isolate a pure capture/reverse type=idempotency_race sample with this script.
#
# Requirements: bash, curl, jq, uuidgen (macOS built-in)
#
# By default this script creates and funds its own dedicated GBP account (via POST /accounts +
# POST /accounts/{id}/deposits) rather than reusing the shared demo GBP_ACCOUNT_ID used by
# generate-traffic.sh. This avoids a real problem observed when reusing the shared account: enough
# repeated DECLINED authorisations in a short window can trip fraud-service's repeated-decline
# account-lock recommendation, after which every subsequent request short-circuits as "account not
# active" instead of racing at all. Set ACCOUNT_ID to reuse a specific existing account instead
# (skips auto-provisioning).
#
# Usage:
#   ./generate-concurrency-conflicts.sh
#   ROUNDS=5 PARALLEL_REQUESTS=8 ./generate-concurrency-conflicts.sh
#   ACCOUNT_ID=11111111-1111-1111-1111-111111111111 ./generate-concurrency-conflicts.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:9000}"

ROUNDS="${ROUNDS:-5}"
PARALLEL_REQUESTS="${PARALLEL_REQUESTS:-6}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-2}"

ACCOUNT_ID="${ACCOUNT_ID:-}"
ACCOUNT_DEPOSIT_AMOUNT="${ACCOUNT_DEPOSIT_AMOUNT:-100000.00}"

for cmd in jq curl uuidgen; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: $cmd is required (brew install $cmd)" >&2
    exit 1
  fi
done

# Run-scoped tag so idempotency keys never collide with a previous run (accountId +
# idempotencyKey is the uniqueness scope for authorise; authorisationId + idempotencyKey for
# capture/reverse). Keep it short - idempotencyKey has a 20-char max.
RUN_TAG=$(date +%s)
RUN_TAG=${RUN_TAG: -6}

# Creates a fresh, well-funded, ACTIVE GBP account and sets ACCOUNT_ID to it. Used once up front
# if ACCOUNT_ID wasn't supplied, and again before *every* round in that same case (see AUTO_PROVISION
# below) - fraud-service's repeated-decline detection locks an account after just one round's worth
# of rapid declines (the idempotency-race round deliberately triggers several), so reusing the
# same auto-provisioned account across rounds would only ever produce data for round 1.
provision_fresh_account() {
  local create_resp account_id deposit_resp
  create_resp=$(curl -sS -X POST "${BASE_URL}/accounts" \
    -H "Content-Type: application/json" -d '{"currencyCode":"GBP"}')
  account_id=$(echo "$create_resp" | jq -r '.accountId // empty')
  if [ -z "$account_id" ]; then
    echo "ERROR: failed to create account, response: $create_resp" >&2
    exit 1
  fi
  deposit_resp=$(curl -sS -X POST "${BASE_URL}/accounts/${account_id}/deposits" \
    -H "Content-Type: application/json" \
    -d "$(jq -nc --arg amount "$ACCOUNT_DEPOSIT_AMOUNT" '{amount:($amount|tonumber), currencyCode:"GBP"}')")
  echo "  -> provisioned accountId=${account_id}, deposited ${ACCOUNT_DEPOSIT_AMOUNT} GBP"
  echo "     $(echo "$deposit_resp" | jq -c '{availableBalance, reservedBalance, status}' 2>/dev/null || echo "$deposit_resp")"
  ACCOUNT_ID="$account_id"
}

AUTO_PROVISION=false
if [ -z "$ACCOUNT_ID" ]; then
  AUTO_PROVISION=true
  echo "No ACCOUNT_ID given - will auto-provision a fresh, dedicated GBP account before every round"
  echo "(fraud-service locks an account after ~1 round's worth of rapid declines otherwise)."
fi

echo "== auth-service concurrency-conflict test-data generator =="
echo "Auth base URL:     ${BASE_URL}"
echo "Account:           ${ACCOUNT_ID:-<auto-provisioned per round>}"
echo "Rounds:            ${ROUNDS}"
echo "Parallel requests: ${PARALLEL_REQUESTS} (fired concurrently per race, per operation)"
echo "Run tag:           ${RUN_TAG}"
echo "============================================================"

# Fires $PARALLEL_REQUESTS concurrent POSTs to $1 (URL) with body $2, all in the background, then
# waits for all of them. Prints each response's HTTP status code as it completes.
fire_concurrent() {
  local url="$1" payload="$2" label="$3"
  local pids=()
  local tmp_dir
  tmp_dir=$(mktemp -d)

  for ((p = 1; p <= PARALLEL_REQUESTS; p++)); do
    (
      code=$(curl -sS -o "${tmp_dir}/body_${p}" -w '%{http_code}' -X POST "$url" \
        -H "Content-Type: application/json" -d "$payload")
      echo "$code" > "${tmp_dir}/code_${p}"
    ) &
    pids+=($!)
  done

  for pid in "${pids[@]}"; do
    wait "$pid"
  done

  local codes=""
  for ((p = 1; p <= PARALLEL_REQUESTS; p++)); do
    codes="${codes} $(cat "${tmp_dir}/code_${p}" 2>/dev/null || echo '???')"
  done
  echo "[$label] fired ${PARALLEL_REQUESTS} concurrent requests -> http codes:${codes}"

  # Return the first response body that looks like a successful (200) authorisation/capture/
  # reversal payload, so the caller can pick up the winner's id/status.
  WINNER_BODY=""
  for ((p = 1; p <= PARALLEL_REQUESTS; p++)); do
    if [ "$(cat "${tmp_dir}/code_${p}" 2>/dev/null)" = "200" ]; then
      WINNER_BODY=$(cat "${tmp_dir}/body_${p}")
      break
    fi
  done
  rm -rf "$tmp_dir"
}

for ((r = 1; r <= ROUNDS; r++)); do
  echo
  echo "### Round ${r}/${ROUNDS} ###"

  if [ "$AUTO_PROVISION" = true ]; then
    provision_fresh_account
  fi

  # --- Idempotency-race round: same idempotency key, amount far exceeding available balance, so
  #     authorise declines due to insufficient funds and the account is never mutated - the only
  #     possible race is on the event-table unique constraint -> type=idempotency_race.
  IDEM_IKEY="cc-idem-${RUN_TAG}-${r}"
  IDEM_PAYLOAD=$(jq -nc \
    --arg accountId "$ACCOUNT_ID" --arg idempotencyKey "$IDEM_IKEY" \
    --arg merchantReference "order-cc-idem-${r}" \
    '{accountId:$accountId, idempotencyKey:$idempotencyKey, amount:999999999.00, currencyCode:"GBP", merchantReference:$merchantReference}')
  fire_concurrent "${BASE_URL}/authorisations" "$IDEM_PAYLOAD" "authorise (idempotency_race) ikey=${IDEM_IKEY}"

  sleep "$INTERVAL_SECONDS"
done

echo
echo "============================================================"
echo "Done. Ran ${ROUNDS} rounds, each firing ${PARALLEL_REQUESTS} truly concurrent requests for an"
echo "insufficient-funds authorise race (type=idempotency_race, all responses 200, identical"
echo "DECLINED body)."
echo
echo "Verify with:"
echo "  curl -s ${BASE_URL}/actuator/prometheus | grep auth_concurrency_conflict_total"
echo
echo "Grafana/Prometheus query:"
echo "  sum by (operation, type) (rate(auth_concurrency_conflict_total{application=\"auth-service\"}[5m]) * 60)"




