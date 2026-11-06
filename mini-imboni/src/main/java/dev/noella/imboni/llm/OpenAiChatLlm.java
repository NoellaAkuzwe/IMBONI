package dev.noella.imboni.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** OpenAI Chat Completions in JSON mode. imboni.llm.provider=openai + OPENAI_API_KEY. */
public class OpenAiChatLlm implements LlmClient {

    private final HttpJson http;
    private final String apiKey;
    private final String model;

    public OpenAiChatLlm(String apiKey, String model, Duration timeout, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY is not set");
        this.apiKey = apiKey;
        this.model = model;
        this.http = new HttpJson(timeout, maxRetries);
    }

    @Override
    public Result complete(Request req) {
        long t0 = System.nanoTime();
        JsonNode resp = http.post("https://api.openai.com/v1/chat/completions",
                Map.of("authorization", "Bearer " + apiKey),
                Map.of("model", model,
                        "temperature", 0.2,
                        "max_tokens", req.maxOutputTokens(),
                        "response_format", Map.of("type", "json_object"),
                        "messages", List.of(
                                Map.of("role", "system", "content", req.system()),
                                Map.of("role", "user", "content", req.user()))));
        String text = resp.at("/choices/0/message/content").asText("");
        return new Result(text, resp.at("/usage/prompt_tokens").asInt(), resp.at("/usage/completion_tokens").asInt(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public String modelName() {
        return model;
    }
}
