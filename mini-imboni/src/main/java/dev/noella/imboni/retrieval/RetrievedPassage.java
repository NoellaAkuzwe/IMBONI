package dev.noella.imboni.retrieval;

import dev.noella.imboni.doc.Chunk;

/**
 * A passage handed to the model. {@code sourceId} ("S1", "S2", ...) is what the model must cite.
 * Scores are kept for debugging and for the eval report (NaN when that search didn't return it).
 */
public record RetrievedPassage(String sourceId, Chunk chunk, double vectorScore, double keywordScore, double fusedScore) {}
