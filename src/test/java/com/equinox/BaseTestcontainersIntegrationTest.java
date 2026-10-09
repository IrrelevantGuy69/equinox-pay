package com.equinox;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base abstract class for Testcontainers integration testing.
 * Provides dynamically provisioned PostgreSQL, Kafka, and Redis containers
 * with properties injected into the Spring Boot test context.
 */
public abstract class BaseTestcontainersIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES_CONTAINER =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("equinox_test_db")
                    .withUsername("equinox_user")
                    .withPassword("equinox_pass");

    private static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    private static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        // Start containers if Docker environment is present
        try {
            POSTGRES_CONTAINER.start();
            KAFKA_CONTAINER.start();
            REDIS_CONTAINER.start();
        } catch (Throwable t) {
            // Log fallback if local Docker daemon is not active during unit runs
            System.err.println("[WARN] Testcontainers failed to start: " + t.getMessage());
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (POSTGRES_CONTAINER.isRunning()) {
            registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
            registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        }

        if (KAFKA_CONTAINER.isRunning()) {
            registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        }

        if (REDIS_CONTAINER.isRunning()) {
            registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
            registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
        }
    }
}
