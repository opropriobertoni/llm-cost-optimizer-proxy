package dev.estap.telemetry;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThatCode;

class MetricsLoggerTest {

    @Test
    void shouldLogRequestMetricsWithoutThrowingException() {
        MetricsLogger logger = new MetricsLogger();
        RequestMetrics metrics = new RequestMetrics(
            "test-id",
            Instant.now(),
            "POST",
            "/v1/chat",
            100,
            200,
            50,
            60,
            200
        );

        assertThatCode(() -> logger.log(metrics)).doesNotThrowAnyException();
    }

    @Test
    void shouldLogCompressionMetricsWithoutThrowingException() {
        MetricsLogger logger = new MetricsLogger();
        CompressionMetrics metrics = new CompressionMetrics(
            "test-id",
            true,
            false,
            dev.estap.circuitbreaker.FailOpenCircuitBreaker.FailOpenReason.NONE,
            1000,
            600,
            40.0,
            250,
            2
        );

        assertThatCode(() -> logger.log(metrics)).doesNotThrowAnyException();
    }
}
