package com.wallet.dto;

import com.wallet.entity.Transfer;
import com.wallet.entity.TransferStatus;

import java.util.UUID;

public record TransferResponse(UUID id,
                               UUID from,
                               UUID to,
                               long amountPaise,
                               TransferStatus status) {

    public static TransferResponse of(Transfer transfer) {
        return new TransferResponse(transfer.id(),
                transfer.fromWalletId(),
                transfer.toWalletId(),
                transfer.amountPaise(),
                transfer.status());
    }
}