package com.wallet.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Request rate, latency and error rate come free from Micrometer. These are the
// counters it cannot infer: a declined transfer and an idempotent replay are
// both HTTP successes, so nothing in the transport layer distinguishes them.
@Component
public class DomainMetrics {

    private final Counter walletsCreated;
    private final Counter transfersCreated;
    private final Counter transfersCompleted;
    private final Counter transfersDeclined;
    private final Counter transfersReplayed;
    private final Counter transfersConflicted;

    DomainMetrics(MeterRegistry registry) {
        this.walletsCreated = Counter.builder("wallet.wallets.created")
                .description("Wallets created, funded from the treasury")
                .register(registry);
        this.transfersCreated = Counter.builder("wallet.transfers.created")
                .description("Transfers that claimed an idempotency key and attempted a movement")
                .register(registry);
        this.transfersCompleted = Counter.builder("wallet.transfers.completed")
                .description("Transfers that debited and credited successfully")
                .register(registry);
        this.transfersDeclined = Counter.builder("wallet.transfers.declined")
                .description("Transfers declined without applying")
                .tag("reason", "insufficient_funds")
                .register(registry);
        this.transfersReplayed = Counter.builder("wallet.transfers.replayed")
                .description("Retries of an existing idempotency key that returned the original result")
                .register(registry);
        this.transfersConflicted = Counter.builder("wallet.transfers.conflicted")
                .description("Idempotency keys reused with a different request body")
                .register(registry);
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void transferCreated() {
        transfersCreated.increment();
    }

    public void transferCompleted() {
        transfersCompleted.increment();
    }

    public void transferDeclined() {
        transfersDeclined.increment();
    }

    public void transferReplayed() {
        transfersReplayed.increment();
    }

    public void transferConflicted() {
        transfersConflicted.increment();
    }
}