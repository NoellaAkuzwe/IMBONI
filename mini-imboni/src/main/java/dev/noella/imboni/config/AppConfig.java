package dev.noella.imboni.config;

import dev.noella.imboni.doc.ChunkStore;
import dev.noella.imboni.doc.DocumentStore;
import dev.noella.imboni.ingest.Chunker;
import dev.noella.imboni.ingest.ExtractionQualityChecker;
import dev.noella.imboni.ingest.Extractor;
import dev.noella.imboni.ingest.IngestionService;
import dev.noella.imboni.ingest.StructureAwareChunker;
import dev.noella.imboni.lesson.LessonService;
import dev.noella.imboni.lesson.LessonStore;
import dev.noella.imboni.lesson.ReviewPolicy;
import dev.noella.imboni.llm.AnthropicLlm;
import dev.noella.imboni.llm.LlmClient;
import dev.noella.imboni.llm.OfflineExtractiveLlm;
import dev.noella.imboni.llm.OpenAiChatLlm;
import dev.noella.imboni.ops.TraceStore;
import dev.noella.imboni.retrieval.Embedder;
import dev.noella.imboni.retrieval.EvidenceGate;
import dev.noella.imboni.retrieval.HashingEmbedder;
import dev.noella.imboni.retrieval.OpenAiEmbedder;
import dev.noella.imboni.retrieval.Retriever;
import dev.noella.imboni.retrieval.RetryingEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/** Wires the plain-Java components. The eval harness builds the same graph without Spring. */
@Configuration
public class AppConfig {

    @Bean
    Embedder embedder(ImboniProperties p) {
        Embedder base = switch (p.embedding().provider()) {
            case "openai" -> new OpenAiEmbedder(System.getenv("OPENAI_API_KEY"), p.embedding().model(), p.embedding().dimensions());
            case "hashing" -> new HashingEmbedder(p.embedding().dimensions());
            default -> throw new IllegalArgumentException("unknown embedding provider " + p.embedding().provider());
        };
        return new RetryingEmbedder(base, 3, 500);
    }

    @Bean
    LlmClient llmClient(ImboniProperties p) {
        Duration timeout = Duration.ofSeconds(p.llm().timeoutSeconds());
        return switch (p.llm().provider()) {
            case "openai" -> new OpenAiChatLlm(System.getenv("OPENAI_API_KEY"), p.llm().model(), timeout, p.llm().maxRetries());
            case "anthropic" -> new AnthropicLlm(System.getenv("ANTHROPIC_API_KEY"), p.llm().model(), timeout, p.llm().maxRetries());
            case "offline" -> new OfflineExtractiveLlm();
            default -> throw new IllegalArgumentException("unknown llm provider " + p.llm().provider());
        };
    }

    @Bean
    ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100); // bounded: a flood of uploads gets rejected instead of exhausting memory
        ex.setThreadNamePrefix("ingest-");
        ex.initialize();
        return ex;
    }

    @Bean
    Chunker chunker() {
        return new StructureAwareChunker(180);
    }

    @Bean
    IngestionService ingestionService(DocumentStore docs, ChunkStore chunks, Chunker chunker, Embedder embedder,
                                      ThreadPoolTaskExecutor ingestExecutor) {
        return new IngestionService(docs, chunks, new Extractor(), chunker, new ExtractionQualityChecker(), embedder, ingestExecutor);
    }

    @Bean
    Retriever retriever(ChunkStore chunks, Embedder embedder, ImboniProperties p) {
        return new Retriever(chunks, embedder, p.retrieval().candidatePool(), p.retrieval().rrfK());
    }

    @Bean
    EvidenceGate evidenceGate(ChunkStore chunks, ImboniProperties p) {
        return new EvidenceGate(chunks, p.evidence().minSimilarity(), p.evidence().minCoverage(), p.review().weakEvidenceCoverage());
    }

    @Bean
    LessonStore lessonStore() {
        return new LessonStore();
    }

    @Bean
    TraceStore traceStore() {
        return new TraceStore();
    }

    @Bean
    LessonService lessonService(DocumentStore docs, Retriever retriever, EvidenceGate gate, LlmClient llm,
                                LessonStore lessons, TraceStore traces, ImboniProperties p) {
        return new LessonService(docs, retriever, gate, llm, new ReviewPolicy(p.review()), lessons, traces, p);
    }
}
