package dev.estap.telemetry;

import java.time.Instant;

public record RequestMetrics(
    String requestId,
    Instant timestamp,
    String method,
    String path,
    long requestBodySizeBytes,
    long responseBodySizeBytes,
    long upstreamLatencyMs,
    long totalLatencyMs,
    int statusCode
) {}
