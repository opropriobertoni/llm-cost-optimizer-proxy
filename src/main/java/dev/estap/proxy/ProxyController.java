package dev.estap.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.estap.config.EnvironmentConfig;
import dev.estap.telemetry.MetricsLogger;
import dev.estap.telemetry.RequestMetrics;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyController.class);
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(120);

    private static final Set<String> HEADERS_TO_SKIP = Set.of(
        "host", "content-length", "transfer-encoding", "connection"
    );

    private final EnvironmentConfig config;
    private final StreamingRelay relay;
    private final MetricsLogger metricsLogger;
    private final ObjectMapper objectMapper;

    public ProxyController(
            EnvironmentConfig config,
            StreamingRelay relay,
            MetricsLogger metricsLogger) {
        this.config = config;
        this.relay = relay;
        this.metricsLogger = metricsLogger;
        this.objectMapper = new ObjectMapper();
    }

    public void handle(Context ctx) {
        String requestId = UUID.randomUUID().toString();
        Instant requestStart = Instant.now();
        byte[] requestBody = ctx.bodyAsBytes();

        try {
            if (config.devMode()) {
                analyzePayload(requestBody);
            }

            HttpRequest upstreamRequest = buildUpstreamRequest(ctx, requestBody);
            boolean streaming = isStreamingRequest(ctx, requestBody);

            Instant upstreamStart = Instant.now();
            long responseBytes;
            int statusCode;

            if (streaming) {
                StreamingRelay.StreamingResult result = relay.forwardStreaming(
                    upstreamRequest,
                    ctx.res().getOutputStream(),
                    (status, headers) -> {
                        ctx.status(status);
                        applyResponseHeaders(headers, ctx);
                    }
                );
                statusCode = result.statusCode();
                responseBytes = result.totalBytes();
            } else {
                StreamingRelay.BufferedResponse result = relay.forwardBuffered(upstreamRequest);
                ctx.status(result.statusCode());
                applyResponseHeaders(result.headers(), ctx);
                ctx.result(result.body());
                statusCode = result.statusCode();
                responseBytes = result.body().length;
            }

            logMetrics(requestId, requestStart, upstreamStart, ctx, requestBody.length, responseBytes, statusCode);

        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Upstream communication failed for request {}", requestId, exception);

            ctx.status(502);
            ctx.json(Map.of(
                "error", "Bad Gateway",
                "message", "Failed to communicate with upstream service",
                "requestId", requestId
            ));

            logMetrics(requestId, requestStart, requestStart, ctx, requestBody.length, 0, 502);
        }
    }

    private HttpRequest buildUpstreamRequest(Context ctx, byte[] body) {
        String upstreamUrl = buildUpstreamUrl(ctx);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(upstreamUrl))
            .timeout(UPSTREAM_TIMEOUT);

        ctx.headerMap().forEach((name, value) -> {
            if (!HEADERS_TO_SKIP.contains(name.toLowerCase())
                    && !name.equalsIgnoreCase("authorization")) {
                builder.header(name, value);
            }
        });
        builder.header("Authorization", "Bearer " + config.upstreamApiKey());

        HttpRequest.BodyPublisher bodyPublisher = body.length > 0
            ? HttpRequest.BodyPublishers.ofByteArray(body)
            : HttpRequest.BodyPublishers.noBody();

        builder.method(ctx.method().name(), bodyPublisher);

        return builder.build();
    }

    private String buildUpstreamUrl(Context ctx) {
        StringBuilder url = new StringBuilder(config.upstreamBaseUrl());
        url.append(ctx.path());
        String queryString = ctx.queryString();
        if (queryString != null && !queryString.isEmpty()) {
            url.append('?').append(queryString);
        }
        return url.toString();
    }

    private boolean isStreamingRequest(Context ctx, byte[] body) {
        String accept = ctx.header("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        if (body.length > 0) {
            try {
                JsonNode json = objectMapper.readTree(body);
                JsonNode streamNode = json.get("stream");
                return streamNode != null && streamNode.asBoolean(false);
            } catch (IOException ignored) {
                // Not parseable as JSON — assume non-streaming
            }
        }
        return false;
    }

    private void analyzePayload(byte[] body) {
        if (body.length == 0) {
            return;
        }
        try {
            PayloadAnalyzer.printStructure(objectMapper.readTree(body));
        } catch (IOException exception) {
            LOG.debug("Dev mode: payload is not valid JSON, skipping structure analysis");
        }
    }

    private void applyResponseHeaders(Map<String, List<String>> headers, Context ctx) {
        headers.forEach((name, values) ->
            values.forEach(value -> ctx.res().addHeader(name, value))
        );
    }

    private void logMetrics(
            String requestId,
            Instant requestStart,
            Instant upstreamStart,
            Context ctx,
            long requestBytes,
            long responseBytes,
            int statusCode) {

        Instant now = Instant.now();
        metricsLogger.log(new RequestMetrics(
            requestId,
            requestStart,
            ctx.method().name(),
            ctx.path(),
            requestBytes,
            responseBytes,
            Duration.between(upstreamStart, now).toMillis(),
            Duration.between(requestStart, now).toMillis(),
            statusCode
        ));
    }
}
