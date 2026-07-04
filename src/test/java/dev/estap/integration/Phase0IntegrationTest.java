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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class Phase0IntegrationTest {

    private WireMockServer wireMockServer;
    private EstapApplication app;
    private OkHttpClient httpClient;
    private int proxyPort;

    @BeforeEach
    void setUp() throws IOException {
        // Start WireMock on dynamic port
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor(wireMockServer.port());

        // Create a temporary .env to satisfy EnvironmentConfig loading if needed,
        // but we will start EstapApplication by passing EnvironmentConfig directly.
        // We find a free port for the proxy by starting it on 0 (which Javalin resolves to dynamic port).
        EnvironmentConfig config = new EnvironmentConfig(
            0,
            "http://localhost:" + wireMockServer.port(),
            "test-upstream-api-key",
            true
        );

        app = new EstapApplication(config);
        app.start();
        proxyPort = app.port();

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

    @Test
    void shouldReturnHealthCheck() throws IOException {
        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/estap/health")
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            String bodyString = response.body().string();
            assertThat(bodyString).contains("healthy");
            assertThat(bodyString).contains("0.1.0");
        }
    }

    @Test
    void shouldProxyPostRequestIntact() throws IOException {
        String requestBodyJson = "{\"model\":\"claude-3\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        String responseBodyJson = "{\"content\":\"hello response\"}";

        wireMockServer.stubFor(post(urlEqualTo("/v1/messages"))
            .withHeader("Authorization", equalTo("Bearer test-upstream-api-key"))
            .withRequestBody(equalTo(requestBodyJson))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBodyJson)));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create(requestBodyJson, MediaType.get("application/json")))
            .header("X-Custom-Header", "Value123")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).isEqualTo("application/json");
            assertThat(response.body()).isNotNull();
            assertThat(response.body().string()).isEqualTo(responseBodyJson);
        }

        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/messages"))
            .withHeader("X-Custom-Header", equalTo("Value123")));
    }

    @Test
    void shouldProxySseStreaming() throws IOException {
        String requestBodyJson = "{\"model\":\"claude-3\",\"stream\":true}";
        String sseResponse = "data: {\"token\":\"hello\"}\n\ndata: {\"token\":\"world\"}\n\n";

        wireMockServer.stubFor(post(urlEqualTo("/v1/messages"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sseResponse)));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create(requestBodyJson, MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Type")).contains("text/event-stream");
            assertThat(response.body()).isNotNull();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }

            assertThat(lines).containsExactly(
                "data: {\"token\":\"hello\"}",
                "data: {\"token\":\"world\"}"
            );
        }
    }

    @Test
    void shouldReturn502WhenUpstreamReturnsServerError() throws IOException {
        wireMockServer.stubFor(post(urlEqualTo("/v1/messages"))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create("{}", MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            // Under ordinary non-streaming forwarding, StreamingRelay will return the original 500 status code
            // (or 502 if there was a network connection error).
            // Let's verify that the status matches what the upstream returns.
            assertThat(response.code()).isEqualTo(500);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().string()).isEqualTo("Internal Server Error");
        }
    }

    @Test
    void shouldReturn502OnUpstreamConnectionError() throws IOException {
        // Shutdown the wiremock server to simulate upstream connection failure
        wireMockServer.stop();

        Request request = new Request.Builder()
            .url("http://localhost:" + proxyPort + "/v1/messages")
            .post(RequestBody.create("{}", MediaType.get("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(502);
            assertThat(response.body()).isNotNull();
            String responseBody = response.body().string();
            assertThat(responseBody).contains("Bad Gateway");
            assertThat(responseBody).contains("Failed to communicate with upstream service");
        }
    }
}
