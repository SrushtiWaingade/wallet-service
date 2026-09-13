# Wallet & P2P Transfer — design notes

Live: `https://wallet-service-xvzc.onrender.com`

## Data model

Three tables. Money is always `BIGINT` paise. No floats, no rupee decimals.

```
wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0, created_at)
transfers(id, idempotency_key UNIQUE, request_hash, from_wallet_id,
          to_wallet_id, amount_paise, status, created_at)
ledger_entries(id, transfer_id NULL, wallet_id, delta_paise, created_at)
```

Three constraints do the real work:

- `wallets.user_id UNIQUE` — two people creating the same wallet at once get one wallet.
- `transfers.idempotency_key UNIQUE` — the same key can only be used once.
- `CHECK (balance_paise >= 0)` — a balance can never go negative.

`ledger_entries` is a double-entry ledger. Every movement of money writes two
rows that add up to zero. So conservation is something you can check, not just
something I claim:

```sql
SELECT sum(delta_paise) FROM ledger_entries;   -- always 0
```

New wallets start with ₹1,000. That money is **moved out of a treasury wallet**
rather than created from nothing, so the total across all wallets stays the same
even when new wallets are created. The treasury is one row that every wallet
creation has to touch, so creations queue up on it. At real scale I would split
it into several treasury rows and pick one at random.

## How a transfer works

Everything below happens in one database transaction.

1. `INSERT INTO transfers ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING id`
2. If no row came back, this key was already used. Return the original result, or
   `409` if the request body is different this time.
3. Lock both wallets, **lower UUID first**.
4. `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :from AND balance_paise >= :amt`
5. If that updated zero rows, the balance was too low. Mark the transfer declined.
6. Otherwise credit the other wallet, write two ledger rows, mark it completed.

The debit is a single conditional `UPDATE`. The balance is never read into Java
and written back, so the code cannot lose an update. Whether the transfer
succeeds is decided by how many rows that one statement changed.

### Avoiding deadlock

If A→B and B→A run at the same time, they want the same two rows in opposite
orders. That deadlocks. So both locks are taken up front, always lowest UUID
first. Any consistent order works — it just has to be the same everywhere.

**This was not enough, and testing caught it.** The `INSERT` in step 1 has
foreign keys to both wallets. Postgres locks both referenced rows to check them,
and it does that in column order, not in my sorted order. With `SELECT ... FOR
UPDATE` in step 3, two opposite transfers each held a lock the other needed, and
deadlocked anyway. The first live run of the burst script returned **191 HTTP
500s out of 200 requests**.

The fix was `SELECT ... FOR NO KEY UPDATE`. That is the weaker lock an `UPDATE`
of a non-key column takes anyway, and it does not clash with the foreign key
locks. Deadlocks went from 124 to 0.

### What I rejected

- **`SERIALIZABLE`** — correct, but every conflict becomes a failure you have to
  catch and retry. That means writing retry code to solve a problem lock ordering
  already solves, and under A→B/B→A it becomes a retry storm.
- **`FOR UPDATE` plus arithmetic in Java** — also correct, but it moves the
  decision out of the database, where a later change can quietly bring back the
  lost-update bug.
- **Keeping used idempotency keys in memory** — works on one instance, breaks
  silently on two.

## Where idempotency lives

On the unique index on `transfers.idempotency_key`, claimed **in the same
transaction as the debit and credit**.

The key is claimed first. So when 50 retries arrive at once, 49 of them stop at
step 2 without ever touching a wallet. Claiming it after the money moved would
also be correct, because the transaction would roll back — but 49 transactions
would do real work and fight over locks first.

There is no moment where the key exists but the money has not moved, or the other
way round. That is the whole point of doing it in one transaction. Checking the
key in a separate transaction leaves a gap, and that gap double-debits under load.

Same key + same body returns the original response, read back from the stored
row, so it is identical. Same key + different body is a `409`. I detect that by
storing a SHA-256 of `from|to|amount`.

## Consistency or availability

I chose consistency. One Postgres primary, every money movement in one
transaction on one node. If the database fails over or the network splits, the
service **stops accepting writes** instead of writing to two places that later
disagree.

For money that is the only sensible choice. A transfer that was refused can be
retried safely with the same key. A transfer that was applied twice cannot be
undone safely.

What I gave up: writes are unavailable during a database outage, and writes do
not scale horizontally, because every transfer competes for the same rows.

## Proof

`./burst.sh <url>` checks all three invariants using only the public API, so it
runs against the deployed service without any special access.

Against the live URL: **500 transfers at once across 4 wallets, including A→B and
B→A pairs. 450 applied, 50 declined, no server errors, total still 400000 paise,
no negative balances, 34 seconds.**

There are also six tests that run against a throwaway Postgres started by
Testcontainers. If I put the old `FOR UPDATE` back, the A→B/B→A test fails with
`deadlock detected` — so the tests catch the real bug, they do not just pass.

## Logs and metrics

Logs are JSON, one line per event, each carrying a `correlationId` taken from the
`X-Request-Id` header or generated. I strip anything unusual out of that header
first, because a newline in it would let a caller fake log lines — and these logs
are public.

Events logged: `wallet_created`, `transfer_created`, `transfer_completed`,
`transfer_declined`, `idempotent_replay`, `idempotency_conflict`.

`/metrics` has request rate, error rate, a latency histogram, and counters for
transfers completed, declined and replayed. p99 comes from the histogram using
`histogram_quantile()` rather than a pre-computed number, because you cannot
average two p99s from two instances.

Latency is mostly the free tier: 0.1 CPU and 512 MB. The first burst after a
deploy takes about 79 seconds, dropping to about 17 once the JVM warms up.

## What I directed and what the AI decided

I used an AI assistant to write most of the code. The split:

**I decided.** The stack — Java 21, Spring Boot 4, Postgres, and plain JDBC
instead of JPA so the SQL stays visible. The package layout. That new wallets
must start with usable money. That both `/metrics` and the Prometheus endpoint
should be exposed. How small each commit should be. I read every change before it
went in, and I ran the burst script against the deployment myself.

**The AI decided and I agreed.** The exact SQL for get-or-create, the conditional
`UPDATE` for the debit, the lock ordering, the `FOR NO KEY UPDATE` fix, the
treasury design, hashing the request body to catch key reuse, the structure of
the burst script, and the Testcontainers setup.

One honest note: the first version of the lock ordering was wrong, and I accepted
it. The burst script found it. That is exactly why the
script exists, and why I run it against the deployed service rather than only
locally.

## Cost

₹0. Render free tier (512 MB, 0.1 CPU) and Neon free Postgres (0.5 GB, 100
compute-hours a month). Both in Singapore, so the app and database are about a
millisecond apart. Neither needed a card. An uptime ping keeps the service awake,
also free.
