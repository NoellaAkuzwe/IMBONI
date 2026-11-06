package dev.noella.imboni.lesson;

import java.util.List;

/**
 * What the teacher gets back. Refusals are a normal response (not an error): they say what was
 * missing and which documents came closest, so the teacher can upload material or rephrase.
 */
public record LessonResponse(
        String lessonId,
        String status,                 // AUTO_APPROVED | NEEDS_REVIEW | REFUSED
        List<String> reviewReasons,
        String message,
        LessonDraft lesson,
        List<CheckedStatement> statements,
        List<Citation> citations,
        Evidence evidence,
        String traceId) {

    public record CheckedStatement(String section, String text, List<String> sources, String kind,
                                   String verdict, String reason) {}

    public record Citation(String sourceId, String documentId, String label, List<String> extractionWarnings) {}

    public record Evidence(String level, double maxSimilarity, double coverage, List<String> missingTerms) {}
}
