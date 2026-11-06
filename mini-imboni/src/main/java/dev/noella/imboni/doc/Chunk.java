package dev.noella.imboni.doc;

import java.util.List;

/**
 * A searchable passage. Carries its owner (for authorization at query time), where it came from
 * (section + page, used in citations), and any extraction warnings inherited from its source text
 * (used by the review policy).
 */
public record Chunk(
        String id,
        String documentId,
        String ownerId,
        String documentTitle,
        int ordinal,
        String section,
        int page,
        String text,
        List<String> warnings,
        float[] embedding) {

    public Chunk {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public Chunk withEmbedding(float[] e) {
        return new Chunk(id, documentId, ownerId, documentTitle, ordinal, section, page, text, warnings, e);
    }

    public String citationLabel() {
        return documentTitle + (section == null || section.isBlank() ? "" : " › " + section) + " (p. " + page + ")";
    }
}
