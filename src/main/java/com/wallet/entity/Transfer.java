package com.wallet.entity;

import java.util.UUID;

public record Transfer(UUID id,
                       String idempotencyKey,
                       String requestHash,
                       UUID fromWalletId,
                       UUID toWalletId,
                       long amountPaise,
                       TransferStatus status) {
}