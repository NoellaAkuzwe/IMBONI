package dev.noella.imboni.lesson;

import java.util.List;
import java.util.Map;

/**
 * The model's lesson, parsed. Every statement carries the source ids it relies on and a kind:
 * "fact" (a claim about the subject, must be supported by its sources) or "instruction" (a
 * teaching step such as "work in pairs", which only has to avoid introducing new numbers/facts).
 */
public record LessonDraft(String title, Map<String, List<Statement>> sections, List<String> gaps) {

    public static final List<String> REQUIRED_SECTIONS =
            List.of("objectives", "key_ideas", "activity", "check_for_understanding");
    public static final List<String> ALL_SECTIONS =
            List.of("objectives", "key_ideas", "worked_example", "activity", "check_for_understanding");

    public record Statement(String text, List<String> sources, String kind) {
        public boolean isFact() {
            return !"instruction".equalsIgnoreCase(kind);
        }
    }
}
