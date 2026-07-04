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
}
