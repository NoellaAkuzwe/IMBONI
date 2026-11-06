package dev.noella.imboni.retrieval;

import dev.noella.imboni.doc.Chunk;
import dev.noella.imboni.doc.ChunkStore;
import dev.noella.imboni.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether retrieval found enough evidence to write a lesson at all.
 *
 * <p>Two signals:
 * <ul>
 *   <li><b>maxSimilarity</b>: best vector similarity between the request and any permitted passage.</li>
 *   <li><b>coverage</b>: IDF-weighted share of the request's key terms that appear in the retrieved
 *   passages. IDF weighting matters: for "Ohm's law" against a corpus about Newton's laws, "law" is
 *   covered but the rare, decisive term "ohm" isn't, so coverage stays low and we refuse.</li>
 * </ul>
 * NONE → refuse and say what's missing. WEAK → generate, but force teacher review. SUFFICIENT → normal.
 */
public class EvidenceGate {

    public enum Level { NONE, WEAK, SUFFICIENT }

    private final ChunkStore store;
    private final double minSimilarity;
    private final double minCoverage;
    private final double weakCoverage;

    public EvidenceGate(ChunkStore store, double minSimilarity, double minCoverage, double weakCoverage) {
        this.store = store;
        this.minSimilarity = minSimilarity;
        this.minCoverage = minCoverage;
        this.weakCoverage = weakCoverage;
    }

    public Assessment assess(String query, Collection<String> permittedDocIds, Retriever.Result r) {
        return assess(query, permittedDocIds, r, minSimilarity, minCoverage);
    }

    /** Overload used by the eval threshold sweep. */
    public Assessment assess(String query, Collection<String> permittedDocIds, Retriever.Result r,
                             double minSim, double minCov) {
        Set<String> qTerms = Text.contentTermSet(query);
        if (r.passages().isEmpty() || qTerms.isEmpty()) {
            return new Assessment(Level.NONE, r.maxVectorSimilarity(), 0, List.copyOf(qTerms));
        }
        // Document frequency over the caller's permitted chunks. Fine at prototype scale; at real
        // scale this would be precomputed per document at ingestion time.
        List<Chunk> all = new ArrayList<>();
        for (String id : permittedDocIds) all.addAll(store.chunksOf(id));
        Map<String, Integer> df = new HashMap<>();
        for (Chunk c : all) for (String t : new HashSet<>(Text.contentTerms(c.section() + " " + c.text()))) {
            if (qTerms.contains(t)) df.merge(t, 1, Integer::sum);
        }
        Set<String> retrievedTerms = new HashSet<>();
        for (RetrievedPassage p : r.passages()) retrievedTerms.addAll(Text.contentTerms(p.chunk().section() + " " + p.chunk().text()));

        double total = 0, covered = 0;
        List<String> missing = new ArrayList<>();
        int n = all.size();
        for (String t : qTerms) {
            double idf = Math.log((n + 1.0) / (df.getOrDefault(t, 0) + 1.0)) + 1;
            total += idf;
            if (retrievedTerms.contains(t)) covered += idf; else missing.add(t);
        }
        double coverage = total == 0 ? 0 : covered / total;
        Level level;
        if (r.maxVectorSimilarity() < minSim || coverage < minCov) level = Level.NONE;
        else if (coverage < weakCoverage) level = Level.WEAK;
        else level = Level.SUFFICIENT;
        return new Assessment(level, r.maxVectorSimilarity(), coverage, missing);
    }

    public record Assessment(Level level, double maxSimilarity, double coverage, List<String> missingTerms) {}
}
