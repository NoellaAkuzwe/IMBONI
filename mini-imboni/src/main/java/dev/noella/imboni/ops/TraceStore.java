package dev.noella.imboni.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Keeps recent request traces in memory (bounded) and logs each one as a single structured line,
 * which is what you'd ship to a log pipeline / dashboard in production.
 */
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger("imboni.trace");
    private static final int MAX = 10_000;

    private final ConcurrentLinkedDeque<RequestTrace> traces = new ConcurrentLinkedDeque<>();

    public void record(RequestTrace t) {
        traces.addLast(t);
        while (traces.size() > MAX) traces.pollFirst();
        log.info("lesson_trace teacher={} decision={} reasons={} evidence={} cov={} total_ms={} gen_ms={} tokens_in={} tokens_out={} cost_usd={} unsupported={}/{} cache={} error={}",
                t.teacherId(), t.decision(), t.reasons(), t.evidenceLevel(), String.format("%.2f", t.coverage()),
                t.totalMs(), t.generationMs(), t.inputTokens(), t.outputTokens(), String.format("%.5f", t.costUsd()),
                t.unsupported(), t.statements(), t.cacheHit(), t.error());
    }

    public List<RequestTrace> all() {
        return new ArrayList<>(traces);
    }

    public void clear() {
        traces.clear();
    }

    public static Summary summarize(List<RequestTrace> ts) {
        int n = ts.size();
        if (n == 0) return new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
        List<Long> total = ts.stream().filter(t -> !t.cacheHit()).map(RequestTrace::totalMs).sorted().toList();
        List<Long> gen = ts.stream().filter(t -> t.llmCalls() > 0).map(RequestTrace::generationMs).sorted().toList();
        long answered = ts.stream().filter(t -> t.llmCalls() > 0).count();
        double cost = ts.stream().mapToDouble(RequestTrace::costUsd).sum();
        long review = ts.stream().filter(t -> "NEEDS_REVIEW".equals(t.decision())).count();
        long refused = ts.stream().filter(t -> "REFUSED".equals(t.decision())).count();
        long errors = ts.stream().filter(t -> t.error() != null).count();
        long cache = ts.stream().filter(RequestTrace::cacheHit).count();
        int stmts = ts.stream().mapToInt(RequestTrace::statements).sum();
        int uns = ts.stream().mapToInt(RequestTrace::unsupported).sum();
        Map<String, Integer> reasons = new TreeMap<>();
        ts.forEach(t -> t.reasons().forEach(r -> reasons.merge(r, 1, Integer::sum)));
        return new Summary(n, pct(total, 50), pct(total, 95), pct(gen, 50), pct(gen, 95),
                answered == 0 ? 0 : cost / answered, cost,
                review * 1.0 / n, refused * 1.0 / n, errors * 1.0 / n, cache * 1.0 / n,
                stmts == 0 ? 0 : uns * 1.0 / stmts, reasons);
    }

    static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    public record Summary(int requests, long p50TotalMs, long p95TotalMs, long p50GenerationMs, long p95GenerationMs,
                          double avgCostPerAnsweredUsd, double totalCostUsd, double reviewRate, double refusalRate,
                          double errorRate, double cacheHitRate, double unsupportedStatementRate,
                          Map<String, Integer> reviewReasons) {
        public Summary {
            reviewReasons = Collections.unmodifiableMap(new TreeMap<>(reviewReasons));
        }
    }
}
