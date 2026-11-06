package dev.noella.imboni.ingest;

import java.util.List;

/** Splits extracted pages into passages. Two implementations so the eval can compare boundaries. */
public interface Chunker {

    List<Draft> chunk(ExtractedDocument doc);

    String name();

    /** A passage before it gets an id, owner and embedding. {@code startsPage}: first passage on its page. */
    record Draft(String section, int page, String text, boolean startsPage) {}
}
