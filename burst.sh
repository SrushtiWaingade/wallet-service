#!/usr/bin/env bash
#
# Reproduces the concurrency invariants against a running wallet service.
#
#   ./burst.sh                          # against http://localhost:8080
#   ./burst.sh https://your-app.on.host  # against the deployed URL
#
# Everything is asserted through the public API, never the database, so this
# runs unchanged against a deployment you have no shell access to.

set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-50}"
OPENING_BALANCE_PAISE="${OPENING_BALANCE_PAISE:-100000}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failed=$((failed + 1)); }

require_service_up() {
    if ! curl -sf --max-time 90 "$BASE_URL/actuator/health" > /dev/null; then
        echo "Service is not answering at $BASE_URL"
        echo "If this is a free host it may be cold starting; try again in a minute."
        exit 1
    fi
}

# Each request writes to its own file. Appending concurrent curl output to one
# shared file interleaves the writes and produces phantom mismatches.
fire_concurrently() {
    local dir="$1" count="$2" token="$3" method="$4" path="$5"
    mkdir -p "$dir"
    export BASE_URL token dir method path
    seq 1 "$count" | xargs -P "$count" -I{} sh -c '
        curl -s -X "$method" \
             -H "Authorization: Bearer $token" \
             -o "$dir/$1.json" \
             -w "%{http_code}\n" \
             --max-time 120 \
             "$BASE_URL$path" > "$dir/$1.code" 2>/dev/null
    ' _ {}
}

gate1_race_free_get_or_create() {
    echo
    echo "Gate 1 - race-free get-or-create: $CONCURRENCY concurrent POST /wallets, one new user"
    local user="burst-$(date +%s)-$RANDOM"
    local dir="$WORK/gate1"

    fire_concurrently "$dir" "$CONCURRENCY" "$user" POST /wallets

    local codes
    codes=$(cat "$dir"/*.code | sort | uniq -c | tr '\n' ' ')
    if [ "$(cat "$dir"/*.code | sort -u | tr -d '[:space:]')" = "200" ]; then
        pass "all $CONCURRENCY responses were HTTP 200"
    else
        fail "expected every response to be 200, got: $codes"
    fi

    local distinct
    distinct=$(python3 - "$dir" <<'PY'
import json, pathlib, sys
ids = set()
for f in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    try:
        ids.add(json.loads(f.read_text())["id"])
    except Exception:
        ids.add("unparseable:" + f.name)
print(len(ids))
print("\n".join(sorted(ids)))
PY
)
    local count="${distinct%%$'\n'*}"
    local ids="${distinct#*$'\n'}"

    if [ "$count" = "1" ]; then
        pass "exactly one wallet id returned across $CONCURRENCY concurrent creates"
    else
        fail "expected 1 distinct wallet id, got $count:"
        echo "$ids" | sed 's/^/          /'
        return
    fi

    # Funded exactly once. N concurrent creates that each debited the treasury
    # would show a balance of N x OPENING_BALANCE_PAISE here.
    local balance
    balance=$(curl -s -H "Authorization: Bearer $user" "$BASE_URL/wallets/$ids" \
              | python3 -c 'import json,sys; print(json.load(sys.stdin)["balance_paise"])' 2>/dev/null)
    if [ "$balance" = "$OPENING_BALANCE_PAISE" ]; then
        pass "wallet funded exactly once (balance = $balance paise)"
    else
        fail "expected balance $OPENING_BALANCE_PAISE, got ${balance:-<none>}"
    fi
}

echo "Target: $BASE_URL"
require_service_up
gate1_race_free_get_or_create

echo
echo "-----------------------------------------"
printf 'passed: %d   failed: %d\n' "$passed" "$failed"
[ "$failed" -eq 0 ] || exit 1