package com.wallet.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("Transfer " + transferId + " does not exist");
    }
}