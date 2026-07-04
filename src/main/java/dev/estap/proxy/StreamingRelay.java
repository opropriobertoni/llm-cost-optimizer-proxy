package dev.estap.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StreamingRelay {

    private static final int PIPE_BUFFER_SIZE = 1024;
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "transfer-encoding", "content-length", "connection"
    );

    private final HttpClient httpClient;

    public StreamingRelay(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public BufferedResponse forwardBuffered(HttpRequest upstreamRequest)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(
            upstreamRequest,
            HttpResponse.BodyHandlers.ofByteArray()
        );
        return new BufferedResponse(
            response.statusCode(),
            filterHopByHopHeaders(response.headers().map()),
            response.body()
        );
    }

    public StreamingResult forwardStreaming(
            HttpRequest upstreamRequest,
            OutputStream clientOutput,
            ResponseInitializer initializer) throws IOException, InterruptedException {

        HttpResponse<InputStream> response = httpClient.send(
            upstreamRequest,
            HttpResponse.BodyHandlers.ofInputStream()
        );

        initializer.initialize(
            response.statusCode(),
            filterHopByHopHeaders(response.headers().map())
        );

        long totalBytes = pipeStream(response.body(), clientOutput);
        return new StreamingResult(response.statusCode(), totalBytes);
    }

    private long pipeStream(InputStream source, OutputStream destination) throws IOException {
        long totalBytes = 0;
        try (source) {
            byte[] buffer = new byte[PIPE_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = source.read(buffer)) != -1) {
                destination.write(buffer, 0, bytesRead);
                destination.flush();
                totalBytes += bytesRead;
            }
        }
        return totalBytes;
    }

    private static Map<String, List<String>> filterHopByHopHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null && !HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                filtered.put(name, values);
            }
        });
        return Collections.unmodifiableMap(filtered);
    }

    public record BufferedResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body
    ) {}

    public record StreamingResult(int statusCode, long totalBytes) {}

    @FunctionalInterface
    public interface ResponseInitializer {
        void initialize(int statusCode, Map<String, List<String>> headers);
    }
}
