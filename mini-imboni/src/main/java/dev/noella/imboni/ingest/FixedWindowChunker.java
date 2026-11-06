package dev.noella.imboni.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Baseline: fixed windows of N words, ignoring structure. This is the naive approach that splits
 * explanations from their equations and tables from their headings. Kept for the eval comparison.
 */
public class FixedWindowChunker implements Chunker {

    private final int windowWords;

    public FixedWindowChunker(int windowWords) {
        this.windowWords = windowWords;
    }

    @Override
    public List<Draft> chunk(ExtractedDocument doc) {
        List<Draft> out = new ArrayList<>();
        for (ExtractedDocument.Page p : doc.pages()) {
            String[] words = p.text().split("\\s+");
            for (int i = 0; i < words.length; i += windowWords) {
                String text = String.join(" ", java.util.Arrays.copyOfRange(words, i, Math.min(words.length, i + windowWords))).strip();
                if (!text.isEmpty()) out.add(new Draft("", p.number(), text, i == 0));
            }
        }
        return out;
    }

    @Override
    public String name() {
        return "fixed-" + windowWords;
    }
}
