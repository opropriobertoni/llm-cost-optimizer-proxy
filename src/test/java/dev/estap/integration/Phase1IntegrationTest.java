package dev.estap.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.estap.EstapApplication;
import dev.estap.config.EnvironmentConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class Phase1IntegrationTest {

    private WireMockServer wireMockServer;
    private EstapApplication app;
    private OkHttpClient httpClient;
    private int proxyPort;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor(wireMockServer.port());

        httpClient = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private void startApp(boolean dryRun) {
        EnvironmentConfig config = new EnvironmentConfig(
            0,
            "http://localhost:" + wireMockServer.port(),
            "test-upstream-api-key",
            true,
            "test-groq-key",
            "llama3-70b",
            "http://localhost:" + wireMockServer.port() + "/v1/chat/completions",
            5000, // 5s timeout to prevent cold start failures
            dryRun
        );

        app = new EstapApplication(config);
        app.start();
        proxyPort = app.port();
    }

    @Test
    void shouldCompressPromptAndForwardToUpstream() throws IOException {
        startApp(false);

        String clientRequestPayload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": "Crie um método simples em Java.\\n```java\\nint sum(int a, int b) { return a+b; }\\n```\\nMuito obrigado!"
                }
              ]
            }
            """;

        String groqResponsePayload = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Create simple Java method.\\n{{CODE_BLOCK_0}}"
                  }
                }
              ]
            }
            """;

        String expectedUpstreamPayload = "{\"messages\":[{\"role\":\"user\",\"content\":\"Create simple Java method.\\n```java\\nint sum(int a, int b) { return a+b; }\\n```\"}]}";

        // Stub the Groq endpoint
        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer test-groq-key"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(groqResponsePayload)));

        // Stub the Upstream model endpoint
        wireMockServer.stubFor(post(urlEqualTo("/v1/messages"))
            .withHeader("Authorization", equalTo("Bearer test-upstream-api-key"))
            .withRequestBody(equalTo(expectedUpstreamPayload))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"content\":\"hello output\"}")));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create(clientRequestPayload, MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().string()).contains("hello output");
        }

        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void shouldTriggerFailOpenToOriginalPayloadOnGroqTimeout() throws IOException {
        startApp(false);

        String clientRequestPayload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": "Crie um método simples."
                }
              ]
            }
            """;

        // Groq delays 6s (timeout is 5s)
        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(6000)
                .withBody("{}")));

        // Stub upstream with original payload
        wireMockServer.stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(equalTo(clientRequestPayload))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"content\":\"original output\"}")));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create(clientRequestPayload, MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().string()).contains("original output");
        }

        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void shouldTriggerFailOpenToOriginalPayloadOnSanityCheckFailure() throws IOException {
        startApp(false);

        String clientRequestPayload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": "Soma"
                }
              ]
            }
            """;

        // Groq returns a larger response, violating mathematical size check
        String largerGroqResponse = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Esta é uma resposta muito maior que o prompt original"
                  }
                }
              ]
            }
            """;

        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(largerGroqResponse)));

        // Stub upstream with original payload
        wireMockServer.stubFor(post(urlEqualTo("/v1/messages"))
            .withRequestBody(equalTo(clientRequestPayload))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"content\":\"original output\"}")));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create(clientRequestPayload, MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().string()).contains("original output");
        }

        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void shouldHandleDryRunModeWithoutCallingUpstream() throws IOException {
        startApp(true); // dryRun = true

        String clientRequestPayload = """
            {
              "messages": [
                {
                  "role": "user",
                  "content": "Por favor crie um script bash."
                }
              ]
            }
            """;

        String groqResponsePayload = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Create bash script."
                  }
                }
              ]
            }
            """;

        wireMockServer.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(groqResponsePayload)));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create(clientRequestPayload, MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            String body = response.body().string();
            assertThat(body).contains("dryRun");
            assertThat(body).contains("compressionApplied");
            assertThat(body).contains("originalTokens");
            assertThat(body).contains("compressedTokens");
        }

        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        // Upstream messages endpoint should NOT be called in dry run mode
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }
}
