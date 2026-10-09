package com.equinox.core.service;

import com.equinox.core.document.PaymentDocument;
import com.equinox.core.dto.PaymentNotificationEvent;
import com.equinox.core.repository.PaymentSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PaymentIndexerService {

    private final PaymentSearchRepository searchRepository;

    @KafkaListener(topics = "payment-notifications", groupId = "equinox-search-group")
    public void indexPaymentToElasticsearch(PaymentNotificationEvent event) {
        PaymentDocument document = PaymentDocument.builder()
                .id(event.getPaymentId().toString())
                .fromAccountId(event.getFromAccountId().toString())
                .toAccountId(event.getToAccountId().toString())
                .amount(event.getAmount())
                .status(event.getStatus())
                .timestamp(Instant.now().toEpochMilli())
                .build();

        searchRepository.save(document);
        System.out.println(">>> CQRS SYNC: Indexed Payment " + event.getPaymentId() + " into Elasticsearch");
    }
}
