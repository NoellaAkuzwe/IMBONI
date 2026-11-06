package dev.noella.imboni.eval;

import dev.noella.imboni.retrieval.Embedder;

import java.util.List;
import java.util.Set;

/** Fails on chosen call numbers (1-based) with a transient error. Used to test ingestion retries. */
public class FlakyEmbedder implements Embedder {

    private final Embedder delegate;
    private final Set<Integer> failOnCalls;
    private int calls;

    public FlakyEmbedder(Embedder delegate, Set<Integer> failOnCalls) {
        this.delegate = delegate;
        this.failOnCalls = failOnCalls;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        calls++;
        if (failOnCalls.contains(calls)) throw new TransientEmbeddingException("simulated 503 on call " + calls, null);
        return delegate.embed(texts);
    }

    public int calls() {
        return calls;
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }
}
