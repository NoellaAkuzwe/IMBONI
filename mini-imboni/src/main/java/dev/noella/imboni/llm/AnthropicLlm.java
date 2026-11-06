package dev.noella.imboni.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Anthropic Messages API. imboni.llm.provider=anthropic + ANTHROPIC_API_KEY. */
public class AnthropicLlm implements LlmClient {

    private final HttpJson http;
    private final String apiKey;
    private final String model;

    public AnthropicLlm(String apiKey, String model, Duration timeout, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        this.apiKey = apiKey;
        this.model = model;
        this.http = new HttpJson(timeout, maxRetries);
    }

    @Override
    public Result complete(Request req) {
        long t0 = System.nanoTime();
        JsonNode resp = http.post("https://api.anthropic.com/v1/messages",
                Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"),
                Map.of("model", model,
                        "max_tokens", req.maxOutputTokens(),
                        "temperature", 0.2,
                        "system", req.system(),
                        "messages", List.of(Map.of("role", "user", "content", req.user()))));
        StringBuilder text = new StringBuilder();
        for (JsonNode block : resp.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        }
        return new Result(text.toString(), resp.at("/usage/input_tokens").asInt(), resp.at("/usage/output_tokens").asInt(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public String modelName() {
        return model;
    }
}
