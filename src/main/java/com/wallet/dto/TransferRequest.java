package com.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferRequest(@NotNull UUID from,
                              @NotNull UUID to,
                              @Positive long amountPaise,
                              @NotBlank String idempotencyKey) {
}