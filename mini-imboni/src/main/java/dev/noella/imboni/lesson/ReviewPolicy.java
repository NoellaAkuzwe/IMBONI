package dev.noella.imboni.lesson;

import dev.noella.imboni.config.ImboniProperties;
import dev.noella.imboni.retrieval.EvidenceGate;
import dev.noella.imboni.retrieval.RetrievedPassage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a lesson can go straight to the teacher, needs a person to review it first, or
 * should not be produced at all. Every rule produces a reason code so the review queue and the ops
 * summary can show WHY something was routed to review.
 */
public class ReviewPolicy {

    public enum Status { AUTO_APPROVED, NEEDS_REVIEW, REFUSED }

    public record Decision(Status status, List<String> reasons) {}

    private final ImboniProperties.Review cfg;

    public ReviewPolicy(ImboniProperties.Review cfg) {
        this.cfg = cfg;
    }

    public Decision refuse(EvidenceGate.Assessment a) {
        return new Decision(Status.REFUSED, List.of("insufficient_evidence"));
    }

    public Decision decide(EvidenceGate.Assessment evidence, List<String> structureErrors,
                           List<GroundingChecker.StatementCheck> checks, List<RetrievedPassage> passages,
                           LessonDraft draft) {
        List<String> reasons = new ArrayList<>();
        if (evidence.level() == EvidenceGate.Level.WEAK) reasons.add("weak_evidence");
        if (!structureErrors.isEmpty()) reasons.add("invalid_structure");

        long facts = checks.stream().filter(c -> c.statement().isFact()).count();
        long unsupported = checks.stream().filter(c -> c.verdict() == GroundingChecker.Verdict.UNSUPPORTED).count();
        long weak = checks.stream().filter(c -> c.verdict() == GroundingChecker.Verdict.WEAK).count();
        if (facts > 0 && unsupported * 1.0 / Math.max(1, checks.size()) > cfg.maxUnsupportedFraction())
            reasons.add("unsupported_claims");
        if (checks.size() > 0 && weak * 1.0 / checks.size() > 0.2) reasons.add("many_weak_claims");
        if (facts == 0) reasons.add("no_factual_content");

        if (cfg.reviewOnExtractionWarnings()) {
            Set<String> cited = new TreeSet<>();
            checks.forEach(c -> cited.addAll(c.statement().sources()));
            boolean flagged = passages.stream()
                    .anyMatch(p -> cited.contains(p.sourceId()) && !p.chunk().warnings().isEmpty());
            if (flagged) reasons.add("cites_flagged_source");
        }
        if (draft != null && !draft.gaps().isEmpty()) reasons.add("model_reported_gaps");

        return reasons.isEmpty() ? new Decision(Status.AUTO_APPROVED, List.of())
                : new Decision(Status.NEEDS_REVIEW, reasons);
    }
}
