package dev.noella.imboni.lesson;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Generated lessons and their review state. In memory for the prototype; would be a table in Postgres. */
public class LessonStore {

    public enum ReviewState { AUTO_APPROVED, PENDING_REVIEW, APPROVED, REJECTED, REFUSED }

    public record Record(String id, String teacherId, Instant createdAt, LessonRequest request, LessonResponse response,
                         ReviewState state, String reviewerNote, Instant reviewedAt) {
        Record review(ReviewState s, String note) {
            return new Record(id, teacherId, createdAt, request, response, s, note, Instant.now());
        }
    }

    private final Map<String, Record> lessons = new ConcurrentHashMap<>();

    public void save(Record r) {
        lessons.put(r.id(), r);
    }

    public Optional<Record> findForTeacher(String id, String teacherId) {
        return Optional.ofNullable(lessons.get(id)).filter(r -> r.teacherId().equals(teacherId));
    }

    public List<Record> pendingForTeacher(String teacherId) {
        return lessons.values().stream()
                .filter(r -> r.teacherId().equals(teacherId) && r.state() == ReviewState.PENDING_REVIEW)
                .sorted(Comparator.comparing(Record::createdAt))
                .toList();
    }

    public Record review(String id, String teacherId, boolean approve, String note) {
        Record r = findForTeacher(id, teacherId).orElseThrow(() -> new IllegalArgumentException("lesson not found: " + id));
        if (r.state() != ReviewState.PENDING_REVIEW) throw new IllegalStateException("lesson is not awaiting review");
        Record updated = r.review(approve ? ReviewState.APPROVED : ReviewState.REJECTED, note);
        lessons.put(id, updated);
        return updated;
    }
}
