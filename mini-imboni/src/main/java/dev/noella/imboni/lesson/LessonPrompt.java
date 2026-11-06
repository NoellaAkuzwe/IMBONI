package dev.noella.imboni.lesson;

import dev.noella.imboni.retrieval.RetrievedPassage;

import java.util.List;

/** Builds the prompt. The same text goes to the offline generator and to real models. */
public final class LessonPrompt {

    private LessonPrompt() {}

    public static final String SYSTEM = """
            You write lesson plans for teachers using ONLY the numbered source passages provided.
            Rules:
            - Every statement must cite the passage ids it relies on, e.g. "sources": ["S2"].
            - Mark each statement "kind": "fact" (a claim about the subject) or "instruction" (a teaching step).
            - Facts must be directly supported by the cited passages. Do not add facts, numbers, formulas or
              examples that are not in the passages, even if you know them to be true.
            - Instructions must not introduce new facts or numbers.
            - If the passages don't cover part of the request, list it in "gaps" instead of filling it in.
            - Pitch the activities at the given grade level and fit them in the given duration.
            Respond with JSON only, in this shape:
            {"title": "...",
             "sections": {
               "objectives": [{"text": "...", "sources": ["S1"], "kind": "instruction"}],
               "key_ideas": [{"text": "...", "sources": ["S1"], "kind": "fact"}],
               "worked_example": [{"text": "...", "sources": ["S2"], "kind": "fact"}],
               "activity": [{"text": "...", "sources": ["S1"], "kind": "instruction"}],
               "check_for_understanding": [{"text": "...", "sources": ["S3"], "kind": "instruction"}]
             },
             "gaps": ["..."]}
            """;

    public static String user(LessonRequest req, List<RetrievedPassage> passages) {
        StringBuilder sb = new StringBuilder();
        sb.append("REQUEST\n")
                .append("topic: ").append(req.topic()).append('\n')
                .append("grade_level: ").append(req.gradeLevel()).append('\n')
                .append("duration_minutes: ").append(req.durationMinutes()).append('\n')
                .append("learning_goals: ").append(req.learningGoals() == null ? "" : req.learningGoals()).append("\n\n")
                .append("SOURCES\n");
        for (RetrievedPassage p : passages) {
            sb.append('[').append(p.sourceId()).append("] (").append(p.chunk().citationLabel()).append(")\n")
                    .append(p.chunk().text()).append("\n\n");
        }
        return sb.toString();
    }

    public static String repair(String previousOutput, List<String> errors) {
        return "Your previous answer was not valid:\n- " + String.join("\n- ", errors)
                + "\n\nPrevious answer:\n" + previousOutput + "\n\nReturn corrected JSON only.";
    }
}
