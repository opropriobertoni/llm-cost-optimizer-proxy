package dev.estap.telemetry;

import dev.estap.circuitbreaker.FailOpenCircuitBreaker;

public record CompressionMetrics(
    String requestId,
    boolean compressionApplied,
    boolean failOpenTriggered,
    FailOpenCircuitBreaker.FailOpenReason failOpenReason,
    long originalSizeBytes,
    long compressedSizeBytes,
    double compressionRatio,
    long groqLatencyMs,
    int codeBlocksCount
) {}
