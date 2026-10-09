package com.equinox.core.controller;

import com.equinox.core.service.VaultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vault")
@RequiredArgsConstructor
public class VaultController {

    private final VaultService vaultService;

    @PostMapping("/transfer")
    public ResponseEntity<String> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam UUID from,
            @RequestParam UUID to,
            @RequestParam BigDecimal amount) {

        vaultService.processTransfer(idempotencyKey, from, to, amount);
        return ResponseEntity.ok("Transfer successfully settled in the Core Vault.");
    }
}
