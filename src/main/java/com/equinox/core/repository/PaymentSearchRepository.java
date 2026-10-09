package com.equinox.core.repository;

import com.equinox.core.document.PaymentDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentSearchRepository extends ElasticsearchRepository<PaymentDocument, String> {
    // Elasticsearch will automatically generate the query to find all documents matching this account
    List<PaymentDocument> findByFromAccountIdOrToAccountId(String fromAccountId, String toAccountId);
}
