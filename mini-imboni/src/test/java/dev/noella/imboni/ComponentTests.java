package dev.noella.imboni;

import dev.noella.imboni.config.ImboniProperties;
import dev.noella.imboni.doc.Chunk;
import dev.noella.imboni.doc.Document;
import dev.noella.imboni.doc.DocumentStatus;
import dev.noella.imboni.doc.InMemoryChunkStore;
import dev.noella.imboni.doc.InMemoryDocumentStore;
import dev.noella.imboni.eval.FlakyEmbedder;
import dev.noella.imboni.ingest.Chunker;
import dev.noella.imboni.ingest.ExtractedDocument;
import dev.noella.imboni.ingest.ExtractionQualityChecker;
import dev.noella.imboni.ingest.Extractor;
import dev.noella.imboni.ingest.IngestionService;
import dev.noella.imboni.ingest.StructureAwareChunker;
import dev.noella.imboni.lesson.GroundingChecker;
import dev.noella.imboni.lesson.LessonDraft;
import dev.noella.imboni.lesson.LessonRequest;
import dev.noella.imboni.lesson.LessonResponse;
import dev.noella.imboni.lesson.LessonService;
import dev.noella.imboni.lesson.LessonStore;
import dev.noella.imboni.lesson.ReviewPolicy;
import dev.noella.imboni.lesson.StructureValidator;
import dev.noella.imboni.llm.LlmClient;
import dev.noella.imboni.llm.OfflineExtractiveLlm;
import dev.noella.imboni.ops.TraceStore;
import dev.noella.imboni.retrieval.EvidenceGate;
import dev.noella.imboni.retrieval.HashingEmbedder;
import dev.noella.imboni.retrieval.RetrievedPassage;
import dev.noella.imboni.retrieval.Retriever;
import dev.noella.imboni.retrieval.RetryingEmbedder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComponentTests {

    static final String NEWTON = """
            # Newton's Laws

            ## Second law

            The acceleration of an object depends on the resultant force and on its mass. This relationship is written as:

            F = m × a

            where F is the force in newtons.

            ## Worked example

            A trolley of mass 2 kg is pushed with a resultant force of 10 N. Its acceleration is 5 m/s². A 5 kg trolley would accelerate at only 2 m/s².

            ## Third law

            When object A exerts a force on object B, object B exerts an equal and opposite force on object A.
            """;

    static final String WATER = """
            # Water cycle

            Heat from the Sun causes water in oceans to evaporate. Water vapour cools and condenses into droplets that form clouds.
            """;

    @Nested
    class Extraction {
        final ExtractionQualityChecker checker = new ExtractionQualityChecker();

        @Test
        void cleanChemistryAndFractionsAreNotFlagged() {
            assertThat(checker.check("Balancing it gives:\n2H2 + O2 → 2H2O", false)).isEmpty();
            assertThat(checker.check("6CO2 + 6H2O → C6H12O6 + 6O2", false)).isEmpty();
            assertThat(checker.check("1/4 + 2/4 = 3/4", false)).isEmpty();
        }

        @Test
        void ocrDamageIsFlagged() {
            assertThat(checker.check("s = ut + l/2 at2", false)).contains("suspicious_equation");
            assertThat(checker.check("a = (v - u / t", false)).contains("suspicious_equation");
            assertThat(checker.check("| a | b |\n| 1 | 2 | |", false)).contains("broken_table");
            assertThat(checker.check("| a | b |\n| 1 | 2 |", true)).contains("table_without_heading");
            assertThat(checker.check("speed is 2 m/s�", false)).contains("garbled_chars");
        }

        @Test
        void textPagesAndScanMarkerAreParsed() throws Exception {
            ExtractedDocument d = new Extractor().extract("w.txt",
                    "<!-- source: scan -->\nfirst page\n--- page 2 ---\nsecond page".getBytes(StandardCharsets.UTF_8));
            assertThat(d.scanned()).isTrue();
            assertThat(d.pages()).extracting(ExtractedDocument.Page::number).containsExactly(1, 2);
        }

        @Test
        void unsupportedFileTypeIsRejected() {
            assertThatThrownBy(() -> new Extractor().extract("x.docx", new byte[1]))
                    .isInstanceOf(Extractor.UnsupportedDocumentException.class);
        }
    }

    @Nested
    class Chunking {
        @Test
        void equationStaysWithItsExplanationAndSectionIsKept() throws Exception {
            var drafts = new StructureAwareChunker(180).chunk(new Extractor().extract("n.md", NEWTON.getBytes(StandardCharsets.UTF_8)));
            Chunker.Draft eq = drafts.stream().filter(d -> d.text().contains("F = m × a")).findFirst().orElseThrow();
            assertThat(eq.text()).contains("relationship is written as").contains("where F is the force");
            assertThat(eq.section()).isEqualTo("Second law");
        }

        @Test
        void tableStaysWithItsCaption() throws Exception {
            String md = "## Results\n\nThe table below shows the results:\n\n| t | v |\n|---|---|\n| 1 | 3 |\n";
            var drafts = new StructureAwareChunker(180).chunk(new Extractor().extract("t.md", md.getBytes(StandardCharsets.UTF_8)));
            assertThat(drafts).hasSize(1);
            assertThat(drafts.get(0).text()).contains("table below").contains("| 1 | 3 |");
        }
    }

    @Nested
    class Grounding {
        final GroundingChecker checker = new GroundingChecker();
        final Chunk chunk = new Chunk("c1", "d1", "alice", "Newton", 0, "Worked example", 1,
                "A trolley of mass 2 kg is pushed with a resultant force of 10 N. Its acceleration is 5 m/s². "
                        + "A 5 kg trolley would accelerate at only 2 m/s². Mass does not change from place to place.",
                List.of(), null);
        final List<RetrievedPassage> passages = List.of(new RetrievedPassage("S1", chunk, 0.9, 1, 1));

        GroundingChecker.Verdict verdict(String text, String kind, List<String> sources) {
            var draft = new LessonDraft("t", Map.of("key_ideas", List.of(new LessonDraft.Statement(text, sources, kind))), List.of());
            return checker.check(draft, passages).get(0).verdict();
        }

        @Test
        void verbatimFactIsSupported() {
            assertThat(verdict("A trolley of mass 2 kg is pushed with a resultant force of 10 N.", "fact", List.of("S1")))
                    .isEqualTo(GroundingChecker.Verdict.SUPPORTED);
        }

        @Test
        void changedNumberIsCaughtEvenIfTheNewNumberAppearsElsewhere() {
            // 5 appears elsewhere in the passage; v1 of the check missed this.
            assertThat(verdict("A trolley of mass 5 kg is pushed with a resultant force of 10 N.", "fact", List.of("S1")))
                    .isEqualTo(GroundingChecker.Verdict.UNSUPPORTED);
        }

        @Test
        void flippedNegationIsCaught() {
            assertThat(verdict("Mass does change from place to place.", "fact", List.of("S1")))
                    .isEqualTo(GroundingChecker.Verdict.UNSUPPORTED);
        }

        @Test
        void fabricatedFactIsCaught() {
            assertThat(verdict("Engineers rely on this rule when designing bridges and aircraft wings.", "fact", List.of("S1")))
                    .isEqualTo(GroundingChecker.Verdict.UNSUPPORTED);
        }

        @Test
        void instructionMayUseNewWordsButNotNewNumbers() {
            assertThat(verdict("In pairs, students explain the example to each other.", "instruction", List.of("S1")))
                    .isEqualTo(GroundingChecker.Verdict.SUPPORTED);
            assertThat(verdict("Students repeat the experiment with a 7 kg trolley.", "instruction", List.of("S1")))
                    .isEqualTo(GroundingChecker.Verdict.UNSUPPORTED);
        }
    }

    @Nested
    class Structure {
        final StructureValidator v = new StructureValidator();

        @Test
        void rejectsUnknownSourcesMissingSectionsAndBadJson() {
            assertThat(v.validate("not json", Set.of("S1")).draft()).isNull();
            var out = v.validate("""
                    {"title":"x","sections":{"objectives":[{"text":"a","sources":["S9"],"kind":"instruction"}]}}""", Set.of("S1"));
            assertThat(out.errors()).anyMatch(e -> e.contains("unknown source S9"))
                    .anyMatch(e -> e.contains("missing or empty section: key_ideas"));
        }

        @Test
        void acceptsFencedJson() {
            String ok = """
                    ```json
                    {"title":"x","sections":{
                     "objectives":[{"text":"a","sources":["S1"],"kind":"instruction"}],
                     "key_ideas":[{"text":"b","sources":["S1"],"kind":"fact"}],
                     "activity":[{"text":"c","sources":["S1"],"kind":"instruction"}],
                     "check_for_understanding":[{"text":"d","sources":["S1"],"kind":"instruction"}]}}
                    ```""";
            assertThat(v.validate(ok, Set.of("S1")).valid()).isTrue();
        }
    }

    /** Small in-memory system with two teachers. */
    static class Sys {
        final InMemoryDocumentStore docs = new InMemoryDocumentStore();
        final InMemoryChunkStore chunks = new InMemoryChunkStore();
        final HashingEmbedder emb = new HashingEmbedder(512);
        final IngestionService ingest = new IngestionService(docs, chunks, new Extractor(), new StructureAwareChunker(180),
                new ExtractionQualityChecker(), emb, Runnable::run);
        final ImboniProperties props = ImboniProperties.defaults();
        final TraceStore traces = new TraceStore();
        final LessonStore lessons = new LessonStore();
        final Retriever retriever = new Retriever(chunks, emb, 20, 60);
        final EvidenceGate gate = new EvidenceGate(chunks, 0.12, 0.5, 0.75);
        String newtonId, waterId;

        Sys() {
            newtonId = ingest.submit("alice", "newton.md", "Newton", NEWTON.getBytes(StandardCharsets.UTF_8)).id();
            waterId = ingest.submit("bob", "water.md", "Water", WATER.getBytes(StandardCharsets.UTF_8)).id();
        }

        LessonService service(LlmClient llm) {
            return new LessonService(docs, retriever, gate, llm, new ReviewPolicy(props.review()), lessons, traces, props);
        }
    }

    @Nested
    class Lessons {
        @Test
        void answersFromOwnDocumentsWithCitations() {
            Sys s = new Sys();
            LessonResponse r = s.service(new OfflineExtractiveLlm()).generate("alice",
                    new LessonRequest("force mass and acceleration", "Grade 9", 45, null, null));
            assertThat(r.status()).isIn("AUTO_APPROVED", "NEEDS_REVIEW");
            assertThat(r.citations()).isNotEmpty().allMatch(c -> c.documentId().equals(s.newtonId));
            assertThat(r.statements()).filteredOn(x -> x.kind().equals("fact")).allMatch(x -> x.verdict().equals("SUPPORTED"));
        }

        @Test
        void refusesWhenDocumentsDontCoverTheTopic() {
            Sys s = new Sys();
            LessonResponse r = s.service(new OfflineExtractiveLlm()).generate("alice",
                    new LessonRequest("plate tectonics and earthquakes", "Grade 7", 45, null, null));
            assertThat(r.status()).isEqualTo("REFUSED");
            assertThat(r.message()).contains("don't cover");
        }

        @Test
        void neverUsesAnotherTeachersDocuments() {
            Sys s = new Sys();
            // The water cycle exists, but only in bob's documents.
            LessonResponse r = s.service(new OfflineExtractiveLlm()).generate("alice",
                    new LessonRequest("the water cycle evaporation and clouds", "Grade 5", 45, null, null));
            assertThat(r.status()).isEqualTo("REFUSED");
            assertThatThrownBy(() -> s.service(new OfflineExtractiveLlm()).generate("alice",
                    new LessonRequest("water", "Grade 5", 45, null, List.of(s.waterId))))
                    .isInstanceOf(LessonService.DocumentNotAccessible.class);
        }

        @Test
        void invalidOutputGetsOneRepairAttemptThenReview() {
            Sys s = new Sys();
            AtomicInteger calls = new AtomicInteger();
            LlmClient broken = new LlmClient() {
                public Result complete(Request r) {
                    calls.incrementAndGet();
                    return new Result("{\"title\":\"x\"}", 10, 5, 1);
                }

                public String modelName() {
                    return "broken";
                }
            };
            LessonResponse r = s.service(broken).generate("alice", new LessonRequest("force mass and acceleration", "Grade 9", 45, null, null));
            assertThat(calls.get()).isEqualTo(2);
            assertThat(r.status()).isEqualTo("NEEDS_REVIEW");
            assertThat(r.reviewReasons()).contains("invalid_structure");
        }

        @Test
        void providerFailureIsTracedAndSurfaced() {
            Sys s = new Sys();
            LlmClient down = new LlmClient() {
                public Result complete(Request r) {
                    throw new RuntimeException("provider error 503: overloaded");
                }

                public String modelName() {
                    return "down";
                }
            };
            assertThatThrownBy(() -> s.service(down).generate("alice", new LessonRequest("force mass and acceleration", "Grade 9", 45, null, null)))
                    .isInstanceOf(LessonService.GenerationFailed.class);
            assertThat(s.traces.all()).anyMatch(t -> "ERROR".equals(t.decision()) && t.error().contains("503"));
        }
    }

    @Nested
    class Ingestion {
        @Test
        void failureHalfwayStoresNothingAndReprocessCreatesNoDuplicates() {
            InMemoryDocumentStore docs = new InMemoryDocumentStore();
            InMemoryChunkStore chunks = new InMemoryChunkStore();
            FlakyEmbedder flaky = new FlakyEmbedder(new HashingEmbedder(64), Set.of(2, 3, 4));
            IngestionService svc = new IngestionService(docs, chunks, new Extractor(), new StructureAwareChunker(20),
                    new ExtractionQualityChecker(), new RetryingEmbedder(flaky, 3, 1), Runnable::run, 1);
            Document d = svc.submit("alice", "n.md", "Newton", NEWTON.getBytes(StandardCharsets.UTF_8));
            assertThat(docs.findById(d.id()).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
            assertThat(chunks.chunksOf(d.id())).isEmpty();

            svc.reprocess(d.id(), "alice");
            Document ok = docs.findById(d.id()).orElseThrow();
            assertThat(ok.status()).isEqualTo(DocumentStatus.READY);
            List<Chunk> stored = chunks.chunksOf(d.id());
            assertThat(stored).hasSize(ok.chunkCount());
            assertThat(stored.stream().map(Chunk::id).distinct().count()).isEqualTo(stored.size());
        }

        @Test
        void sameUploadTwiceIsDeduplicatedPerTeacher() {
            Sys s = new Sys();
            Document again = s.ingest.submit("alice", "newton.md", "Newton", NEWTON.getBytes(StandardCharsets.UTF_8));
            assertThat(again.id()).isEqualTo(s.newtonId);
            Document bobsCopy = s.ingest.submit("bob", "newton.md", "Newton", NEWTON.getBytes(StandardCharsets.UTF_8));
            assertThat(bobsCopy.id()).isNotEqualTo(s.newtonId);
        }

        @Test
        void otherTeachersCannotSeeOrReprocessADocument() {
            Sys s = new Sys();
            assertThat(s.docs.findForOwner(s.newtonId, "bob")).isEmpty();
            assertThatThrownBy(() -> s.ingest.reprocess(s.newtonId, "bob")).isInstanceOf(IngestionService.NotFound.class);
        }
    }
}
