package dev.noella.imboni.api;

import dev.noella.imboni.ingest.Extractor;
import dev.noella.imboni.ingest.IngestionService;
import dev.noella.imboni.lesson.LessonService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({IngestionService.NotFound.class, LessonService.DocumentNotAccessible.class})
    ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler({IllegalArgumentException.class, Extractor.UnsupportedDocumentException.class})
    ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        HttpStatus s = e.getMessage() != null && e.getMessage().startsWith("lesson not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return body(s, e.getMessage(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, msg, null);
    }

    /** Model provider down/timing out after retries: 503 with the trace id so it can be looked up. */
    @ExceptionHandler(LessonService.GenerationFailed.class)
    ResponseEntity<Map<String, Object>> generationFailed(LessonService.GenerationFailed e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e.traceId);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus s, String msg, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        if (traceId != null) m.put("traceId", traceId);
        return ResponseEntity.status(s).body(m);
    }
}
