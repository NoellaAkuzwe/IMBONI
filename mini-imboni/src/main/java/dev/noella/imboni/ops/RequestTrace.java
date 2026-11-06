package dev.noella.imboni.ops;

import java.time.Instant;
import java.util.List;

/** One lesson request, end to end. This is what the ops summary and the eval aggregate. */
public record RequestTrace(
        String id,
        String teacherId,
        Instant at,
        String topic,
        String retrievalMode,
        long retrievalMs,
        long generationMs,
        long checkMs,
        long totalMs,
        int llmCalls,
        int inputTokens,
        int outputTokens,
        double costUsd,
        String evidenceLevel,
        double maxSimilarity,
        double coverage,
        String decision,
        List<String> reasons,
        int statements,
        int unsupported,
        int weak,
        boolean cacheHit,
        String error) {}
