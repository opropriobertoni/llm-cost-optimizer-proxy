package dev.estap.circuitbreaker;

import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FailOpenCircuitBreakerTest {

    private final FailOpenCircuitBreaker circuitBreaker = new FailOpenCircuitBreaker();

    @Test
    void shouldReturnResultOnSuccessfulAction() {
        FailOpenCircuitBreaker.ExecutionResult<String> result = circuitBreaker.execute(
            () -> "success-value",
            "fallback-value"
        );

        assertThat(result.value()).isEqualTo("success-value");
        assertThat(result.failOpenTriggered()).isFalse();
        assertThat(result.reason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.NONE);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldFallbackOnHttpTimeoutException() {
        FailOpenCircuitBreaker.ExecutionResult<String> result = circuitBreaker.execute(
            () -> {
                throw new HttpTimeoutException("Request timed out");
            },
            "fallback-value"
        );

        assertThat(result.value()).isEqualTo("fallback-value");
        assertThat(result.failOpenTriggered()).isTrue();
        assertThat(result.reason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.TIMEOUT);
    }

    @Test
    void shouldFallbackOnTimeoutException() {
        FailOpenCircuitBreaker.ExecutionResult<String> result = circuitBreaker.execute(
            () -> {
                throw new TimeoutException("Timeout");
            },
            "fallback-value"
        );

        assertThat(result.value()).isEqualTo("fallback-value");
        assertThat(result.failOpenTriggered()).isTrue();
        assertThat(result.reason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.TIMEOUT);
    }

    @Test
    void shouldFallbackOnGenericException() {
        FailOpenCircuitBreaker.ExecutionResult<String> result = circuitBreaker.execute(
            () -> {
                throw new RuntimeException("Generic error");
            },
            "fallback-value"
        );

        assertThat(result.value()).isEqualTo("fallback-value");
        assertThat(result.failOpenTriggered()).isTrue();
        assertThat(result.reason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.ERROR);
    }
}
