package dev.estap.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FailOpenCircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(FailOpenCircuitBreaker.class);

    public enum FailOpenReason {
        NONE,
        TIMEOUT,
        SANITY_CHECK_FAILED,
        ERROR
    }

    public record ExecutionResult<T>(
        T value,
        boolean failOpenTriggered,
        FailOpenReason reason,
        long latencyMs
    ) {}

    @FunctionalInterface
    public interface SupplierWithException<T> {
        T get() throws Exception;
    }

    public <T> ExecutionResult<T> execute(SupplierWithException<T> action, T fallbackValue) {
        long startTime = System.currentTimeMillis();
        try {
            T result = action.get();
            long elapsed = System.currentTimeMillis() - startTime;
            return new ExecutionResult<>(result, false, FailOpenReason.NONE, elapsed);
        } catch (java.net.http.HttpTimeoutException | java.util.concurrent.TimeoutException exception) {
            long elapsed = System.currentTimeMillis() - startTime;
            LOG.warn("Circuit breaker activated: request timed out (fail-open)", exception);
            return new ExecutionResult<>(fallbackValue, true, FailOpenReason.TIMEOUT, elapsed);
        } catch (Exception exception) {
            long elapsed = System.currentTimeMillis() - startTime;
            LOG.error("Circuit breaker activated: unexpected error during execution (fail-open)", exception);
            return new ExecutionResult<>(fallbackValue, true, FailOpenReason.ERROR, elapsed);
        }
    }
}
