package dev.noella.imboni.api;

import dev.noella.imboni.doc.Document;
import dev.noella.imboni.doc.DocumentStore;
import dev.noella.imboni.ingest.IngestionService;
import dev.noella.imboni.lesson.LessonRequest;
import dev.noella.imboni.lesson.LessonResponse;
import dev.noella.imboni.lesson.LessonService;
import dev.noella.imboni.lesson.LessonStore;
import dev.noella.imboni.ops.RequestTrace;
import dev.noella.imboni.ops.TraceStore;
import dev.noella.imboni.security.TeacherAuthFilter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class Api {

    private static final String T = TeacherAuthFilter.TEACHER_ATTR;

    private final IngestionService ingestion;
    private final DocumentStore docs;
    private final LessonService lessons;
    private final LessonStore lessonStore;
    private final TraceStore traces;

    public Api(IngestionService ingestion, DocumentStore docs, LessonService lessons, LessonStore lessonStore, TraceStore traces) {
        this.ingestion = ingestion;
        this.docs = docs;
        this.lessons = lessons;
        this.lessonStore = lessonStore;
        this.traces = traces;
    }

    // ---- documents -------------------------------------------------------------------------

    /** Returns 202 immediately; poll GET /documents/{id} until status is READY or FAILED. */
    @PostMapping("/documents")
    public ResponseEntity<Document> upload(@RequestAttribute(T) String teacher, @RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "title", required = false) String title) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("empty file");
        Document d = ingestion.submit(teacher, file.getOriginalFilename(), title, file.getBytes());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(d);
    }

    @GetMapping("/documents")
    public List<Document> listDocuments(@RequestAttribute(T) String teacher) {
        return docs.listForOwner(teacher);
    }

    @GetMapping("/documents/{id}")
    public Document getDocument(@RequestAttribute(T) String teacher, @PathVariable String id) {
        return docs.findForOwner(id, teacher).orElseThrow(() -> new IngestionService.NotFound(id));
    }

    @PostMapping("/documents/{id}/reprocess")
    public ResponseEntity<Document> reprocess(@RequestAttribute(T) String teacher, @PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestion.reprocess(id, teacher));
    }

    // ---- lessons ---------------------------------------------------------------------------

    @PostMapping("/lessons")
    public LessonResponse createLesson(@RequestAttribute(T) String teacher, @Valid @RequestBody LessonRequest req) {
        return lessons.generate(teacher, req);
    }

    @GetMapping("/lessons/{id}")
    public LessonStore.Record getLesson(@RequestAttribute(T) String teacher, @PathVariable String id) {
        return lessonStore.findForTeacher(id, teacher).orElseThrow(() -> new IllegalArgumentException("lesson not found: " + id));
    }

    // ---- human review ----------------------------------------------------------------------

    @GetMapping("/reviews")
    public List<LessonStore.Record> pendingReviews(@RequestAttribute(T) String teacher) {
        return lessonStore.pendingForTeacher(teacher);
    }

    public record ReviewBody(boolean approve, String note) {}

    @PostMapping("/reviews/{id}")
    public LessonStore.Record review(@RequestAttribute(T) String teacher, @PathVariable String id, @RequestBody ReviewBody body) {
        return lessonStore.review(id, teacher, body.approve(), body.note());
    }

    // ---- operations ------------------------------------------------------------------------

    /** Latency percentiles, cost per lesson, review/refusal/error rates, review reasons. */
    @GetMapping("/ops/summary")
    public TraceStore.Summary summary() {
        return TraceStore.summarize(traces.all());
    }

    @GetMapping("/ops/traces")
    public List<RequestTrace> recentTraces(@RequestParam(defaultValue = "50") int limit) {
        List<RequestTrace> all = traces.all();
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }
}
