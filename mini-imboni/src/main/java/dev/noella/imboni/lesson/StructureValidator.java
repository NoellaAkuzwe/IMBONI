package dev.noella.imboni.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses model output and checks the contract: valid JSON, required sections present and non-empty,
 * every statement has text and cites at least one passage that was actually provided.
 */
public class StructureValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public Outcome validate(String raw, Set<String> providedSourceIds) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = JSON.readTree(stripFences(raw));
        } catch (Exception e) {
            return new Outcome(null, List.of("output is not valid JSON: " + e.getMessage().lines().findFirst().orElse("")));
        }
        if (root == null || !root.isObject()) return new Outcome(null, List.of("output is not a JSON object"));

        JsonNode sectionsNode = root.path("sections");
        Map<String, List<LessonDraft.Statement>> sections = new LinkedHashMap<>();
        for (String name : LessonDraft.ALL_SECTIONS) {
            JsonNode arr = sectionsNode.path(name);
            List<LessonDraft.Statement> stmts = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode s : arr) {
                    String text = s.path("text").asText("").strip();
                    List<String> src = new ArrayList<>();
                    s.path("sources").forEach(x -> src.add(x.asText().strip().replaceAll("[\\[\\]]", "")));
                    String kind = s.path("kind").asText("fact");
                    if (text.isEmpty()) {
                        errors.add(name + ": statement with empty text");
                        continue;
                    }
                    if (src.isEmpty()) errors.add(name + ": statement has no sources: \"" + abbreviate(text) + "\"");
                    for (String id : src) {
                        if (!providedSourceIds.contains(id)) errors.add(name + ": cites unknown source " + id);
                    }
                    stmts.add(new LessonDraft.Statement(text, src, kind));
                }
            }
            if (stmts.isEmpty() && LessonDraft.REQUIRED_SECTIONS.contains(name)) errors.add("missing or empty section: " + name);
            sections.put(name, stmts);
        }
        List<String> gaps = new ArrayList<>();
        root.path("gaps").forEach(g -> gaps.add(g.asText()));
        String title = root.path("title").asText("").strip();
        if (title.isEmpty()) errors.add("missing title");
        return new Outcome(new LessonDraft(title, sections, gaps), errors);
    }

    private static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.strip();
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** draft is null when the output couldn't be parsed at all. */
    public record Outcome(LessonDraft draft, List<String> errors) {
        public boolean valid() {
            return draft != null && errors.isEmpty();
        }
    }
}
