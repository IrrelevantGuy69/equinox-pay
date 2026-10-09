package com.equinox.core.controller;

import com.equinox.core.document.PaymentDocument;
import com.equinox.core.repository.PaymentSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/queries")
@RequiredArgsConstructor
public class QueryController {

    private final PaymentSearchRepository searchRepository;

    @GetMapping("/history/{accountId}")
    public ResponseEntity<List<PaymentDocument>> getPaymentHistory(@PathVariable String accountId) {
        // This hits Elasticsearch, completely bypassing PostgreSQL
        List<PaymentDocument> history = searchRepository.findByFromAccountIdOrToAccountId(accountId, accountId);
        return ResponseEntity.ok(history);
    }
}
