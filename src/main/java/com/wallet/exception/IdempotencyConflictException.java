package com.wallet.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was already used with a different request body");
    }
}