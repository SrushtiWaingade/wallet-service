package com.wallet.controller;

import com.wallet.config.BearerTokenFilter;
import com.wallet.dto.WalletResponse;
import com.wallet.repository.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletRepository wallets;

    WalletController(WalletRepository wallets) {
        this.wallets = wallets;
    }

    // Always 200, never 201. Get-or-create has no meaningful "created" case to
    // report: concurrent callers for the same user must be indistinguishable
    // from each other, so every one of them gets the same status and body.
    @PostMapping
    public WalletResponse create(@RequestAttribute(BearerTokenFilter.CALLER_USER) String userId) {
        return WalletResponse.of(wallets.getOrCreate(userId));
    }

    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable UUID id) {
        return wallets.findById(id)
                .map(WalletResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
    }
}