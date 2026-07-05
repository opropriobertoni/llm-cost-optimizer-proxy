package dev.estap;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.estap.circuitbreaker.FailOpenCircuitBreaker;
import dev.estap.compression.CodeBlockExtractor;
import dev.estap.compression.CompressionOrchestrator;
import dev.estap.compression.GroqCompressor;
import dev.estap.compression.SanityCheck;
import dev.estap.config.EnvironmentConfig;
import dev.estap.proxy.PromptExtractor;
import dev.estap.proxy.ProxyController;
import dev.estap.proxy.StreamingRelay;
import dev.estap.telemetry.MetricsLogger;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

public class EstapApplication {

    private static final Logger LOG = LoggerFactory.getLogger(EstapApplication.class);
    private static final String VERSION = "0.1.0";

    private final Javalin app;
    private final EnvironmentConfig config;

    public EstapApplication(EnvironmentConfig config) {
        this.config = config;
        this.app = buildApp(config);
    }

    private static Javalin buildApp(EnvironmentConfig config) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        ObjectMapper objectMapper = new ObjectMapper();
        PromptExtractor promptExtractor = new PromptExtractor(objectMapper);
        CodeBlockExtractor codeBlockExtractor = new CodeBlockExtractor();
        GroqCompressor groqCompressor = new GroqCompressor(config, httpClient, objectMapper);
        SanityCheck sanityCheck = new SanityCheck();
        FailOpenCircuitBreaker circuitBreaker = new FailOpenCircuitBreaker();
        CompressionOrchestrator orchestrator = new CompressionOrchestrator(
            promptExtractor,
            codeBlockExtractor,
            groqCompressor,
            sanityCheck,
            circuitBreaker
        );

        StreamingRelay relay = new StreamingRelay(httpClient);
        MetricsLogger metricsLogger = new MetricsLogger();
        ProxyController proxyController = new ProxyController(config, relay, metricsLogger, orchestrator);

        Javalin app = Javalin.create();

        app.get("/estap/health", ctx -> ctx.json(Map.of(
            "status", "healthy",
            "version", VERSION
        )));

        Handler proxyHandler = proxyController::handle;
        app.get("/<path>", proxyHandler);
        app.post("/<path>", proxyHandler);
        app.put("/<path>", proxyHandler);
        app.delete("/<path>", proxyHandler);
        app.patch("/<path>", proxyHandler);
        app.options("/<path>", proxyHandler);

        return app;
    }

    public void start() {
        long startTime = System.currentTimeMillis();
        app.start(config.port());
        long elapsedMs = System.currentTimeMillis() - startTime;
        LOG.info("ESTAP proxy v{} started on port {} in {}ms (devMode={})",
            VERSION, app.port(), elapsedMs, config.devMode());
    }

    public void stop() {
        app.stop();
    }

    public int port() {
        return app.port();
    }

    public static void main(String[] args) {
        EnvironmentConfig config = EnvironmentConfig.load();
        new EstapApplication(config).start();
    }
}
