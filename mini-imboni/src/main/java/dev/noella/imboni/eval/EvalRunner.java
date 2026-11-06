package dev.noella.imboni.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.noella.imboni.config.ImboniProperties;
import dev.noella.imboni.doc.Chunk;
import dev.noella.imboni.doc.Document;
import dev.noella.imboni.doc.DocumentStatus;
import dev.noella.imboni.doc.InMemoryChunkStore;
import dev.noella.imboni.doc.InMemoryDocumentStore;
import dev.noella.imboni.ingest.Chunker;
import dev.noella.imboni.ingest.ExtractionQualityChecker;
import dev.noella.imboni.ingest.Extractor;
import dev.noella.imboni.ingest.FixedWindowChunker;
import dev.noella.imboni.ingest.IngestionService;
import dev.noella.imboni.ingest.StructureAwareChunker;
import dev.noella.imboni.lesson.LessonRequest;
import dev.noella.imboni.lesson.LessonResponse;
import dev.noella.imboni.lesson.LessonService;
import dev.noella.imboni.lesson.LessonStore;
import dev.noella.imboni.lesson.ReviewPolicy;
import dev.noella.imboni.llm.AnthropicLlm;
import dev.noella.imboni.llm.LlmClient;
import dev.noella.imboni.llm.OfflineExtractiveLlm;
import dev.noella.imboni.llm.OpenAiChatLlm;
import dev.noella.imboni.ops.RequestTrace;
import dev.noella.imboni.ops.TraceStore;
import dev.noella.imboni.retrieval.Embedder;
import dev.noella.imboni.retrieval.EvidenceGate;
import dev.noella.imboni.retrieval.HashingEmbedder;
import dev.noella.imboni.retrieval.OpenAiEmbedder;
import dev.noella.imboni.retrieval.RetrievalMode;
import dev.noella.imboni.retrieval.RetrievedPassage;
import dev.noella.imboni.retrieval.Retriever;
import dev.noella.imboni.retrieval.RetryingEmbedder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Offline evaluation harness. Evaluates each stage separately (extraction → retrieval → evidence
 * gate → generation/grounding → review routing), plus ingestion reliability, and writes
 * {@code target/eval/report.md} and {@code results.json}.
 *
 * <pre>
 *   mvn -q compile exec:java                       # offline: hashing embeddings + extractive generator
 *   IMBONI_LLM=openai OPENAI_API_KEY=... mvn -q compile exec:java    # real model for generation
 *   IMBONI_EMBEDDING=openai ...                    # real embeddings for retrieval
 * </pre>
 */
public class EvalRunner {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int TOP_K = 5;

    record Relevant(String doc, String contains) {}

    record Question(String id, String teacher, String split, String category, boolean answerable, String topic,
                    String grade, List<Relevant> relevant, List<String> together) {}

    record CorpusFile(String teacher, String filename, byte[] bytes) {}

    /** A fully wired, in-memory instance of the system. */
    static class Env {
        InMemoryDocumentStore docs = new InMemoryDocumentStore();
        InMemoryChunkStore chunks = new InMemoryChunkStore();
        Map<String, String> docIdByStem = new HashMap<>();
        Map<String, String> stemByDocId = new HashMap<>();
        Retriever retriever;
        EvidenceGate gate;
        TraceStore traces = new TraceStore();
        LessonStore lessons = new LessonStore();
        LessonService service;
    }

    private final ImboniProperties props = ImboniProperties.defaults();
    private final Map<String, Object> results = new LinkedHashMap<>();
    private final StringBuilder md = new StringBuilder();
    private Embedder embedder;
    private String llmProvider;

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        Path out = Path.of(args.length > 0 ? args[0] : "target/eval");
        new EvalRunner().run(out);
    }

    public Map<String, Object> run(Path outDir) throws Exception {
        List<Question> questions = loadQuestions();
        List<CorpusFile> corpus = loadCorpus();
        String embProvider = System.getenv().getOrDefault("IMBONI_EMBEDDING", "hashing");
        llmProvider = System.getenv().getOrDefault("IMBONI_LLM", "offline");
        embedder = "openai".equals(embProvider)
                ? new RetryingEmbedder(new OpenAiEmbedder(System.getenv("OPENAI_API_KEY"), "text-embedding-3-small", 1536), 3, 500)
                : new HashingEmbedder(512);

        md.append("# mini-imboni evaluation report\n\n");
        md.append("Generated ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 16))
                .append(". Embeddings: `").append(embedder.modelName()).append("`. Generator: `").append(llmProvider).append("`.\n\n");
        if ("offline".equals(llmProvider)) {
            md.append("> **Read this first.** These numbers come from a small dummy corpus (8 documents, 2 teachers, 40 requests) ")
                    .append("and an offline *extractive* generator that copies source sentences. Retrieval, gate, extraction and ")
                    .append("reliability numbers are real measurements of this code. Groundedness of the offline generator is high ")
                    .append("by construction, so the meaningful generation-side number here is the checker's detection rate on ")
                    .append("injected errors (section 5). Run with `IMBONI_LLM=openai` for real-model groundedness, latency and cost.\n\n");
        }
        results.put("embedding", embedder.modelName());
        results.put("generator", llmProvider);
        long answerable = questions.stream().filter(Question::answerable).count();
        results.put("questions", Map.of("total", questions.size(), "answerable", answerable, "unanswerable", questions.size() - answerable));

        evalExtraction(corpus);
        Env structure = evalRetrieval(corpus, questions);
        evalGate(structure, questions);
        evalEndToEnd(corpus, questions);
        evalGroundingChecker(corpus, questions);
        evalIngestionReliability(corpus);

        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("report.md"), md.toString());
        Files.writeString(outDir.resolve("results.json"), JSON.writeValueAsString(results));
        System.out.println(md);
        System.out.println("Wrote " + outDir.resolve("report.md") + " and results.json");
        return results;
    }

    // ------------------------------------------------------------------------------------------
    // 1. Extraction
    // ------------------------------------------------------------------------------------------

    private void evalExtraction(List<CorpusFile> corpus) throws Exception {
        ExtractionQualityChecker checker = new ExtractionQualityChecker();
        JsonNode fixtures = JSON.readTree(resource("eval/extraction_fixtures.json"));
        int defective = 0, caught = 0, clean = 0, falseAlarm = 0;
        List<String[]> rows = new ArrayList<>();
        for (JsonNode f : fixtures) {
            Set<String> expected = new HashSet<>();
            f.path("expected").forEach(e -> expected.add(e.asText()));
            List<String> got = checker.check(f.path("text").asText(), f.path("startOfPage").asBoolean());
            boolean ok;
            if (expected.isEmpty()) {
                clean++;
                ok = got.isEmpty();
                if (!ok) falseAlarm++;
            } else {
                defective++;
                ok = got.containsAll(expected);
                if (ok) caught++;
            }
            rows.add(new String[]{f.path("id").asText(), String.join(", ", expected), String.join(", ", got), ok ? "✓" : "✗"});
        }

        // Equation preservation in the scanned worksheet.
        CorpusFile scan = corpus.stream().filter(c -> c.filename().contains("_scan")).findFirst().orElseThrow();
        String scanText = new Extractor().extract(scan.filename(), scan.bytes()).pages().stream()
                .map(p -> p.text()).collect(Collectors.joining("\n"));
        Map<String, String> truth = new LinkedHashMap<>();
        truth.put("v = u + at", "v=u+at");
        truth.put("s = ut + ½at²", "s=ut+½at²");
        truth.put("a = (v − u) / t", "a=(v-u)/t");
        truth.put("acceleration unit m/s²", "2m/s²");
        String norm = scanText.replaceAll("\\s+", "");
        int preserved = 0;
        List<String[]> eqRows = new ArrayList<>();
        for (var e : truth.entrySet()) {
            boolean p = norm.contains(e.getValue());
            if (p) preserved++;
            eqRows.add(new String[]{e.getKey(), p ? "preserved" : "damaged"});
        }

        md.append("## 1. Extraction quality checks\n\n");
        md.append("Hand-written fixtures of clean and OCR-damaged text. The checker should flag damage without flagging clean STEM text ")
                .append("(chemical formulas, fractions and percentages are the usual false alarms).\n\n");
        md.append(String.format("- Damaged fixtures caught: **%d / %d** (%s)%n", caught, defective, pct(caught, defective)));
        md.append(String.format("- Clean fixtures wrongly flagged: **%d / %d**%n", falseAlarm, clean));
        md.append(String.format("- Equations intact after OCR in the scanned worksheet: **%d / %d**%n%n", preserved, truth.size()));
        md.append(table(new String[]{"fixture", "expected", "flagged", ""}, rows));
        md.append("\n").append(table(new String[]{"scanned-worksheet ground truth", "after extraction"}, eqRows)).append("\n");
        results.put("extraction", Map.of("damaged_caught", caught, "damaged_total", defective, "clean_false_alarms", falseAlarm,
                "clean_total", clean, "scan_equations_preserved", preserved, "scan_equations_total", truth.size()));
    }

    // ------------------------------------------------------------------------------------------
    // 2. Retrieval
    // ------------------------------------------------------------------------------------------

    private Env evalRetrieval(List<CorpusFile> corpus, List<Question> questions) {
        List<Chunker> chunkers = List.of(new FixedWindowChunker(80), new StructureAwareChunker(180));
        List<Question> ans = questions.stream().filter(Question::answerable).toList();
        List<Question> together = ans.stream().filter(q -> q.together() != null && !q.together().isEmpty()).toList();
        List<String[]> rows = new ArrayList<>();
        Map<String, Object> res = new LinkedHashMap<>();
        Env production = null;
        int leaks = 0;
        for (Chunker chunker : chunkers) {
            Env env = build(corpus, chunker, new OfflineExtractiveLlm());
            if (chunker instanceof StructureAwareChunker) production = env;
            for (RetrievalMode mode : RetrievalMode.values()) {
                double hits = 0, rr = 0, complete = 0;
                for (Question q : questions) {
                    var r = env.retriever.retrieve(q.topic(), permitted(env, q.teacher()), mode, TOP_K);
                    for (RetrievedPassage p : r.passages()) if (!p.chunk().ownerId().equals(q.teacher())) leaks++;
                    if (!q.answerable()) continue;
                    int rank = firstRelevantRank(env, q, r.passages());
                    if (rank > 0) {
                        hits++;
                        rr += 1.0 / rank;
                    }
                    if (together.contains(q) && r.passages().stream().anyMatch(p -> containsAll(p.chunk().text(), q.together()))) complete++;
                }
                rows.add(new String[]{chunker.name(), mode.name(), String.valueOf(env.chunks.countAll()),
                        pct(hits, ans.size()), String.format("%.2f", rr / ans.size()), pct(complete, together.size())});
                res.put(chunker.name() + "/" + mode, Map.of("recall_at_5", hits / ans.size(), "mrr", rr / ans.size(),
                        "complete_evidence", complete / together.size(), "chunks", env.chunks.countAll()));
            }
        }
        // Per-category recall for the production configuration.
        Map<String, int[]> byCat = new TreeMap<>();
        for (Question q : ans) {
            var r = production.retriever.retrieve(q.topic(), permitted(production, q.teacher()), props.retrieval().mode(), TOP_K);
            int[] c = byCat.computeIfAbsent(q.category(), k -> new int[2]);
            c[1]++;
            if (firstRelevantRank(production, q, r.passages()) > 0) c[0]++;
        }

        md.append("## 2. Retrieval\n\n");
        md.append(String.format("%d answerable requests with labelled evidence. **Recall@5**: the right passage is in the top 5. ", ans.size()))
                .append("**MRR**: how high it ranks (1.0 = always first). **Complete evidence**: for the ")
                .append(together.size()).append(" requests where an explanation and its equation/table must arrive together, ")
                .append("a single top-5 passage contains both.\n\n");
        md.append(table(new String[]{"chunking", "search", "passages", "recall@5", "MRR", "complete evidence"}, rows));
        md.append("\nProduction config (structure-aware + ").append(props.retrieval().mode()).append(") recall@5 by request type: ");
        md.append(byCat.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1]).collect(Collectors.joining(", ")));
        md.append(String.format("%n%n**Permission leaks** (passages from another teacher's documents returned, across all %d runs × %d requests): **%d**%n%n",
                chunkers.size() * RetrievalMode.values().length, questions.size(), leaks));
        res.put("permission_leaks", leaks);
        results.put("retrieval", res);
        return production;
    }

    private int firstRelevantRank(Env env, Question q, List<RetrievedPassage> passages) {
        for (int i = 0; i < passages.size(); i++) {
            Chunk c = passages.get(i).chunk();
            for (Relevant rel : q.relevant()) {
                if (c.documentId().equals(env.docIdByStem.get(rel.doc())) && normalizeWs(c.text()).contains(normalizeWs(rel.contains())))
                    return i + 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------
    // 3. Evidence gate
    // ------------------------------------------------------------------------------------------

    private void evalGate(Env env, List<Question> questions) {
        double cfgSim = props.evidence().minSimilarity(), cfgCov = props.evidence().minCoverage();
        Map<String, Retriever.Result> retrieved = new HashMap<>();
        for (Question q : questions) {
            retrieved.put(q.id(), env.retriever.retrieve(q.topic() , permitted(env, q.teacher()), props.retrieval().mode(), TOP_K));
        }
        // Sweep on dev. Cost: a false answer (making up a lesson the documents don't support) counts double
        // a false refusal (teacher has to rephrase / upload).
        List<Question> dev = questions.stream().filter(q -> q.split().equals("dev")).toList();
        List<Question> test = questions.stream().filter(q -> q.split().equals("test")).toList();
        double bestCost = Double.MAX_VALUE, bestSim = cfgSim, bestCov = cfgCov;
        List<String[]> sweep = new ArrayList<>();
        for (double sim : new double[]{0.05, 0.12, 0.2, 0.3}) {
            for (double cov : new double[]{0.3, 0.4, 0.5, 0.6, 0.7}) {
                int[] c = confusion(env, dev, retrieved, sim, cov);
                double cost = 2.0 * c[3] + c[1];
                sweep.add(new String[]{String.valueOf(sim), String.valueOf(cov), String.valueOf(c[1]), String.valueOf(c[3]), String.valueOf((int) cost)});
                if (cost <= bestCost) { // ties → stricter thresholds (a false answer costs more than a refusal)
                    bestCost = cost;
                    bestSim = sim;
                    bestCov = cov;
                }
            }
        }
        int[] testCfg = confusion(env, test, retrieved, cfgSim, cfgCov);
        int[] allCfg = confusion(env, questions, retrieved, cfgSim, cfgCov);
        int[] testBest = confusion(env, test, retrieved, bestSim, bestCov);

        List<String[]> misses = new ArrayList<>();
        for (Question q : questions) {
            var a = env.gate.assess(q.topic(), permitted(env, q.teacher()), retrieved.get(q.id()), cfgSim, cfgCov);
            boolean refused = a.level() == EvidenceGate.Level.NONE;
            if (refused == q.answerable()) {
                misses.add(new String[]{q.id(), q.teacher(), q.topic(), q.answerable() ? "false refusal" : "false answer",
                        String.format("%.2f", a.coverage()), String.format("%.2f", a.maxSimilarity())});
            }
        }

        md.append("## 3. Evidence gate (refuse when the documents don't support a lesson)\n\n");
        md.append(String.format("Configured thresholds: min similarity %.2f, min IDF-weighted term coverage %.2f. ", cfgSim, cfgCov))
                .append(String.format("Best on the dev split by sweep: similarity %.2f, coverage %.2f.%n%n", bestSim, bestCov));
        md.append(table(new String[]{"split", "answerable answered", "false refusals", "unanswerable refused", "false answers"},
                List.of(row("test (configured)", testCfg), row("all (configured)", allCfg),
                        row(String.format("test (dev-best %.2f / %.2f)", bestSim, bestCov), testBest))));
        md.append("\nMisclassified requests (all splits):\n\n");
        md.append(misses.isEmpty() ? "_none_\n" : table(new String[]{"id", "teacher", "request", "error", "coverage", "max sim"}, misses));
        md.append("\n<details><summary>Dev-split threshold sweep</summary>\n\n")
                .append(table(new String[]{"min sim", "min coverage", "false refusals", "false answers", "cost (2×FA + FR)"}, sweep))
                .append("\n</details>\n\n");
        results.put("gate", Map.of("min_similarity", cfgSim, "min_coverage", cfgCov,
                "test", Map.of("answered", testCfg[0], "false_refusals", testCfg[1], "refused", testCfg[2], "false_answers", testCfg[3]),
                "all", Map.of("answered", allCfg[0], "false_refusals", allCfg[1], "refused", allCfg[2], "false_answers", allCfg[3]),
                "dev_best", Map.of("min_similarity", bestSim, "min_coverage", bestCov)));
    }

    /** [answerable answered, answerable refused, unanswerable refused, unanswerable answered] */
    private int[] confusion(Env env, List<Question> qs, Map<String, Retriever.Result> retrieved, double sim, double cov) {
        int[] c = new int[4];
        for (Question q : qs) {
            var a = env.gate.assess(q.topic(), permitted(env, q.teacher()), retrieved.get(q.id()), sim, cov);
            boolean refused = a.level() == EvidenceGate.Level.NONE;
            if (q.answerable()) c[refused ? 1 : 0]++;
            else c[refused ? 2 : 3]++;
        }
        return c;
    }

    private static String[] row(String name, int[] c) {
        return new String[]{name, (c[0]) + "/" + (c[0] + c[1]), String.valueOf(c[1]), c[2] + "/" + (c[2] + c[3]), String.valueOf(c[3])};
    }

    // ------------------------------------------------------------------------------------------
    // 4. End to end: generation, grounding, review routing, latency, cost
    // ------------------------------------------------------------------------------------------

    private void evalEndToEnd(List<CorpusFile> corpus, List<Question> questions) {
        Env env = build(corpus, new StructureAwareChunker(180), llm());
        int facts = 0, supported = 0, weak = 0, unsupported = 0, invalid = 0, crossTenantAnswered = 0, errors = 0;
        Map<String, Integer> decisions = new TreeMap<>();
        for (Question q : questions) {
            try {
                LessonResponse r = env.service.generate(q.teacher(), request(q));
                decisions.merge(r.status(), 1, Integer::sum);
                if (q.category().equals("cross_tenant") && !r.status().equals("REFUSED")) crossTenantAnswered++;
                if (r.reviewReasons().contains("invalid_structure") || r.reviewReasons().contains("unparseable_output")) invalid++;
                for (var s : r.statements()) {
                    if (!"fact".equalsIgnoreCase(s.kind())) continue;
                    facts++;
                    switch (s.verdict()) {
                        case "SUPPORTED" -> supported++;
                        case "WEAK" -> weak++;
                        default -> unsupported++;
                    }
                }
            } catch (LessonService.GenerationFailed e) {
                errors++;
                decisions.merge("ERROR", 1, Integer::sum);
            }
        }
        // Cache: repeat one request that was auto-approved.
        String cacheNote = "no auto-approved lesson to repeat";
        for (Question q : questions) {
            var first = env.traces.all().stream().filter(t -> t.topic().equals(q.topic()) && "AUTO_APPROVED".equals(t.decision())).findFirst();
            if (first.isPresent()) {
                env.service.generate(q.teacher(), request(q));
                RequestTrace again = env.traces.all().get(env.traces.all().size() - 1);
                cacheNote = String.format("repeat of \"%s\": cache hit=%s, %d ms vs %d ms, 0 model calls", q.topic(), again.cacheHit(),
                        again.totalMs(), first.get().totalMs());
                break;
            }
        }
        // Authorization at the API boundary: bob asks for alice's document by id.
        String authNote;
        try {
            env.service.generate("bob", new LessonRequest("photosynthesis", "Grade 7", 40, null,
                    List.of(env.docIdByStem.get("photosynthesis"))));
            authNote = "FAILED: bob could request alice's document";
        } catch (LessonService.DocumentNotAccessible e) {
            authNote = "bob requesting alice's document id → rejected as not found (no existence leak)";
        }

        // Which real corpus passages carry extraction warnings (these drive "cites_flagged_source").
        List<String[]> flaggedRows = new ArrayList<>();
        for (Document d : env.docs.listForOwner("alice")) addFlagged(env, d, flaggedRows);
        for (Document d : env.docs.listForOwner("bob")) addFlagged(env, d, flaggedRows);

        List<RequestTrace> ts = env.traces.all().stream().filter(t -> !t.cacheHit()).toList();
        TraceStore.Summary s = TraceStore.summarize(ts);
        List<RequestTrace> answered = ts.stream().filter(t -> t.llmCalls() > 0).toList();
        double avgIn = answered.stream().mapToInt(RequestTrace::inputTokens).average().orElse(0);
        double avgOut = answered.stream().mapToInt(RequestTrace::outputTokens).average().orElse(0);
        double avgCalls = answered.stream().mapToInt(RequestTrace::llmCalls).average().orElse(0);
        long reviewed = answered.stream().filter(t -> "NEEDS_REVIEW".equals(t.decision())).count();
        Map<String, Integer> reasons = new TreeMap<>();
        answered.forEach(t -> t.reasons().forEach(x -> reasons.merge(x, 1, Integer::sum)));

        md.append("## 4. End to end (").append(llmProvider).append(" generator)\n\n");
        md.append(String.format("- Outcomes for %d requests: %s%n", questions.size(), decisions));
        md.append(String.format("- Fact statements supported by their cited passages: **%s** (%d/%d; %d weak, %d unsupported)%n",
                pct(supported, facts), supported, facts, weak, unsupported));
        md.append(String.format("- Output failed the JSON/citation contract after one repair attempt: %d; generation errors: %d%n", invalid, errors));
        md.append(String.format("- Sent to human review: **%d of %d** generated lessons. Reasons: %s%n", reviewed, answered.size(), reasons));
        md.append(String.format("- Cross-teacher requests that produced a lesson: %d%n", crossTenantAnswered));
        md.append("- ").append(authNote).append('\n');
        md.append("- ").append(cacheNote).append('\n');
        md.append(String.format("- Latency (end to end, non-cached): p50 %d ms, p95 %d ms; generation p50 %d ms, p95 %d ms%n",
                s.p50TotalMs(), s.p95TotalMs(), s.p50GenerationMs(), s.p95GenerationMs()));
        md.append(String.format("- Tokens per generated lesson: %.0f in / %.0f out, %.2f model calls on average%n", avgIn, avgOut, avgCalls));
        md.append("- Cost per generated lesson at list prices for these token counts: ");
        md.append(props.pricing().entrySet().stream().filter(e -> !e.getKey().startsWith("offline"))
                .map(e -> String.format("%s $%.5f", e.getKey(), e.getValue().cost((int) avgIn, (int) avgOut)))
                .collect(Collectors.joining(", ")));
        if ("offline".equals(llmProvider)) md.append(" _(projection: token counts are estimated from the offline prompt/output)_");
        md.append("\n\nCorpus passages carrying extraction warnings (any lesson citing one goes to review):\n\n");
        md.append(flaggedRows.isEmpty() ? "_none_\n" : table(new String[]{"document", "passage", "page", "warnings"}, flaggedRows));
        md.append('\n');

        Map<String, Object> e2e = new LinkedHashMap<>();
        e2e.put("decisions", decisions);
        e2e.put("fact_statements", facts);
        e2e.put("supported", supported);
        e2e.put("weak", weak);
        e2e.put("unsupported", unsupported);
        e2e.put("invalid_structure", invalid);
        e2e.put("errors", errors);
        e2e.put("review_rate_of_generated", answered.isEmpty() ? 0 : reviewed * 1.0 / answered.size());
        e2e.put("review_reasons", reasons);
        e2e.put("cross_tenant_answered", crossTenantAnswered);
        e2e.put("latency_ms", Map.of("p50", s.p50TotalMs(), "p95", s.p95TotalMs(), "gen_p50", s.p50GenerationMs(), "gen_p95", s.p95GenerationMs()));
        e2e.put("avg_tokens", Map.of("in", avgIn, "out", avgOut, "calls", avgCalls));
        results.put("end_to_end", e2e);
    }

    // ------------------------------------------------------------------------------------------
    // 5. Grounding checker: does it catch planted errors?
    // ------------------------------------------------------------------------------------------

    private void evalGroundingChecker(List<CorpusFile> corpus, List<Question> questions) {
        NoisyLlm noisy = new NoisyLlm(new OfflineExtractiveLlm());
        Env env = build(corpus, new StructureAwareChunker(180), noisy);
        Map<NoisyLlm.ErrorType, int[]> byType = new LinkedHashMap<>();
        for (NoisyLlm.ErrorType t : NoisyLlm.ErrorType.values()) byType.put(t, new int[2]);
        int cleanFacts = 0, cleanFlagged = 0, lessonsWithErrors = 0, routedToReview = 0;
        List<String[]> missed = new ArrayList<>();
        for (Question q : questions) {
            if (!q.answerable()) continue;
            env.service.clearCache();
            LessonResponse r = env.service.generate(q.teacher(), request(q));
            if (r.status().equals("REFUSED")) continue;
            List<NoisyLlm.Injection> inj = noisy.lastInjections();
            if (inj.isEmpty()) continue;
            lessonsWithErrors++;
            if (r.status().equals(ReviewPolicy.Status.NEEDS_REVIEW.name())) routedToReview++;
            Set<String> injected = inj.stream().map(NoisyLlm.Injection::text).collect(Collectors.toSet());
            for (NoisyLlm.Injection i : inj) {
                var st = r.statements().stream().filter(x -> x.text().equals(i.text())).findFirst();
                int[] c = byType.get(i.type());
                c[1]++;
                if (st.isPresent() && !st.get().verdict().equals("SUPPORTED")) c[0]++;
                else missed.add(new String[]{q.id(), i.type().name(), i.text(), st.map(x -> x.reason()).orElse("?")});
            }
            for (var st : r.statements()) {
                if (!"fact".equalsIgnoreCase(st.kind()) || injected.contains(st.text())) continue;
                cleanFacts++;
                if (!st.verdict().equals("SUPPORTED")) cleanFlagged++;
            }
        }
        md.append("## 5. Grounding checker vs planted errors\n\n");
        md.append("Each generated lesson gets known errors planted in it: a fabricated fact with a real citation, a changed number, ")
                .append("a flipped negation, and a term swap (one key term replaced by another term from the same material). ")
                .append("A planted error counts as caught if the checker marks it weak or unsupported. ")
                .append("Note: the offline generator copies sentences verbatim, so the false-flag rate below is a floor; ")
                .append("a real model paraphrases, which will raise it. Measure that with `IMBONI_LLM=openai`.\n\n");
        List<String[]> rows = new ArrayList<>();
        Map<String, Object> res = new LinkedHashMap<>();
        byType.forEach((t, c) -> {
            rows.add(new String[]{t.name(), c[0] + "/" + c[1], pct(c[0], c[1])});
            res.put(t.name().toLowerCase(Locale.ROOT), Map.of("caught", c[0], "total", c[1]));
        });
        md.append(table(new String[]{"planted error", "caught", "rate"}, rows));
        md.append(String.format("%n- Correct fact statements wrongly flagged: **%d / %d** (%s)%n", cleanFlagged, cleanFacts, pct(cleanFlagged, cleanFacts)));
        md.append(String.format("- Lessons containing planted errors that were routed to human review: **%d / %d**%n%n", routedToReview, lessonsWithErrors));
        if (!missed.isEmpty()) {
            md.append("Missed (these are the checker's known blind spots):\n\n")
                    .append(table(new String[]{"id", "type", "statement", "checker said"}, missed.stream().limit(10).toList())).append('\n');
        }
        res.put("clean_facts", cleanFacts);
        res.put("clean_flagged", cleanFlagged);
        res.put("lessons_with_errors", lessonsWithErrors);
        res.put("routed_to_review", routedToReview);
        results.put("grounding_checker", res);
    }

    // ------------------------------------------------------------------------------------------
    // 6. Ingestion reliability
    // ------------------------------------------------------------------------------------------

    private void evalIngestionReliability(List<CorpusFile> corpus) {
        CorpusFile file = corpus.stream().filter(c -> c.filename().startsWith("photosynthesis")).findFirst().orElseThrow();
        Chunker chunker = new StructureAwareChunker(60); // small passages → several embedding batches
        List<String[]> rows = new ArrayList<>();
        Map<String, Object> res = new LinkedHashMap<>();

        // Baseline.
        InMemoryDocumentStore d0 = new InMemoryDocumentStore();
        InMemoryChunkStore c0 = new InMemoryChunkStore();
        Document base = new IngestionService(d0, c0, new Extractor(), chunker, new ExtractionQualityChecker(), embedder, Runnable::run, 2)
                .submit("alice", file.filename(), "photosynthesis", file.bytes());
        int baseline = c0.chunksOf(base.id()).size();

        // a) One transient failure, absorbed by the retry budget.
        InMemoryDocumentStore d1 = new InMemoryDocumentStore();
        InMemoryChunkStore c1 = new InMemoryChunkStore();
        FlakyEmbedder f1 = new FlakyEmbedder(embedder, Set.of(2));
        Document a = new IngestionService(d1, c1, new Extractor(), chunker, new ExtractionQualityChecker(),
                new RetryingEmbedder(f1, 3, 1), Runnable::run, 2).submit("alice", file.filename(), "photosynthesis", file.bytes());
        a = d1.findById(a.id()).orElseThrow();
        rows.add(new String[]{"transient embedding error on batch 2", a.status() + " after " + a.attempts() + " attempt(s)",
                c1.chunksOf(a.id()).size() + " / " + baseline});

        // b) Persistent failure halfway through, then reprocess.
        InMemoryDocumentStore d2 = new InMemoryDocumentStore();
        InMemoryChunkStore c2 = new InMemoryChunkStore();
        FlakyEmbedder f2 = new FlakyEmbedder(embedder, Set.of(3, 4, 5));
        IngestionService svc = new IngestionService(d2, c2, new Extractor(), chunker, new ExtractionQualityChecker(),
                new RetryingEmbedder(f2, 3, 1), Runnable::run, 2);
        Document b = svc.submit("alice", file.filename(), "photosynthesis", file.bytes());
        Document failed = d2.findById(b.id()).orElseThrow();
        int afterFail = c2.chunksOf(b.id()).size();
        svc.reprocess(b.id(), "alice");
        Document recovered = d2.findById(b.id()).orElseThrow();
        List<Chunk> after = c2.chunksOf(b.id());
        long unique = after.stream().map(Chunk::id).distinct().count();
        rows.add(new String[]{"embedding down for batch 2 (all 3 retries)", failed.status() + ", " + afterFail + " partial passages stored", "—"});
        rows.add(new String[]{"→ reprocess after recovery", recovered.status() + " after " + recovered.attempts() + " attempts",
                after.size() + " / " + baseline + " (" + (after.size() - unique) + " duplicates)"});

        // c) Duplicate upload.
        Document dup = svc.submit("alice", file.filename(), "photosynthesis", file.bytes());
        rows.add(new String[]{"same file uploaded again", dup.id().equals(b.id()) ? "returned existing document" : "created a duplicate",
                c2.chunksOf(b.id()).size() + " / " + baseline});

        md.append("## 6. Ingestion reliability\n\n");
        md.append(table(new String[]{"scenario", "result", "passages stored / expected"}, rows)).append('\n');
        res.put("transient_ok", a.status() == DocumentStatus.READY && c1.chunksOf(a.id()).size() == baseline);
        res.put("partial_after_failure", afterFail);
        res.put("recovered", recovered.status() == DocumentStatus.READY && after.size() == baseline && unique == after.size());
        res.put("duplicate_upload_deduped", dup.id().equals(b.id()));
        results.put("ingestion", res);
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private Env build(List<CorpusFile> corpus, Chunker chunker, LlmClient llm) {
        Env env = new Env();
        IngestionService ingest = new IngestionService(env.docs, env.chunks, new Extractor(), chunker,
                new ExtractionQualityChecker(), embedder, Runnable::run);
        for (CorpusFile f : corpus) {
            String stem = f.filename().replaceFirst("\\.[^.]+$", "");
            Document d = ingest.submit(f.teacher(), f.filename(), stem, f.bytes());
            Document done = env.docs.findById(d.id()).orElseThrow();
            if (done.status() != DocumentStatus.READY) throw new IllegalStateException("ingestion failed: " + done.error());
            env.docIdByStem.put(stem, d.id());
            env.stemByDocId.put(d.id(), stem);
        }
        env.retriever = new Retriever(env.chunks, embedder, props.retrieval().candidatePool(), props.retrieval().rrfK());
        env.gate = new EvidenceGate(env.chunks, props.evidence().minSimilarity(), props.evidence().minCoverage(),
                props.review().weakEvidenceCoverage());
        env.service = new LessonService(env.docs, env.retriever, env.gate, llm, new ReviewPolicy(props.review()),
                env.lessons, env.traces, props);
        return env;
    }

    private static void addFlagged(Env env, Document d, List<String[]> rows) {
        for (Chunk c : env.chunks.chunksOf(d.id())) {
            if (!c.warnings().isEmpty())
                rows.add(new String[]{d.title(), "#" + c.ordinal() + " " + (c.section().isBlank() ? "" : c.section()), String.valueOf(c.page()),
                        String.join(", ", c.warnings())});
        }
    }

    private LlmClient llm() {
        Duration t = Duration.ofSeconds(60);
        return switch (llmProvider) {
            case "openai" -> new OpenAiChatLlm(System.getenv("OPENAI_API_KEY"),
                    System.getenv().getOrDefault("IMBONI_LLM_MODEL", "gpt-4o-mini"), t, 2);
            case "anthropic" -> new AnthropicLlm(System.getenv("ANTHROPIC_API_KEY"),
                    System.getenv().getOrDefault("IMBONI_LLM_MODEL", "claude-haiku-4-5"), t, 2);
            default -> new OfflineExtractiveLlm();
        };
    }

    private static List<String> permitted(Env env, String teacher) {
        return env.docs.listForOwner(teacher).stream().filter(d -> d.status() == DocumentStatus.READY).map(Document::id).toList();
    }

    private static LessonRequest request(Question q) {
        return new LessonRequest(q.topic(), q.grade(), 45, null, null);
    }

    private static boolean containsAll(String text, List<String> phrases) {
        String t = normalizeWs(text);
        return phrases.stream().allMatch(p -> t.contains(normalizeWs(p)));
    }

    private static String normalizeWs(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static List<Question> loadQuestions() throws Exception {
        List<Question> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(resource("eval/questions.json"))) {
            List<Relevant> rel = new ArrayList<>();
            n.path("relevant").forEach(r -> rel.add(new Relevant(r.path("doc").asText(), r.path("contains").asText())));
            List<String> tog = new ArrayList<>();
            n.path("together").forEach(x -> tog.add(x.asText()));
            out.add(new Question(n.path("id").asText(), n.path("teacher").asText(), n.path("split").asText(),
                    n.path("category").asText(), n.path("answerable").asBoolean(), n.path("topic").asText(),
                    n.path("grade").asText(), rel, tog));
        }
        return out;
    }

    static List<CorpusFile> loadCorpus() throws Exception {
        List<CorpusFile> out = new ArrayList<>();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:corpus/*/*");
        for (Resource r : resources) {
            String uri = r.getURI().toString();
            String[] parts = uri.split("/");
            try (InputStream in = r.getInputStream()) {
                out.add(new CorpusFile(parts[parts.length - 2], parts[parts.length - 1], in.readAllBytes()));
            }
        }
        out.sort((a, b) -> (a.teacher() + a.filename()).compareTo(b.teacher() + b.filename()));
        if (out.isEmpty()) throw new IllegalStateException("corpus not found on classpath");
        return out;
    }

    private static InputStream resource(String path) {
        InputStream in = EvalRunner.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IllegalStateException("missing resource " + path);
        return in;
    }

    private static String pct(double a, double b) {
        return b == 0 ? "n/a" : Math.round(100.0 * a / b) + "%";
    }

    private static String table(String[] header, List<String[]> rows) {
        StringBuilder sb = new StringBuilder("| ").append(String.join(" | ", header)).append(" |\n|");
        for (int i = 0; i < header.length; i++) sb.append("---|");
        sb.append('\n');
        for (String[] r : rows) {
            sb.append("| ");
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(r[i].replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }
}
