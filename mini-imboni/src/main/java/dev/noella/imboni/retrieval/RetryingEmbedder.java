package dev.noella.imboni.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Retries transient embedding failures with exponential backoff. Permanent errors fail fast. */
public class RetryingEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(RetryingEmbedder.class);

    private final Embedder delegate;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public RetryingEmbedder(Embedder delegate, int maxAttempts, long baseBackoffMs) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        TransientEmbeddingException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.embed(texts);
            } catch (TransientEmbeddingException e) {
                last = e;
                log.warn("embedding attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) sleep(baseBackoffMs * (1L << (attempt - 1)));
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
