package dev.noella.imboni.doc;

import java.util.Collection;
import java.util.List;

/**
 * Passage storage + search. Every search method takes the set of document ids the caller is allowed
 * to read; implementations must filter on it in the query itself (not after the fact), so unpermitted
 * passages can never reach the model.
 */
public interface ChunkStore {

    /**
     * Atomically replace all chunks of a document. Re-running ingestion for a document therefore
     * can never leave duplicate or half-written passages.
     */
    void replaceChunks(String documentId, List<Chunk> chunks);

    List<Chunk> chunksOf(String documentId);

    int countAll();

    /** Top-k by cosine similarity among permitted documents. */
    List<Scored> vectorSearch(float[] query, Collection<String> permittedDocIds, int k);

    /** Top-k by keyword relevance (BM25 in memory, ts_rank in Postgres) among permitted documents. */
    List<Scored> keywordSearch(String query, Collection<String> permittedDocIds, int k);

    record Scored(Chunk chunk, double score) {}
}
