package dev.noella.imboni.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.noella.imboni.llm.HttpJson;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OpenAI embeddings. Enabled with imboni.embedding.provider=openai and OPENAI_API_KEY. */
public class OpenAiEmbedder implements Embedder {

    private final HttpJson http = new HttpJson(Duration.ofSeconds(30), 0); // RetryingEmbedder handles retries
    private final String apiKey;
    private final String model;
    private final int dims;

    public OpenAiEmbedder(String apiKey, String model, int dims) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY is not set");
        this.apiKey = apiKey;
        this.model = model;
        this.dims = dims;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            JsonNode resp = http.post("https://api.openai.com/v1/embeddings",
                    Map.of("authorization", "Bearer " + apiKey),
                    Map.of("model", model, "input", texts, "dimensions", dims));
            List<float[]> out = new ArrayList<>();
            for (JsonNode d : resp.get("data")) {
                JsonNode e = d.get("embedding");
                float[] v = new float[e.size()];
                for (int i = 0; i < v.length; i++) v[i] = (float) e.get(i).asDouble();
                out.add(v);
            }
            return out;
        } catch (HttpJson.ProviderException e) {
            if (e.transientError()) throw new TransientEmbeddingException(e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public int dimensions() {
        return dims;
    }

    @Override
    public String modelName() {
        return model;
    }
}
