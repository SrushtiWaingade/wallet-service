# Wallet & P2P Transfer

A wallet service with peer-to-peer transfers. Money is always integer paise —
`BIGINT` in the database, `long` in Java. No floats and no rupee decimals
anywhere money is handled.

| | |
|---|---|
| Live API | `https://wallet-service-xvzc.onrender.com` |
| Logs | [Screen recording of logs streaming during a burst](https://www.loom.com/share/a28b8fb05ec0493b9ac7c6b3be466b21) |
| Metrics | `https://wallet-service-xvzc.onrender.com/metrics` |
| Design notes | [DESIGN.md](DESIGN.md) |

An uptime ping keeps the service awake, so the first request should be quick. If
it isn't, wait 60 seconds and try again.

## What the service guarantees

All four are enforced by the database, not by Java code. That means they still
hold if the service runs on more than one machine.

1. **Money is never created or destroyed.** Every movement writes two ledger rows
   that add up to zero, so you can check it: `SELECT sum(delta_paise) FROM
   ledger_entries` is always `0`.
2. **A balance never goes negative.** The debit is one conditional `UPDATE`. If it
   changes zero rows, the transfer is declined and nothing moved.
3. **A transfer happens once.** Sending the same `idempotency_key` again returns
   the original response. The same key with a different body is a `409`.
4. **One user, one wallet.** Simultaneous `POST /wallets` for the same user give
   back one wallet, using `INSERT ... ON CONFLICT (user_id) DO NOTHING`.

## Running it

You need Docker. Nothing else — no Java, no Postgres.

```bash
docker compose up --build
```

This starts Postgres, waits for it to be ready, runs the migration, and serves on
`http://localhost:8080`.

```bash
docker compose down -v      # stop and delete the database
docker compose logs -f app  # watch the logs
```

## API

The bearer token **is** the user id. `Bearer alice` means you are `alice`. This is
deliberately simple — see [DESIGN.md](DESIGN.md).

New wallets start with ₹1,000 (100000 paise). That money is moved out of a
treasury wallet, not created, so the total never changes.

### Create or fetch your wallet

```bash
curl -X POST http://localhost:8080/wallets \
  -H 'Authorization: Bearer alice'
```
```json
{"id":"ea771c18-...","user_id":"alice","balance_paise":100000}
```

Call it again and you get the same wallet back.

### Check a balance

```bash
curl http://localhost:8080/wallets/{id} -H 'Authorization: Bearer alice'
```

### Send money

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

You must own the `from` wallet.

### Check a transfer

```bash
curl http://localhost:8080/transfers/{id} -H 'Authorization: Bearer alice'
```

### What the status codes mean

| Code | Meaning |
|---|---|
| `200` | Done — or a repeat of a transfer already done, returning the original result |
| `422` | Declined, not enough money. Nothing moved. Retrying with the same key returns the same decline |
| `409` | This idempotency key was already used with a different body |
| `403` | You don't own the `from` wallet |
| `404` | Wallet or transfer not found |
| `400` | Amount is zero or negative, sending to yourself, blank key, or bad JSON |
| `401` | Missing or malformed bearer token |

## Checking the guarantees yourself

One command. It only uses the public API, so it works against the deployed
service too.

```bash
./burst.sh                                            # localhost
./burst.sh https://wallet-service-xvzc.onrender.com   # the live service
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

It exits non-zero if anything fails. Gate 3 deliberately sends A→B and B→A at the
same time, which is the case that deadlocks if wallets aren't locked in a fixed
order.

You can turn it up or down:

```bash
CONCURRENCY=100 STORM=50 TRANSFERS=500 ./burst.sh https://wallet-service-xvzc.onrender.com
```

500 transfers against the live service passes in about 34 seconds.

## Tests

```bash
./mvnw test
```

You need Docker. The tests start their own temporary Postgres using
Testcontainers, so they never touch a real database. Five of the six check the
guarantees under real concurrency, including the A→B/B→A deadlock case.

## Logs and metrics

Logs are JSON, one line per event. Every line has a `correlationId`, taken from
the `X-Request-Id` header if you send one, otherwise generated.

Events: `wallet_created`, `transfer_created`, `transfer_completed`,
`transfer_declined`, `idempotent_replay`, `idempotency_conflict`.

Following one declined transfer:

```bash
docker compose logs app | grep transfer_declined \
  | jq -r '[.correlationId, .event, .amount_paise] | @tsv'
```

Metrics are at `/metrics` (and `/actuator/prometheus`):

```
wallet_transfers_total
wallet_transfers_completed_total
wallet_transfers_declined_total{reason="insufficient_funds"}
wallet_transfers_replayed_total
wallet_transfers_conflicted_total
wallet_wallets_total
```

Plus request rate, error rate, and a latency histogram. To get p99:

```promql
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{uri="/transfers"}[5m]))
```

## Deploying

All configuration comes from environment variables, so the same image runs on a
laptop, in Docker Compose, and in production without changes.

| Variable | Default | Notes |
|---|---|---|
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/wallet` | add `?sslmode=require` for hosted Postgres |
| `DB_USER` | `wallet` | |
| `DB_PASSWORD` | `wallet` | |
| `DB_POOL_SIZE` | `20` | lower it if your database limits connections |
| `PORT` | `8080` | most hosts set this for you |
| `LOG_FORMAT` | `ecs` | set it to empty for readable logs while developing |

Running on Render's free tier, built from the `Dockerfile`, with Neon free
Postgres. Both are in Singapore so the app and database are about a millisecond
apart. Total cost ₹0, and neither needed a card.

Passwords only ever arrive as environment variables. The defaults above are
throwaway local values, which is why this repository can be public.