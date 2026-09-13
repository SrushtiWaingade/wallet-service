# Wallet & P2P Transfer

A wallet service with peer-to-peer transfers. Money is integer paise end to end —
`BIGINT` in the database, `long` in Java, no floats and no rupee decimals anywhere
in the money path.

| | |
|---|---|
| Live API | `https://TODO.onrender.com` |
| Logs | `TODO` |
| Metrics | `https://TODO.onrender.com/metrics` |
| Design write-up | [DESIGN.md](DESIGN.md) |

The service is kept awake by an uptime ping, so the first request should not
cold-start. If it does, give it 60 seconds and retry.

## The invariants

These are the properties the service exists to hold. Each is enforced in
Postgres rather than in application code, so they survive multiple instances,
not just multiple threads.

1. **Conservation.** The sum of all balances never changes across a transfer.
   Every movement writes two ledger rows summing to zero, so this is checkable
   rather than merely claimed: `SELECT sum(delta_paise) FROM ledger_entries` is
   always `0`.
2. **No overdraft.** A balance never goes negative. The debit is a conditional
   `UPDATE ... WHERE balance_paise >= :amount`; zero rows affected means declined,
   with nothing partially applied.
3. **Exactly-once transfer.** Re-sending an `idempotency_key` applies the transfer
   once and returns the original response. The same key with a different body is
   a `409`.
4. **Race-free get-or-create.** Concurrent `POST /wallets` for one user yield one
   wallet, via `INSERT ... ON CONFLICT (user_id) DO NOTHING`.

## Quick start

Requires Docker. Nothing else — no local Java or Postgres.

```bash
docker compose up --build
```

That starts Postgres and the app, waits for the database to be ready, runs the
Flyway migration and serves on `http://localhost:8080`.

```bash
docker compose down -v      # stop and wipe the database
docker compose logs -f app  # follow the JSON logs
```

## API

Auth is a bearer token that **is** the user id — `Bearer alice` means you are
`alice`. Deliberately trivial; see [DESIGN.md](DESIGN.md).

New wallets open with ₹1,000 (100000 paise), moved from a treasury wallet rather
than conjured, so conservation holds even as wallets are created.

### Create or fetch your wallet

```bash
curl -X POST http://localhost:8080/wallets \
  -H 'Authorization: Bearer alice'
```
```json
{"id":"ea771c18-...","user_id":"alice","balance_paise":100000}
```

Idempotent by nature: calling it again returns the same wallet.

### Read a balance

```bash
curl http://localhost:8080/wallets/{id} -H 'Authorization: Bearer alice'
```

### Transfer

```bash
curl -X POST http://localhost:8080/transfers \
  -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' \
  -d '{
        "from": "<alice-wallet-id>",
        "to": "<bob-wallet-id>",
        "amount_paise": 25000,
        "idempotency_key": "any-unique-string"
      }'
```
```json
{"id":"a334ecb6-...","from":"ea771c18-...","to":"5a10c72a-...",
 "amount_paise":25000,"status":"COMPLETED"}
```

The caller must own the `from` wallet.

### Transfer status

```bash
curl http://localhost:8080/transfers/{id} -H 'Authorization: Bearer alice'
```

### Responses

| Code | Meaning |
|---|---|
| `200` | applied, or an idempotent replay of the original result |
| `422` | declined — insufficient funds. Nothing moved; the key is spent, so a retry replays the decline |
| `409` | idempotency key reused with a different body |
| `403` | caller does not own the `from` wallet |
| `404` | wallet or transfer not found |
| `400` | non-positive amount, self-transfer, blank key, malformed body |
| `401` | missing or malformed bearer token |

## Reproducing the invariants

One command, asserting only through the public API, so it runs unchanged against
a deployment you have no shell access to.

```bash
./burst.sh                                   # against localhost:8080
./burst.sh https://TODO.onrender.com         # against the deployment
```

```
Gate 1 - race-free get-or-create: 50 concurrent POST /wallets, one new user
  PASS  all 50 responses were HTTP 200
  PASS  exactly one wallet id across 50 concurrent creates
  PASS  wallet funded exactly once (balance = 100000 paise)

Gate 2 - exactly-once transfer: 30 concurrent retries of one idempotency key
  PASS  all 30 retries returned HTTP 200
  PASS  all 30 responses were byte-identical
  PASS  sender debited exactly once (100000 -> 99000)

Gate 3 - conservation: 200 concurrent transfers over 4 wallets, both directions
  PASS  no 5xx responses under contention ( 180 200   20 422 )
  PASS  20 overdrawing transfers declined cleanly (422)
  PASS  no wallet went negative
  PASS  total conserved across 200 transfers (400000 paise, unchanged)
```

Exit code is non-zero if any assertion fails. Gate 3 includes reciprocal A→B and
B→A pairs, which is the case that deadlocks without a deterministic lock order.

Turn the dials for a slower host:

```bash
CONCURRENCY=20 STORM=20 TRANSFERS=50 ./burst.sh https://TODO.onrender.com
```

## Tests

```bash
./mvnw test
```

Requires Docker. Tests start their own throwaway Postgres via Testcontainers, so
they never touch a real database. Five of the six assert the invariants directly
under concurrency, including the A→B/B→A deadlock case.

## Observability

**Logs** are structured JSON (ECS), one line per event, with a `correlationId`
from the `X-Request-Id` header or generated per request. Domain events:
`wallet_created`, `transfer_created`, `transfer_completed`, `transfer_declined`,
`idempotent_replay`, `idempotency_conflict`.

Tracing a single declined transfer:

```bash
docker compose logs app | grep transfer_declined \
  | jq -r '[.correlationId, .event, .amount_paise] | @tsv'
```

**Metrics** at `/metrics` (and `/actuator/prometheus`).

```
wallet_transfers_total
wallet_transfers_completed_total
wallet_transfers_declined_total{reason="insufficient_funds"}
wallet_transfers_replayed_total
wallet_transfers_conflicted_total
wallet_wallets_total
```

Plus request rate, error rate by `outcome`, and a latency histogram. p99 is
computed from buckets rather than exported as a client-side quantile, because
quantiles cannot be aggregated across instances:

```promql
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{uri="/transfers"}[5m]))
```

## Deploying

The image takes all configuration from the environment, so the same artefact runs
on a laptop, in compose, and in production:

| Variable | Default | Notes |
|---|---|---|
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/wallet` | needs `?sslmode=require` on managed Postgres |
| `DB_USER` | `wallet` | |
| `DB_PASSWORD` | `wallet` | |
| `DB_POOL_SIZE` | `20` | lower it to fit a managed tier's connection cap |
| `PORT` | `8080` | injected by most hosts |
| `LOG_FORMAT` | `ecs` | set empty for human-readable local logs |

Deployed on Render's free tier from the `Dockerfile`, against Neon free Postgres,
both in Singapore so the app-to-database round trip stays around a millisecond.
Total cost ₹0; neither requires a card.

Credentials only ever arrive as environment variables. The defaults above are
throwaway local values, which is why this repo can be public.