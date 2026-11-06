package dev.noella.imboni.lesson;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What a teacher asks for. Grade level and duration are required because "correct but too
 * advanced" is a real failure mode: the model needs them, and the reviewer needs to see them.
 */
public record LessonRequest(
        @NotBlank @Size(max = 300) String topic,
        @NotBlank @Size(max = 40) String gradeLevel,
        @Min(10) @Max(240) int durationMinutes,
        @Size(max = 500) String learningGoals,
        List<String> documentIds) {

    public String retrievalQuery() {
        return learningGoals == null || learningGoals.isBlank() ? topic : topic + ". " + learningGoals;
    }

    public String cacheKey() {
        return String.join("|", topic.strip().toLowerCase(), gradeLevel.strip().toLowerCase(),
                String.valueOf(durationMinutes), learningGoals == null ? "" : learningGoals.strip().toLowerCase(),
                documentIds == null ? "*" : String.join(",", documentIds.stream().sorted().toList()));
    }
}
