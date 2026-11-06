package dev.noella.imboni.retrieval;

import java.util.List;

public interface Embedder {

    List<float[]> embed(List<String> texts);

    default float[] embed(String text) {
        return embed(List.of(text)).get(0);
    }

    int dimensions();

    String modelName();

    /** Thrown for failures worth retrying (timeouts, 429, 5xx). */
    class TransientEmbeddingException extends RuntimeException {
        public TransientEmbeddingException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
