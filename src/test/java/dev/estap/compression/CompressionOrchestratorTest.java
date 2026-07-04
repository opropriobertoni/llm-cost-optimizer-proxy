package dev.estap.compression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.estap.circuitbreaker.FailOpenCircuitBreaker;
import dev.estap.config.EnvironmentConfig;
import dev.estap.proxy.PromptExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class CompressionOrchestratorTest {

    private WireMockServer wireMockServer;
    private CompressionOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor(wireMockServer.port());

        EnvironmentConfig config = new EnvironmentConfig(
            0,
            "http://localhost:1111",
            "test-upstream-key",
            false,
            "test-groq-key",
            "llama3-70b",
            "http://localhost:" + wireMockServer.port() + "/v1/chat/completions",
            5000, // 5s timeout to avoid test cold start delays
            false
        );

        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        PromptExtractor promptExtractor = new PromptExtractor(objectMapper);
        CodeBlockExtractor codeBlockExtractor = new CodeBlockExtractor();
        GroqCompressor groqCompressor = new GroqCompressor(config, httpClient, objectMapper);
        SanityCheck sanityCheck = new SanityCheck();
        FailOpenCircuitBreaker circuitBreaker = new FailOpenCircuitBreaker();

        orchestrator = new CompressionOrchestrator(
            promptExtractor,
            codeBlockExtractor,
            groqCompressor,
            sanityCheck,
            circuitBreaker,
            objectMapper
        );
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldSuccessfullyCompressPayload() throws IOException {
        String originalPayload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": "Por favor, crie uma classe Java com o método main.\\n```java\\npublic class App {}\\n```\\nObrigado!"
                }
              ]
            }
            """;

        String mockResponse = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Create Java class with main method.\\n{{CODE_BLOCK_0}}"
                  }
                }
              ]
            }
            """;

        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(mockResponse)));

        CompressionOrchestrator.CompressionOutcome outcome = orchestrator.orchestrate(
            originalPayload.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(outcome.compressionApplied()).isTrue();
        assertThat(outcome.failOpenTriggered()).isFalse();
        assertThat(outcome.failOpenReason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.NONE);
        assertThat(outcome.codeBlocksCount()).isEqualTo(1);

        String resultJson = new String(outcome.finalPayloadBody(), StandardCharsets.UTF_8);
        assertThat(resultJson).contains("Create Java class with main method.");
        assertThat(resultJson).contains("public class App {}"); // Code block is preserved
    }

    @Test
    void shouldTriggerFailOpenOnGroqTimeout() {
        String originalPayload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": "Crie um método simples."
                }
              ]
            }
            """;

        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(6000) // Delay exceeds 5s timeout
                .withBody("{}")));

        CompressionOrchestrator.CompressionOutcome outcome = orchestrator.orchestrate(
            originalPayload.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(outcome.compressionApplied()).isFalse();
        assertThat(outcome.failOpenTriggered()).isTrue();
        assertThat(outcome.failOpenReason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.TIMEOUT);
        assertThat(new String(outcome.finalPayloadBody(), StandardCharsets.UTF_8)).isEqualTo(originalPayload);
    }

    @Test
    void shouldTriggerFailOpenOnSanityCheckFailure() {
        String originalPrompt = "Crie um método simples."; // length 22
        String originalPayload = "{\"messages\":[{\"role\":\"user\",\"content\":\"" + originalPrompt + "\"}]}";

        // Mock returns a response that is actually larger than the original prompt
        String largerResponse = "Por favor, crie um método extremamente simples e legível."; // length 56
        String mockResponse = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + largerResponse + "\"}}]}";

        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(mockResponse)));

        CompressionOrchestrator.CompressionOutcome outcome = orchestrator.orchestrate(
            originalPayload.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(outcome.compressionApplied()).isFalse();
        assertThat(outcome.failOpenTriggered()).isTrue();
        assertThat(outcome.failOpenReason()).isEqualTo(FailOpenCircuitBreaker.FailOpenReason.SANITY_CHECK_FAILED);
        assertThat(new String(outcome.finalPayloadBody(), StandardCharsets.UTF_8)).isEqualTo(originalPayload);
    }
}
