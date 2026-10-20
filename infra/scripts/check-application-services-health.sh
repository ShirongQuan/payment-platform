#!/usr/bin/env bash
set -euo pipefail

AUTH_URL="${AUTH_URL:-http://localhost:9000}"
FRAUD_URL="${FRAUD_URL:-http://localhost:9010}"
LEDGER_URL="${LEDGER_URL:-http://localhost:9020}"

check_health() {
    local service="$1"
    local url="$2"
    local response

    printf '%-16s ' "$service"

    if ! response=$(curl --silent --show-error \
        --fail --max-time 5 \
        "$url/actuator/health"); then
        echo "UNREACHABLE or HTTP ERROR"
        return 1
    fi

    if [[ "$response" == *'"status":"UP"'* ]]; then
        echo "UP"
    else
        echo "NOT UP: $response"
        return 1
    fi
}

echo "Payment Platform — Service Health"
echo "---------------------------------"

failed=0

check_health "auth-service" "$AUTH_URL" || failed=1
check_health "fraud-service" "$FRAUD_URL" || failed=1
check_health "ledger-service" "$LEDGER_URL" || failed=1

echo "---------------------------------"

#if [[ "$failed" -eq 0 ]]; then
#    echo "All application services report UP."
#else
#    echo "One or more application services are not healthy."
#    exit 1
#fi