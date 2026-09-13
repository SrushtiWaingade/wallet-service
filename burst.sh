#!/usr/bin/env bash
#
# Reproduces the concurrency invariants against a running wallet service.
#
#   ./burst.sh                           # against http://localhost:8080
#   ./burst.sh https://your-app.host     # against the deployed URL
#
# Everything is asserted through the public API, never the database, so this
# runs unchanged against a deployment you have no shell access to.

set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-50}"        # gate 1: simultaneous wallet creates
STORM="${STORM:-30}"                    # gate 2: retries of one idempotency key
TRANSFERS="${TRANSFERS:-200}"           # gate 3: contended transfers
OPENING_BALANCE_PAISE="${OPENING_BALANCE_PAISE:-100000}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export WORK BASE_URL

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

create_wallet() {
    curl -s -X POST -H "Authorization: Bearer $1" "$BASE_URL/wallets" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
}

wallet_balance() {
    curl -s -H "Authorization: Bearer $1" "$BASE_URL/wallets/$2" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["balance_paise"])'
}

# Each request writes to its own file. Appending concurrent curl output to one
# shared file interleaves the writes and produces phantom mismatches.
fire_indexed_requests() {
    local dir="$1" count="$2"
    export dir
    seq 1 "$count" | xargs -P "$count" -I{} sh -c '
        curl -s -X POST \
             -H "Authorization: Bearer $(cat "$dir/tok-$1.txt")" \
             -H "Content-Type: application/json" \
             --data-binary "@$dir/req-$1.json" \
             -o "$dir/res-$1.json" \
             -w "%{http_code}\n" \
             --max-time 120 \
             "$BASE_URL/transfers" > "$dir/code-$1.txt" 2>/dev/null
    ' _ {}
}

gate1_race_free_get_or_create() {
    echo
    echo "Gate 1 - race-free get-or-create: $CONCURRENCY concurrent POST /wallets, one new user"
    local user="burst-$(date +%s)-$RANDOM"
    local dir="$WORK/gate1"
    mkdir -p "$dir"
    export user dir

    seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I{} sh -c '
        curl -s -X POST -H "Authorization: Bearer $user" \
             -o "$dir/$1.json" -w "%{http_code}\n" --max-time 120 \
             "$BASE_URL/wallets" > "$dir/$1.code" 2>/dev/null
    ' _ {}

    if [ "$(cat "$dir"/*.code | sort -u | tr -d '[:space:]')" = "200" ]; then
        pass "all $CONCURRENCY responses were HTTP 200"
    else
        fail "expected every response to be 200, got: $(cat "$dir"/*.code | sort | uniq -c | tr '\n' ' ')"
    fi

    local ids count
    ids=$(python3 -c '
import json, pathlib, sys
out = set()
for f in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    try: out.add(json.loads(f.read_text())["id"])
    except Exception: out.add("unparseable:" + f.name)
print("\n".join(sorted(out)))' "$dir")
    count=$(printf '%s\n' "$ids" | grep -c .)

    if [ "$count" = "1" ]; then
        pass "exactly one wallet id across $CONCURRENCY concurrent creates"
    else
        fail "expected 1 distinct wallet id, got $count"
        printf '%s\n' "$ids" | sed 's/^/          /'
        return
    fi

    local balance
    balance=$(wallet_balance "$user" "$ids")
    if [ "$balance" = "$OPENING_BALANCE_PAISE" ]; then
        pass "wallet funded exactly once (balance = $balance paise)"
    else
        fail "expected balance $OPENING_BALANCE_PAISE, got ${balance:-<none>}"
    fi
}

gate2_exactly_once_transfer() {
    echo
    echo "Gate 2 - exactly-once transfer: $STORM concurrent retries of one idempotency key"
    local run="g2-$(date +%s)-$RANDOM"
    local dir="$WORK/gate2"
    mkdir -p "$dir"

    local sender="$run-sender" receiver="$run-receiver"
    local from to amount=1000
    from=$(create_wallet "$sender")
    to=$(create_wallet "$receiver")

    local i=1
    while [ "$i" -le "$STORM" ]; do
        printf '{"from":"%s","to":"%s","amount_paise":%d,"idempotency_key":"%s"}' \
            "$from" "$to" "$amount" "$run-key" > "$dir/req-$i.json"
        printf '%s' "$sender" > "$dir/tok-$i.txt"
        i=$((i + 1))
    done

    fire_indexed_requests "$dir" "$STORM"

    if [ "$(cat "$dir"/code-*.txt | sort -u | tr -d '[:space:]')" = "200" ]; then
        pass "all $STORM retries returned HTTP 200"
    else
        fail "expected every retry to be 200, got: $(cat "$dir"/code-*.txt | sort | uniq -c | tr '\n' ' ')"
    fi

    local distinct
    distinct=$(cat "$dir"/res-*.json | python3 -c '
import sys, json
seen = set()
for chunk in sys.stdin.read().replace("}{", "}\n{").splitlines():
    if chunk.strip():
        try: seen.add(json.dumps(json.loads(chunk), sort_keys=True))
        except Exception: seen.add("unparseable")
print(len(seen))')
    if [ "$distinct" = "1" ]; then
        pass "all $STORM responses were byte-identical"
    else
        fail "expected 1 distinct response body, got $distinct"
    fi

    local after expected=$((OPENING_BALANCE_PAISE - amount))
    after=$(wallet_balance "$sender" "$from")
    if [ "$after" = "$expected" ]; then
        pass "sender debited exactly once ($OPENING_BALANCE_PAISE -> $after)"
    else
        fail "expected sender balance $expected, got ${after:-<none>} (a double debit would be $((expected - amount)))"
    fi
}

gate3_conservation_under_contention() {
    echo
    echo "Gate 3 - conservation: $TRANSFERS concurrent transfers over 4 wallets, both directions"
    local run="g3-$(date +%s)-$RANDOM"
    local dir="$WORK/gate3"
    mkdir -p "$dir"

    local tokens=() ids=()
    local n
    for n in 1 2 3 4; do
        tokens+=("$run-w$n")
        ids+=("$(create_wallet "$run-w$n")")
    done

    local before=0 bal
    for n in 0 1 2 3; do
        bal=$(wallet_balance "${tokens[$n]}" "${ids[$n]}")
        before=$((before + bal))
    done

    # Reciprocal pairs land on the same two rows in opposite directions, which
    # is the case that deadlocks without a deterministic lock order. Oversized
    # amounts force declines so the overdraft path is exercised under load too.
    python3 - "$dir" "$TRANSFERS" "$run" "${ids[0]}" "${ids[1]}" "${ids[2]}" "${ids[3]}" \
                     "${tokens[0]}" "${tokens[1]}" "${tokens[2]}" "${tokens[3]}" <<'PY'
import json, random, sys, pathlib
dir_, total, run = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
ids, tokens = sys.argv[4:8], sys.argv[8:12]
random.seed(run)
for i in range(1, total + 1):
    if i % 2 == 1:
        a, b = random.sample(range(4), 2)
        dir_.joinpath(f"pair-{i}.txt").write_text(f"{a} {b}")
    else:
        prev = dir_.joinpath(f"pair-{i-1}.txt").read_text().split()
        b, a = int(prev[0]), int(prev[1])          # same two wallets, reversed
    amount = 999_999_999 if i % 10 == 0 else random.randint(100, 3000)
    dir_.joinpath(f"req-{i}.json").write_text(json.dumps({
        "from": ids[a], "to": ids[b],
        "amount_paise": amount,
        "idempotency_key": f"{run}-{i}",
    }))
    dir_.joinpath(f"tok-{i}.txt").write_text(tokens[a])
PY

    fire_indexed_requests "$dir" "$TRANSFERS"

    local codes server_errors
    codes=$(cat "$dir"/code-*.txt | sort | uniq -c | tr '\n' ' ')
    server_errors=$(cat "$dir"/code-*.txt | grep -c '^5' || true)
    if [ "$server_errors" = "0" ]; then
        pass "no 5xx responses under contention ($codes)"
    else
        fail "$server_errors server errors under contention ($codes)"
    fi

    # grep -c, not grep -q: with pipefail, grep -q exits on the first match and
    # the SIGPIPE it sends upstream fails the whole pipeline.
    local declines
    declines=$(cat "$dir"/code-*.txt | grep -c '^422' || true)
    if [ "$declines" -gt 0 ]; then
        pass "$declines overdrawing transfers declined cleanly (422)"
    else
        fail "expected some 422 declines; the overdraft path was never exercised"
    fi

    local after=0 negatives=0
    for n in 0 1 2 3; do
        bal=$(wallet_balance "${tokens[$n]}" "${ids[$n]}")
        after=$((after + bal))
        [ "$bal" -lt 0 ] && negatives=$((negatives + 1))
    done

    if [ "$negatives" = "0" ]; then
        pass "no wallet went negative"
    else
        fail "$negatives wallets have a negative balance"
    fi

    if [ "$after" = "$before" ]; then
        pass "total conserved across $TRANSFERS transfers ($before paise, unchanged)"
    else
        fail "conservation broken: $before paise before, $after after (delta $((after - before)))"
    fi
}

echo "Target: $BASE_URL"
require_service_up
gate1_race_free_get_or_create
gate2_exactly_once_transfer
gate3_conservation_under_contention

echo
echo "-----------------------------------------"
printf 'passed: %d   failed: %d\n' "$passed" "$failed"
[ "$failed" -eq 0 ] || exit 1