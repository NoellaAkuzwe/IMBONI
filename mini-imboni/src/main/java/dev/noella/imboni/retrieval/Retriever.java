package dev.noella.imboni.retrieval;

import dev.noella.imboni.doc.Chunk;
import dev.noella.imboni.doc.ChunkStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vector, keyword or hybrid retrieval over the caller's permitted documents.
 * Hybrid uses reciprocal rank fusion (RRF), which needs no score calibration between the two
 * searches: score = Σ 1 / (k + rank).
 */
public class Retriever {

    private final ChunkStore store;
    private final Embedder embedder;
    private final int candidatePool;
    private final int rrfK;

    public Retriever(ChunkStore store, Embedder embedder, int candidatePool, int rrfK) {
        this.store = store;
        this.embedder = embedder;
        this.candidatePool = candidatePool;
        this.rrfK = rrfK;
    }

    public Result retrieve(String query, Collection<String> permittedDocIds, RetrievalMode mode, int topK) {
        long t0 = System.nanoTime();
        if (permittedDocIds.isEmpty()) return new Result(List.of(), 0, (System.nanoTime() - t0) / 1_000_000);

        // The vector search always runs: its best similarity feeds the evidence gate even in KEYWORD mode.
        List<ChunkStore.Scored> vec = store.vectorSearch(embedder.embed(query), permittedDocIds, candidatePool);
        double maxSim = vec.isEmpty() ? 0 : vec.get(0).score();
        List<ChunkStore.Scored> kw = mode == RetrievalMode.VECTOR ? List.of()
                : store.keywordSearch(query, permittedDocIds, candidatePool);

        Map<String, Double> vScore = new HashMap<>();
        Map<String, Double> kScore = new HashMap<>();
        Map<String, Chunk> chunks = new LinkedHashMap<>();
        vec.forEach(s -> { vScore.put(s.chunk().id(), s.score()); chunks.putIfAbsent(s.chunk().id(), s.chunk()); });
        kw.forEach(s -> { kScore.put(s.chunk().id(), s.score()); chunks.putIfAbsent(s.chunk().id(), s.chunk()); });

        Map<String, Double> fused = new HashMap<>();
        switch (mode) {
            case VECTOR -> vec.forEach(s -> fused.put(s.chunk().id(), s.score()));
            case KEYWORD -> kw.forEach(s -> fused.put(s.chunk().id(), s.score()));
            case HYBRID -> {
                for (int i = 0; i < vec.size(); i++) fused.merge(vec.get(i).chunk().id(), 1.0 / (rrfK + i + 1), Double::sum);
                for (int i = 0; i < kw.size(); i++) fused.merge(kw.get(i).chunk().id(), 1.0 / (rrfK + i + 1), Double::sum);
            }
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(fused.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<RetrievedPassage> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            String id = ranked.get(i).getKey();
            out.add(new RetrievedPassage("S" + (i + 1), chunks.get(id),
                    vScore.getOrDefault(id, Double.NaN), kScore.getOrDefault(id, Double.NaN), ranked.get(i).getValue()));
        }
        return new Result(out, maxSim, (System.nanoTime() - t0) / 1_000_000);
    }

    public record Result(List<RetrievedPassage> passages, double maxVectorSimilarity, long latencyMs) {}
}
