package com.wallet.entity;

// replayed distinguishes a fresh application from an idempotent retry. The
// client cannot tell the difference by design - both return the same body -
// but the metrics and logs need to.
public record TransferResult(Transfer transfer, boolean replayed) {
}