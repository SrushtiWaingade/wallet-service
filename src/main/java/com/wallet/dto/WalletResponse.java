package com.wallet.dto;

import com.wallet.entity.Wallet;

import java.util.UUID;

public record WalletResponse(UUID id, String userId, long balancePaise) {

    public static WalletResponse of(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.balancePaise());
    }
}