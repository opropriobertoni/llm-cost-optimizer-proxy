package dev.estap.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PromptExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptExtractor extractor = new PromptExtractor(objectMapper);

    @Test
    void shouldExtractStringPromptFromMessages() {
        String payload = """
            {
              "model": "gpt-4",
              "messages": [
                { "role": "system", "content": "You are helpful" },
                { "role": "user", "content": "Hello, how are you?" }
              ]
            }
            """;

        Optional<PromptExtractor.ExtractionResult> resultOpt = extractor.extract(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(resultOpt).isPresent();
        PromptExtractor.ExtractionResult result = resultOpt.get();
        assertThat(result.userPrompt()).isEqualTo("Hello, how are you?");
        assertThat(result.jsonPath()).containsExactly("messages", "1", "content");
        assertThat(result.originalPayload().get("model").asText()).isEqualTo("gpt-4");
    }

    @Test
    void shouldExtractTextFromContentArrayBlock() {
        String payload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": [
                    { "type": "image", "source": "xyz" },
                    { "type": "text", "text": "Describe this image" }
                  ]
                }
              ]
            }
            """;

        Optional<PromptExtractor.ExtractionResult> resultOpt = extractor.extract(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(resultOpt).isPresent();
        PromptExtractor.ExtractionResult result = resultOpt.get();
        assertThat(result.userPrompt()).isEqualTo("Describe this image");
        assertThat(result.jsonPath()).containsExactly("messages", "0", "content", "1", "text");
    }

    @Test
    void shouldReturnEmptyWhenNoUserMessagePresent() {
        String payload = """
            {
              "messages": [
                { "role": "system", "content": "System prompt only" }
              ]
            }
            """;

        Optional<PromptExtractor.ExtractionResult> resultOpt = extractor.extract(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(resultOpt).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenPayloadIsMalformed() {
        String payload = "invalid-json";
        Optional<PromptExtractor.ExtractionResult> resultOpt = extractor.extract(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(resultOpt).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenPayloadIsEmpty() {
        Optional<PromptExtractor.ExtractionResult> resultOpt = extractor.extract(new byte[0]);
        assertThat(resultOpt).isEmpty();
    }
}
