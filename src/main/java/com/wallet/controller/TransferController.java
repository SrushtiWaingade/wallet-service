package com.wallet.controller;

import com.wallet.config.BearerTokenFilter;
import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.entity.TransferResult;
import com.wallet.entity.TransferStatus;
import com.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transfers;

    TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    // The status code is derived from the stored transfer, never from whether
    // this particular call did the work. That is what makes a retry byte-for-byte
    // identical to the original response.
    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @RequestAttribute(BearerTokenFilter.CALLER_USER) String callerUserId,
            @Valid @RequestBody TransferRequest request) {

        TransferResult result = transfers.transfer(callerUserId, request);
        HttpStatus status = result.transfer().status() == TransferStatus.DECLINED_INSUFFICIENT_FUNDS
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(TransferResponse.of(result.transfer()));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.of(transfers.findById(id));
    }
}
