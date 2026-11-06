package dev.noella.imboni.config;

import dev.noella.imboni.retrieval.RetrievalMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * All tunables in one place. Every threshold here is something the eval harness measures or
 * sweeps, so changing one should be followed by re-running {@code EvalRunner}.
 */
@ConfigurationProperties(prefix = "imboni")
public record ImboniProperties(
        Embedding embedding,
        Llm llm,
        Retrieval retrieval,
        Evidence evidence,
        Review review,
        Map<String, Price> pricing,
        Map<String, String> apiKeys) {

    public ImboniProperties {
        if (embedding == null) embedding = new Embedding(null, 0, null);
        if (llm == null) llm = new Llm(null, null, 0, 0, 0, null);
        if (retrieval == null) retrieval = new Retrieval(null, 0, 0, 0);
        if (evidence == null) evidence = new Evidence(0, 0);
        if (review == null) review = new Review(-1, true, 0);
        if (pricing == null || pricing.isEmpty()) pricing = Map.of(
                // USD per million tokens. Used to estimate cost per lesson; update when prices change.
                "gpt-4o-mini", new Price(0.15, 0.60),
                "claude-haiku", new Price(1.00, 5.00),
                "offline-extractive", new Price(0, 0));
        if (apiKeys == null || apiKeys.isEmpty()) apiKeys = Map.of("dev-key-alice", "alice", "dev-key-bob", "bob");
    }

    public static ImboniProperties defaults() {
        return new ImboniProperties(null, null, null, null, null, null, null);
    }

    /** provider: hashing (offline, default) | openai. */
    public record Embedding(String provider, int dimensions, String model) {
        public Embedding {
            if (provider == null) provider = "hashing";
            if (dimensions <= 0) dimensions = "openai".equals(provider) ? 1536 : 512;
            if (model == null) model = "openai".equals(provider) ? "text-embedding-3-small" : "hashing-v1";
        }
    }

    /** provider: offline (default) | openai | anthropic. */
    public record Llm(String provider, String model, int timeoutSeconds, int maxRetries, int maxOutputTokens,
                      String pricingKey) {
        public Llm {
            if (provider == null) provider = "offline";
            if (model == null) model = switch (provider) {
                case "openai" -> "gpt-4o-mini";
                case "anthropic" -> "claude-haiku-4-5";
                default -> "offline-extractive";
            };
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
            if (maxRetries <= 0) maxRetries = 2;
            if (maxOutputTokens <= 0) maxOutputTokens = 1200;
            if (pricingKey == null) pricingKey = switch (provider) {
                case "openai" -> "gpt-4o-mini";
                case "anthropic" -> "claude-haiku";
                default -> "offline-extractive";
            };
        }
    }

    public record Retrieval(RetrievalMode mode, int topK, int candidatePool, int rrfK) {
        public Retrieval {
            if (mode == null) mode = RetrievalMode.HYBRID;
            if (topK <= 0) topK = 5;
            if (candidatePool <= 0) candidatePool = 20;
            if (rrfK <= 0) rrfK = 60;
        }
    }

    /**
     * Evidence gate. A request is answered only if the best passage is similar enough AND the
     * retrieved passages cover enough of the request's key terms. Defaults were chosen on the eval
     * "dev" split (see EvalRunner's threshold sweep) and reported on the "test" split.
     */
    public record Evidence(double minSimilarity, double minCoverage) {
        public Evidence {
            if (minSimilarity <= 0) minSimilarity = 0.12;
            if (minCoverage <= 0) minCoverage = 0.5;
        }
    }

    /**
     * When a person must review a lesson before it's used.
     * maxUnsupportedFraction: any fact statement above this share unsupported → review (default 0: any).
     * reviewOnExtractionWarnings: lesson cites a passage whose source had OCR/extraction warnings → review.
     * weakEvidenceCoverage: coverage below this (but above the refusal gate) → review.
     */
    public record Review(double maxUnsupportedFraction, boolean reviewOnExtractionWarnings, double weakEvidenceCoverage) {
        public Review {
            if (maxUnsupportedFraction < 0) maxUnsupportedFraction = 0.0;
            if (weakEvidenceCoverage <= 0) weakEvidenceCoverage = 0.75;
        }
    }

    public record Price(double inputPerMTok, double outputPerMTok) {
        public double cost(int in, int out) {
            return in / 1e6 * inputPerMTok + out / 1e6 * outputPerMTok;
        }
    }
}
