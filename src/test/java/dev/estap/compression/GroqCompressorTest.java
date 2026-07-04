package dev.estap.compression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.estap.config.EnvironmentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroqCompressorTest {

    private WireMockServer wireMockServer;
    private GroqCompressor compressor;
    private EnvironmentConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor(wireMockServer.port());

        config = new EnvironmentConfig(
            0,
            "http://localhost:1111", // unused upstream url
            "test-upstream-key",
            false,
            "test-groq-key",
            "llama3-70b",
            "http://localhost:" + wireMockServer.port() + "/v1/chat/completions",
            5000, // 5000ms timeout for tests to prevent cold start failures
            false
        );

        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        compressor = new GroqCompressor(config, httpClient, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldSendCorrectPayloadAndReturnCompressedText() throws IOException, InterruptedException {
        String mockResponse = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1677652288,
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Create Java method sum(a, b) and print result."
                  },
                  "finish_reason": "stop"
                }
              ]
            }
            """;

        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer test-groq-key"))
            .withHeader("Content-Type", equalTo("application/json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(mockResponse)));

        String result = compressor.compress("Crie um método em Java para somar a e b e imprimir o resultado.");

        assertThat(result).isEqualTo("Create Java method sum(a, b) and print result.");

        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void shouldThrowIOExceptionWhenApiReturnsNon200() {
        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(429)
                .withBody("Rate limit exceeded")));

        assertThatThrownBy(() -> compressor.compress("hello"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Groq API returned error status: 429");
    }

    @Test
    void shouldThrowIOExceptionOnTimeout() {
        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(6000) // delay is 6s, timeout is 5s
                .withBody("{}")));

        assertThatThrownBy(() -> compressor.compress("hello"))
            // In Java HttpClient, a timeout is thrown as HttpConnectTimeoutException or HttpTimeoutException,
            // both subclassing IOException.
            .isInstanceOf(IOException.class);
    }
}
