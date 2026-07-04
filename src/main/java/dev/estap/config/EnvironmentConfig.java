package dev.estap.config;

import io.github.cdimascio.dotenv.Dotenv;

public record EnvironmentConfig(
    int port,
    String upstreamBaseUrl,
    String upstreamApiKey,
    boolean devMode
) {

    public static EnvironmentConfig load() {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        return new EnvironmentConfig(
            parseRequiredInt(dotenv, "ESTAP_PORT"),
            requireNonEmpty(dotenv, "UPSTREAM_BASE_URL"),
            requireNonEmpty(dotenv, "UPSTREAM_API_KEY"),
            Boolean.parseBoolean(dotenv.get("ESTAP_DEV_MODE", "false"))
        );
    }

    private static String requireNonEmpty(Dotenv dotenv, String key) {
        String value = dotenv.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + key);
        }
        return value;
    }

    private static int parseRequiredInt(Dotenv dotenv, String key) {
        String value = requireNonEmpty(dotenv, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                "Environment variable must be numeric: " + key + "=" + value
            );
        }
    }
}
