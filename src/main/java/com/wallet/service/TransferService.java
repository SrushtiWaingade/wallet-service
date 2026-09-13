package com.wallet.service;

import com.wallet.dto.TransferRequest;
import com.wallet.entity.TransferResult;
import com.wallet.entity.Wallet;
import com.wallet.exception.InvalidTransferException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.exception.WalletNotOwnedException;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class TransferService {

    private final TransferRepository transfers;
    private final WalletRepository wallets;

    TransferService(TransferRepository transfers, WalletRepository wallets) {
        this.transfers = transfers;
        this.wallets = wallets;
    }

    public TransferResult transfer(String callerUserId, TransferRequest request) {
        if (request.from().equals(request.to())) {
            throw new InvalidTransferException("from and to must be different wallets");
        }

        Wallet source = wallets.findById(request.from())
                .orElseThrow(() -> new WalletNotFoundException(request.from()));
        if (!source.userId().equals(callerUserId)) {
            throw new WalletNotOwnedException(request.from(), callerUserId);
        }

        return transfers.execute(request.idempotencyKey(),
                requestHash(request),
                request.from(),
                request.to(),
                request.amountPaise());
    }

    // Covers everything that defines the movement, but not the key itself. Two
    // requests carrying the same key are the same request only if these three
    // fields match; if they differ the caller has reused a key and gets a 409.
    private String requestHash(TransferRequest request) {
        String canonical = request.from() + "|" + request.to() + "|" + request.amountPaise();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
