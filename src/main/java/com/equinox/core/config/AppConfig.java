package com.equinox.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // This is CRITICAL. Without this, Jackson will crash when it tries
        // to convert Instant or LocalDateTime variables into JSON strings!
        mapper.registerModule(new JavaTimeModule());

        // This makes your dates format nicely (e.g., "2026-04-12T17:31:32Z")
        // instead of an unreadable array of numbers
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return mapper;
    }
    @Bean
    public org.springframework.web.client.RestTemplate restTemplate() {
        return new org.springframework.web.client.RestTemplate();
    }
}
