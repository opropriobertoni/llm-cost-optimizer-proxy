package dev.estap.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentConfigTest {

    private Path tempEnvFile;

    @BeforeEach
    void setUp() {
        tempEnvFile = Path.of(".env");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempEnvFile);
    }

    @Test
    void shouldLoadValidConfig() throws IOException {
        String envContent = """
            ESTAP_PORT=9090
            UPSTREAM_BASE_URL=https://api.test.com
            UPSTREAM_API_KEY=test-key-123
            ESTAP_DEV_MODE=true
            """;
        Files.writeString(tempEnvFile, envContent);

        EnvironmentConfig config = EnvironmentConfig.load();

        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.upstreamBaseUrl()).isEqualTo("https://api.test.com");
        assertThat(config.upstreamApiKey()).isEqualTo("test-key-123");
        assertThat(config.devMode()).isTrue();
    }

    @Test
    void shouldThrowExceptionWhenRequiredVariableIsMissing() throws IOException {
        String envContent = """
            ESTAP_PORT=9090
            UPSTREAM_API_KEY=test-key-123
            """;
        Files.writeString(tempEnvFile, envContent);

        assertThatThrownBy(EnvironmentConfig::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Required environment variable is missing: UPSTREAM_BASE_URL");
    }

    @Test
    void shouldThrowExceptionWhenPortIsNotNumeric() throws IOException {
        String envContent = """
            ESTAP_PORT=not-a-number
            UPSTREAM_BASE_URL=https://api.test.com
            UPSTREAM_API_KEY=test-key-123
            """;
        Files.writeString(tempEnvFile, envContent);

        assertThatThrownBy(EnvironmentConfig::load)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Environment variable must be numeric: ESTAP_PORT=not-a-number");
    }
}
