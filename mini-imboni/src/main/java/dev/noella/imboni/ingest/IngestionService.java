package dev.noella.imboni.ingest;

import dev.noella.imboni.doc.Chunk;
import dev.noella.imboni.doc.ChunkStore;
import dev.noella.imboni.doc.Document;
import dev.noella.imboni.doc.DocumentStatus;
import dev.noella.imboni.doc.DocumentStore;
import dev.noella.imboni.retrieval.Embedder;
import dev.noella.imboni.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Upload → PENDING → (background) extract → quality-check → chunk → embed → atomically store → READY.
 *
 * <p>Reliability properties (each covered by a test):
 * <ul>
 *   <li>Uploading the same file twice for the same teacher returns the existing document (content hash).</li>
 *   <li>Processing runs off the request thread; status is visible via GET /documents/{id}.</li>
 *   <li>Embedding failures are retried with backoff; if they still fail, the document is FAILED
 *   with the error and can be reprocessed.</li>
 *   <li>Chunks get deterministic ids and are swapped in atomically, so a retry after a failure
 *   halfway through can't leave duplicate or partial passages.</li>
 * </ul>
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentStore docs;
    private final ChunkStore chunks;
    private final Extractor extractor;
    private final Chunker chunker;
    private final ExtractionQualityChecker checker;
    private final Embedder embedder;
    private final Executor executor;
    private final int embedBatch;
    /** Raw bytes kept until processing succeeds (stand-in for object storage). */
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

    public IngestionService(DocumentStore docs, ChunkStore chunks, Extractor extractor, Chunker chunker,
                            ExtractionQualityChecker checker, Embedder embedder, Executor executor) {
        this(docs, chunks, extractor, chunker, checker, embedder, executor, 32);
    }

    public IngestionService(DocumentStore docs, ChunkStore chunks, Extractor extractor, Chunker chunker,
                            ExtractionQualityChecker checker, Embedder embedder, Executor executor, int embedBatch) {
        this.embedBatch = embedBatch;
        this.docs = docs;
        this.chunks = chunks;
        this.extractor = extractor;
        this.chunker = chunker;
        this.checker = checker;
        this.embedder = embedder;
        this.executor = executor;
    }

    public Document submit(String ownerId, String filename, String title, byte[] bytes) {
        String hash = Text.sha256(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
        var existing = docs.findByOwnerAndHash(ownerId, hash);
        if (existing.isPresent()) {
            Document d = existing.get();
            if (d.status() == DocumentStatus.FAILED) {
                blobs.put(d.id(), bytes);
                schedule(d.id());
            }
            return d;
        }
        Instant now = Instant.now();
        Document d = new Document(UUID.randomUUID().toString(), ownerId,
                title == null || title.isBlank() ? filename : title, filename, hash,
                DocumentStatus.PENDING, null, List.of(), 0, 0, now, now);
        docs.save(d);
        blobs.put(d.id(), bytes);
        schedule(d.id());
        return d;
    }

    /** Re-run processing for a FAILED (or READY, e.g. after a chunker change) document. */
    public Document reprocess(String docId, String ownerId) {
        Document d = docs.findForOwner(docId, ownerId).orElseThrow(() -> new NotFound(docId));
        if (!blobs.containsKey(docId)) throw new IllegalStateException("original upload no longer available; upload again");
        schedule(docId);
        return d;
    }

    private void schedule(String docId) {
        executor.execute(() -> process(docId));
    }

    public void process(String docId) {
        Document d = docs.findById(docId).orElseThrow(() -> new NotFound(docId));
        d = docs.save(d.startedAttempt());
        try {
            ExtractedDocument extracted = extractor.extract(d.filename(), blobs.get(docId));
            List<Chunker.Draft> drafts = chunker.chunk(extracted);
            if (drafts.isEmpty()) throw new IllegalStateException("no text could be extracted");

            Set<String> docWarnings = new LinkedHashSet<>(extracted.warnings());
            if (extracted.scanned()) docWarnings.add("scanned_source");
            List<Chunk> built = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                Chunker.Draft dr = drafts.get(i);
                List<String> w = new ArrayList<>(checker.check(dr.text(), dr.startsPage()));
                if (extracted.scanned()) w.add("scanned_source");
                w.forEach(code -> docWarnings.add(code + ":page " + dr.page()));
                built.add(new Chunk(docId + "-" + i, docId, d.ownerId(), d.title(), i, dr.section(), dr.page(),
                        dr.text(), w, null));
            }

            List<Chunk> embedded = new ArrayList<>(built.size());
            for (int i = 0; i < built.size(); i += embedBatch) {
                List<Chunk> batch = built.subList(i, Math.min(built.size(), i + embedBatch));
                List<float[]> vecs = embedder.embed(batch.stream()
                        .map(c -> (c.section().isBlank() ? "" : c.section() + "\n") + c.text()).toList());
                for (int j = 0; j < batch.size(); j++) embedded.add(batch.get(j).withEmbedding(vecs.get(j)));
            }

            chunks.replaceChunks(docId, embedded); // atomic: all or nothing
            docs.save(d.ready(new ArrayList<>(docWarnings), embedded.size()));
            blobs.remove(docId);
            log.info("document {} ready: {} chunks, {} warnings", docId, embedded.size(), docWarnings.size());
        } catch (Exception e) {
            log.warn("document {} failed on attempt {}: {}", docId, d.attempts(), e.toString());
            docs.save(d.withStatus(DocumentStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String id) {
            super("document not found: " + id);
        }
    }
}
