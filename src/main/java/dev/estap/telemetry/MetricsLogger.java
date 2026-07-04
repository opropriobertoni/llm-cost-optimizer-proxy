package dev.estap.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsLogger {

    private static final Logger METRICS_LOG = LoggerFactory.getLogger("ESTAP_METRICS");

    private final ObjectMapper objectMapper;

    public MetricsLogger() {
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void log(RequestMetrics metrics) {
        try {
            METRICS_LOG.info(objectMapper.writeValueAsString(metrics));
        } catch (JsonProcessingException exception) {
            METRICS_LOG.error("Failed to serialize request metrics", exception);
        }
    }

    public void log(CompressionMetrics metrics) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(metrics);
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode) {
                objectNode.put("type", "COMPRESSION");
            }
            METRICS_LOG.info(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            METRICS_LOG.error("Failed to serialize compression metrics", exception);
        }
    }
}
