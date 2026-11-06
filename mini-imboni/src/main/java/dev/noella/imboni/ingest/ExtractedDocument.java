package dev.noella.imboni.ingest;

import java.util.List;

/** Text pulled out of an upload, page by page, plus anything the extractor itself noticed. */
public record ExtractedDocument(List<Page> pages, boolean scanned, List<String> warnings) {

    public record Page(int number, String text) {}
}
