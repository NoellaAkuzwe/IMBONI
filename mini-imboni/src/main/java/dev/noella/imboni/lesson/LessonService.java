package dev.noella.imboni.lesson;

import dev.noella.imboni.config.ImboniProperties;
import dev.noella.imboni.doc.Document;
import dev.noella.imboni.doc.DocumentStatus;
import dev.noella.imboni.doc.DocumentStore;
import dev.noella.imboni.llm.LlmClient;
import dev.noella.imboni.ops.RequestTrace;
import dev.noella.imboni.ops.TraceStore;
import dev.noella.imboni.retrieval.EvidenceGate;
import dev.noella.imboni.retrieval.RetrievalMode;
import dev.noella.imboni.retrieval.RetrievedPassage;
import dev.noella.imboni.retrieval.Retriever;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The request path: authorize → retrieve → evidence gate → generate → validate (one repair) →
 * grounding check → review decision → trace.
 */
public class LessonService {

    private final DocumentStore docs;
    private final Retriever retriever;
    private final EvidenceGate gate;
    private final LlmClient llm;
    private final StructureValidator validator = new StructureValidator();
    private final GroundingChecker grounding = new GroundingChecker();
    private final ReviewPolicy policy;
    private final LessonStore lessons;
    private final TraceStore traces;
    private final ImboniProperties props;
    /**
     * Response cache. The key includes the teacher id and the content hash of every permitted
     * document, so cached lessons follow the same permission rules and go stale when documents change.
     */
    private final Map<String, LessonResponse> cache = new ConcurrentHashMap<>();

    public LessonService(DocumentStore docs, Retriever retriever, EvidenceGate gate, LlmClient llm, ReviewPolicy policy,
                         LessonStore lessons, TraceStore traces, ImboniProperties props) {
        this.docs = docs;
        this.retriever = retriever;
        this.gate = gate;
        this.llm = llm;
        this.policy = policy;
        this.lessons = lessons;
        this.traces = traces;
        this.props = props;
    }

    public LessonResponse generate(String teacherId, LessonRequest req) {
        return generate(teacherId, req, props.retrieval().mode());
    }

    public LessonResponse generate(String teacherId, LessonRequest req, RetrievalMode mode) {
        long t0 = System.nanoTime();
        String traceId = UUID.randomUUID().toString();

        List<Document> permitted = permittedDocuments(teacherId, req.documentIds());
        List<String> permittedIds = permitted.stream().map(Document::id).toList();
        String cacheKey = teacherId + "|" + mode + "|" + req.cacheKey() + "|"
                + permitted.stream().map(d -> d.id() + ":" + d.contentHash() + ":" + d.chunkCount()).sorted().collect(Collectors.joining(","));
        LessonResponse cached = cache.get(cacheKey);
        if (cached != null) {
            traces.record(new RequestTrace(traceId, teacherId, Instant.now(), req.topic(), mode.name(), 0, 0, 0,
                    ms(t0), 0, 0, 0, 0, cached.evidence().level(), cached.evidence().maxSimilarity(),
                    cached.evidence().coverage(), cached.status(), cached.reviewReasons(), 0, 0, 0, true, null));
            return cached;
        }

        Retriever.Result r = retriever.retrieve(req.retrievalQuery(), permittedIds, mode, props.retrieval().topK());
        EvidenceGate.Assessment ev = gate.assess(req.topic(), permittedIds, r);
        LessonResponse.Evidence evidence = new LessonResponse.Evidence(ev.level().name(), round(ev.maxSimilarity()),
                round(ev.coverage()), ev.missingTerms());

        if (ev.level() == EvidenceGate.Level.NONE) {
            ReviewPolicy.Decision d = policy.refuse(ev);
            String closest = r.passages().stream().map(p -> p.chunk().documentTitle()).distinct().limit(3)
                    .collect(Collectors.joining(", "));
            String msg = permittedIds.isEmpty()
                    ? "You have no processed documents yet. Upload teaching material first."
                    : "Your documents don't cover this request well enough to write a grounded lesson"
                    + (ev.missingTerms().isEmpty() ? "" : " (not found: " + String.join(", ", ev.missingTerms()) + ")")
                    + "." + (closest.isEmpty() ? "" : " Closest material: " + closest + ".")
                    + " Upload material on this topic or rephrase the request.";
            LessonResponse resp = new LessonResponse(null, d.status().name(), d.reasons(), msg, null, List.of(),
                    List.of(), evidence, traceId);
            traces.record(new RequestTrace(traceId, teacherId, Instant.now(), req.topic(), mode.name(), r.latencyMs(), 0, 0,
                    ms(t0), 0, 0, 0, 0, ev.level().name(), ev.maxSimilarity(), ev.coverage(), d.status().name(),
                    d.reasons(), 0, 0, 0, false, null));
            return resp;
        }

        Set<String> sourceIds = r.passages().stream().map(RetrievedPassage::sourceId).collect(Collectors.toSet());
        String user = LessonPrompt.user(req, r.passages());
        int calls = 0, inTok = 0, outTok = 0;
        long genMs = 0;
        StructureValidator.Outcome outcome;
        try {
            LlmClient.Result first = llm.complete(new LlmClient.Request(LessonPrompt.SYSTEM, user, props.llm().maxOutputTokens()));
            calls++; inTok += first.inputTokens(); outTok += first.outputTokens(); genMs += first.latencyMs();
            outcome = validator.validate(first.text(), sourceIds);
            if (!outcome.valid()) {
                // One repair attempt with the concrete errors. Bounded: a second failure goes to review.
                LlmClient.Result fix = llm.complete(new LlmClient.Request(LessonPrompt.SYSTEM,
                        user + "\n\n" + LessonPrompt.repair(first.text(), outcome.errors()), props.llm().maxOutputTokens()));
                calls++; inTok += fix.inputTokens(); outTok += fix.outputTokens(); genMs += fix.latencyMs();
                StructureValidator.Outcome second = validator.validate(fix.text(), sourceIds);
                if (second.draft() != null) outcome = second;
            }
        } catch (RuntimeException e) {
            double cost = cost(inTok, outTok);
            traces.record(new RequestTrace(traceId, teacherId, Instant.now(), req.topic(), mode.name(), r.latencyMs(), genMs, 0,
                    ms(t0), calls, inTok, outTok, cost, ev.level().name(), ev.maxSimilarity(), ev.coverage(), "ERROR",
                    List.of(), 0, 0, 0, false, e.getMessage()));
            throw new GenerationFailed("The model request failed: " + e.getMessage(), traceId, e);
        }

        long tc = System.nanoTime();
        List<GroundingChecker.StatementCheck> checks = outcome.draft() == null ? List.of()
                : grounding.check(outcome.draft(), r.passages());
        ReviewPolicy.Decision d = policy.decide(ev, outcome.errors(), checks, r.passages(), outcome.draft());
        if (outcome.draft() == null) d = new ReviewPolicy.Decision(ReviewPolicy.Status.NEEDS_REVIEW, List.of("unparseable_output"));
        long checkMs = (System.nanoTime() - tc) / 1_000_000;

        List<LessonResponse.CheckedStatement> checked = checks.stream().map(c -> new LessonResponse.CheckedStatement(
                c.section(), c.statement().text(), c.statement().sources(), c.statement().kind(), c.verdict().name(), c.reason())).toList();
        Set<String> citedIds = checks.stream().flatMap(c -> c.statement().sources().stream()).collect(Collectors.toSet());
        List<LessonResponse.Citation> citations = new ArrayList<>();
        for (RetrievedPassage p : r.passages()) {
            if (citedIds.contains(p.sourceId()))
                citations.add(new LessonResponse.Citation(p.sourceId(), p.chunk().documentId(), p.chunk().citationLabel(), p.chunk().warnings()));
        }
        String lessonId = UUID.randomUUID().toString();
        String msg = d.status() == ReviewPolicy.Status.AUTO_APPROVED ? "Lesson ready."
                : "Lesson drafted; a teacher should review it before use: " + String.join(", ", d.reasons()) + ".";
        LessonResponse resp = new LessonResponse(lessonId, d.status().name(), d.reasons(), msg, outcome.draft(), checked,
                citations, evidence, traceId);

        lessons.save(new LessonStore.Record(lessonId, teacherId, Instant.now(), req, resp,
                d.status() == ReviewPolicy.Status.AUTO_APPROVED ? LessonStore.ReviewState.AUTO_APPROVED : LessonStore.ReviewState.PENDING_REVIEW,
                null, null));
        int unsupported = (int) checks.stream().filter(c -> c.verdict() == GroundingChecker.Verdict.UNSUPPORTED).count();
        int weak = (int) checks.stream().filter(c -> c.verdict() == GroundingChecker.Verdict.WEAK).count();
        traces.record(new RequestTrace(traceId, teacherId, Instant.now(), req.topic(), mode.name(), r.latencyMs(), genMs, checkMs,
                ms(t0), calls, inTok, outTok, cost(inTok, outTok), ev.level().name(), ev.maxSimilarity(), ev.coverage(),
                d.status().name(), d.reasons(), checks.size(), unsupported, weak, false, null));
        if (d.status() == ReviewPolicy.Status.AUTO_APPROVED) cache.put(cacheKey, resp);
        return resp;
    }

    /** READY documents the teacher owns, optionally narrowed to the ids they asked for. */
    List<Document> permittedDocuments(String teacherId, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return docs.listForOwner(teacherId).stream().filter(d -> d.status() == DocumentStatus.READY).toList();
        }
        Map<String, Document> out = new LinkedHashMap<>();
        for (String id : requested) {
            // Same error for "doesn't exist" and "not yours": don't reveal other teachers' document ids.
            Document d = docs.findForOwner(id, teacherId).orElseThrow(() -> new DocumentNotAccessible(id));
            if (d.status() == DocumentStatus.READY) out.put(id, d);
        }
        return new ArrayList<>(out.values());
    }

    public void clearCache() {
        cache.clear();
    }

    private double cost(int in, int out) {
        ImboniProperties.Price p = props.pricing().get(props.llm().pricingKey());
        return p == null ? 0 : p.cost(in, out);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    public static class GenerationFailed extends RuntimeException {
        public final String traceId;

        public GenerationFailed(String msg, String traceId, Throwable cause) {
            super(msg, cause);
            this.traceId = traceId;
        }
    }

    public static class DocumentNotAccessible extends RuntimeException {
        public DocumentNotAccessible(String id) {
            super("document not found: " + id);
        }
    }
}
