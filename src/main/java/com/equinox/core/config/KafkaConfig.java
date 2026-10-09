package com.equinox.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentNotificationsTopic() {
        return TopicBuilder.name("payment-notifications")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentNotificationsDlqTopic() {
        return TopicBuilder.name("payment-notifications.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template, EquinoxMetricsService metricsService) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            if (metricsService != null) {
                metricsService.incrementDlqCount();
            }
            return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition());
        });
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
