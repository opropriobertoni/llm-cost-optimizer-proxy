package dev.estap.telemetry;

import dev.estap.circuitbreaker.FailOpenCircuitBreaker;

public record CompressionMetrics(
    String requestId,
    boolean compressionApplied,
    boolean failOpenTriggered,
    FailOpenCircuitBreaker.FailOpenReason failOpenReason,
    long originalTokens,
    long compressedTokens,
    double compressionRatio,
    long groqLatencyMs,
    int codeBlocksCount,
    long proseOriginalTokens,
    long proseCompressedTokens,
    double proseCompressionRatio
) {}
