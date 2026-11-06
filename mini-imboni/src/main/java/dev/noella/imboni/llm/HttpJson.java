package dev.noella.imboni.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal JSON-over-HTTP client for model providers, with a hard timeout per call and bounded
 * retries on the errors that are worth retrying (timeouts, 429, 5xx). 4xx errors (bad key, bad
 * request) fail immediately: retrying them only adds latency and cost.
 */
public class HttpJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final Duration timeout;
    private final int maxRetries;

    public HttpJson(Duration timeout, int maxRetries) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.timeout = timeout;
        this.maxRetries = maxRetries;
    }

    public JsonNode post(String url, Map<String, String> headers, Object body) {
        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) sleep(500L * (1L << (attempt - 1)));
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                headers.forEach(b::header);
                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code / 100 == 2) return JSON.readTree(resp.body());
                ProviderException ex = new ProviderException(code, truncate(resp.body()));
                if (code == 429 || code >= 500) {
                    last = ex;
                    continue;
                }
                throw ex;
            } catch (HttpTimeoutException e) {
                last = new ProviderException(504, "timed out after " + timeout.toSeconds() + "s");
            } catch (IOException e) {
                last = new ProviderException(503, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException(503, "interrupted");
            }
        }
        throw last;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class ProviderException extends RuntimeException {
        public final int status;

        public ProviderException(int status, String msg) {
            super("provider error " + status + ": " + msg);
            this.status = status;
        }

        public boolean transientError() {
            return status == 429 || status >= 500;
        }
    }
}
