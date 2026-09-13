package com.wallet.exception;

import java.util.UUID;

public class WalletNotOwnedException extends RuntimeException {
    public WalletNotOwnedException(UUID walletId, String callerUserId) {
        super("Wallet " + walletId + " is not owned by caller " + callerUserId);
    }
}
