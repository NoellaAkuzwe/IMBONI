package dev.noella.imboni.doc;

import dev.noella.imboni.text.Text;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation used by default, in tests and by the eval harness. Same contract as
 * {@link PgVectorChunkStore}: permission filtering happens before scoring.
 */
@Component
@Profile("!pg")
public class InMemoryChunkStore implements ChunkStore {

    private final Map<String, List<Chunk>> byDoc = new ConcurrentHashMap<>();

    @Override
    public void replaceChunks(String documentId, List<Chunk> chunks) {
        byDoc.put(documentId, List.copyOf(chunks)); // single atomic swap
    }

    @Override
    public List<Chunk> chunksOf(String documentId) {
        return byDoc.getOrDefault(documentId, List.of());
    }

    @Override
    public int countAll() {
        return byDoc.values().stream().mapToInt(List::size).sum();
    }

    private List<Chunk> permitted(Collection<String> docIds) {
        List<Chunk> out = new ArrayList<>();
        for (String id : docIds) out.addAll(byDoc.getOrDefault(id, List.of()));
        return out;
    }

    @Override
    public List<Scored> vectorSearch(float[] query, Collection<String> permittedDocIds, int k) {
        return permitted(permittedDocIds).stream()
                .filter(c -> c.embedding() != null)
                .map(c -> new Scored(c, cosine(query, c.embedding())))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(k)
                .toList();
    }

    /**
     * BM25 over the permitted chunks only. Corpus statistics (IDF, average length) are computed on
     * the permitted set too, so another teacher's documents can't even influence the ranking.
     */
    @Override
    public List<Scored> keywordSearch(String query, Collection<String> permittedDocIds, int k) {
        List<Chunk> pool = permitted(permittedDocIds);
        if (pool.isEmpty()) return List.of();
        Set<String> qTerms = Text.contentTermSet(query);
        List<List<String>> docsTerms = pool.stream().map(c -> Text.contentTerms(c.section() + " " + c.text())).toList();
        double avgLen = docsTerms.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Integer> df = new HashMap<>();
        for (List<String> terms : docsTerms) for (String t : new HashSet<>(terms)) df.merge(t, 1, Integer::sum);

        double k1 = 1.2, b = 0.75;
        int n = pool.size();
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> terms = docsTerms.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : terms) tf.merge(t, 1, Integer::sum);
            double s = 0;
            for (String q : qTerms) {
                int f = tf.getOrDefault(q, 0);
                if (f == 0) continue;
                int d = df.getOrDefault(q, 0);
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * terms.size() / avgLen));
            }
            if (s > 0) scored.add(new Scored(pool.get(i), s));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.subList(0, Math.min(k, scored.size()));
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
