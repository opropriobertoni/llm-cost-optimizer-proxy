package dev.estap.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class PromptExtractor {

    private final ObjectMapper objectMapper;

    public PromptExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ExtractionResult> extract(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(payloadBytes);
            if (!root.isObject()) {
                return Optional.empty();
            }

            ObjectNode originalPayload = (ObjectNode) root;
            JsonNode messagesNode = originalPayload.get("messages");

            if (messagesNode == null || !messagesNode.isArray()) {
                return Optional.empty();
            }

            ArrayNode messages = (ArrayNode) messagesNode;
            int lastUserIndex = -1;

            // Find the last user message
            for (int i = 0; i < messages.size(); i++) {
                JsonNode message = messages.get(i);
                JsonNode role = message.get("role");
                if (role != null && role.isTextual() && "user".equalsIgnoreCase(role.asText())) {
                    lastUserIndex = i;
                }
            }

            if (lastUserIndex == -1) {
                return Optional.empty();
            }

            JsonNode userMessage = messages.get(lastUserIndex);
            JsonNode content = userMessage.get("content");

            if (content == null) {
                return Optional.empty();
            }

            if (content.isTextual()) {
                List<String> path = List.of("messages", String.valueOf(lastUserIndex), "content");
                return Optional.of(new ExtractionResult(
                    content.asText(),
                    path.toArray(new String[0]),
                    originalPayload
                ));
            } else if (content.isArray()) {
                ArrayNode contentArray = (ArrayNode) content;
                for (int j = 0; j < contentArray.size(); j++) {
                    JsonNode block = contentArray.get(j);
                    JsonNode type = block.get("type");
                    if (type != null && type.isTextual() && "text".equalsIgnoreCase(type.asText())) {
                        JsonNode text = block.get("text");
                        if (text != null && text.isTextual()) {
                            List<String> path = List.of(
                                "messages",
                                String.valueOf(lastUserIndex),
                                "content",
                                String.valueOf(j),
                                "text"
                            );
                            return Optional.of(new ExtractionResult(
                                text.asText(),
                                path.toArray(new String[0]),
                                originalPayload
                            ));
                        }
                    }
                }
            }

        } catch (IOException exception) {
            // Ignore parse errors and let the request fall back to passthrough
        }

        return Optional.empty();
    }

    public record ExtractionResult(
        String userPrompt,
        String[] jsonPath,
        ObjectNode originalPayload
    ) {}
}
