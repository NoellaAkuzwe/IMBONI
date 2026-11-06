package dev.noella.imboni.retrieval;

import dev.noella.imboni.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline embedding: feature-hashed unigrams + bigrams + character trigrams, log-TF weighted,
 * L2-normalized. It is NOT semantic (it won't know "velocity" ≈ "speed"), which is exactly why the
 * eval compares it against keyword and hybrid search instead of assuming vectors are better.
 * Swap in {@link OpenAiEmbedder} with {@code imboni.embedding.provider=openai}.
 */
public class HashingEmbedder implements Embedder {

    private final int dims;

    public HashingEmbedder(int dims) {
        this.dims = dims;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(embedOne(t));
        return out;
    }

    private float[] embedOne(String text) {
        float[] v = new float[dims];
        List<String> terms = Text.contentTerms(text);
        for (int i = 0; i < terms.size(); i++) {
            add(v, "u:" + terms.get(i), 1.0f);
            if (i + 1 < terms.size()) add(v, "b:" + terms.get(i) + "_" + terms.get(i + 1), 0.7f);
            String w = "#" + terms.get(i) + "#";
            for (int j = 0; j + 3 <= w.length(); j++) add(v, "c:" + w.substring(j, j + 3), 0.25f);
        }
        for (int i = 0; i < dims; i++) v[i] = (float) (Math.signum(v[i]) * Math.log1p(Math.abs(v[i])));
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < dims; i++) v[i] /= (float) norm;
        return v;
    }

    private void add(float[] v, String feature, float w) {
        int h = feature.hashCode();
        int idx = Math.floorMod(h, dims);
        float sign = ((h >>> 31) == 0) ? 1f : -1f;
        v[idx] += sign * w;
    }

    @Override
    public int dimensions() {
        return dims;
    }

    @Override
    public String modelName() {
        return "hashing-v1";
    }
}
