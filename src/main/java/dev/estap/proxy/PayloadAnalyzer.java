package dev.estap.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;

/**
 * Dev-mode utility that prints the structural anatomy of a JSON payload
 * without exposing any content values. Used during Phase 0 to map
 * where the user's prompt resides in the IDE's request body.
 */
public final class PayloadAnalyzer {

    private static final Logger DEV_LOG = LoggerFactory.getLogger("ESTAP_DEV");
    private static final String INDENT = "  ";

    private PayloadAnalyzer() {}

    public static void printStructure(JsonNode root) {
        StringBuilder output = new StringBuilder();
        output.append("[ESTAP:DEV] Payload Structure:\n");
        traverseNode(root, output, 1);
        DEV_LOG.info("{}", output);
    }

    private static void traverseNode(JsonNode node, StringBuilder output, int depth) {
        String prefix = INDENT.repeat(depth);

        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                appendField(field.getKey(), field.getValue(), output, prefix, depth);
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int index = 0; index < arrayNode.size(); index++) {
                appendField("[" + index + "]", arrayNode.get(index), output, prefix, depth);
            }
        }
    }

    private static void appendField(String name, JsonNode value, StringBuilder output, String prefix, int depth) {
        if (value.isObject()) {
            output.append(prefix).append(name).append(": OBJECT\n");
            traverseNode(value, output, depth + 1);
        } else if (value.isArray()) {
            output.append(prefix).append(name).append(": ARRAY[").append(value.size()).append("]\n");
            traverseNode(value, output, depth + 1);
        } else if (value.isTextual()) {
            output.append(prefix).append(name).append(": STRING\n");
        } else if (value.isNumber()) {
            output.append(prefix).append(name).append(": NUMBER\n");
        } else if (value.isBoolean()) {
            output.append(prefix).append(name).append(": BOOLEAN\n");
        } else if (value.isNull()) {
            output.append(prefix).append(name).append(": NULL\n");
        }
    }
}
