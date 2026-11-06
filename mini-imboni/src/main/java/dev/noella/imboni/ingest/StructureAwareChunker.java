package dev.noella.imboni.ingest;

import dev.noella.imboni.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits on document structure instead of word counts:
 * <ul>
 *   <li>Headings start a new passage and become the passage's {@code section} (used in citations).</li>
 *   <li>An equation is glued to the explanation before it, and to a following "where ..." line.</li>
 *   <li>A table is kept whole and glued to its caption/lead-in sentence.</li>
 *   <li>Blocks are packed into passages up to {@code maxWords}; a block is never cut in half
 *   (an oversized paragraph is split on sentence boundaries).</li>
 * </ul>
 */
public class StructureAwareChunker implements Chunker {

    private final int maxWords;

    public StructureAwareChunker(int maxWords) {
        this.maxWords = maxWords;
    }

    private enum Kind { HEADING, PARAGRAPH, EQUATION, TABLE, LIST }

    private record Block(Kind kind, String text) {}

    @Override
    public List<Draft> chunk(ExtractedDocument doc) {
        List<Draft> out = new ArrayList<>();
        String section = "";
        for (ExtractedDocument.Page page : doc.pages()) {
            List<Block> blocks = glue(parse(page.text()));
            List<String> buffer = new ArrayList<>();
            int bufWords = 0;
            boolean firstOnPage = true;
            for (Block b : blocks) {
                if (b.kind == Kind.HEADING) {
                    if (!buffer.isEmpty()) {
                        out.add(new Draft(section, page.number(), String.join("\n", buffer), firstOnPage));
                        firstOnPage = false;
                        buffer.clear();
                        bufWords = 0;
                    }
                    section = b.text.replaceFirst("^#+\\s*", "").strip();
                    continue;
                }
                for (String piece : splitIfHuge(b)) {
                    int w = Text.wordCount(piece);
                    if (bufWords > 0 && bufWords + w > maxWords) {
                        out.add(new Draft(section, page.number(), String.join("\n", buffer), firstOnPage));
                        firstOnPage = false;
                        buffer.clear();
                        bufWords = 0;
                    }
                    buffer.add(piece);
                    bufWords += w;
                }
            }
            if (!buffer.isEmpty()) out.add(new Draft(section, page.number(), String.join("\n", buffer), firstOnPage));
        }
        return out;
    }

    private List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> para = new ArrayList<>();
        List<String> table = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String l = raw.strip();
            if (!l.startsWith("|") && !table.isEmpty()) {
                blocks.add(new Block(Kind.TABLE, String.join("\n", table)));
                table.clear();
            }
            if (l.isEmpty()) {
                flushPara(blocks, para);
            } else if (l.startsWith("#")) {
                flushPara(blocks, para);
                blocks.add(new Block(Kind.HEADING, l));
            } else if (l.startsWith("|")) {
                flushPara(blocks, para);
                table.add(l);
            } else if (ExtractionQualityChecker.isEquationLine(l) || l.startsWith("$$")) {
                flushPara(blocks, para);
                blocks.add(new Block(Kind.EQUATION, l));
            } else if (l.matches("^([-*•]|\\d+[.)])\\s+.*")) {
                flushPara(blocks, para);
                blocks.add(new Block(Kind.LIST, l));
            } else {
                para.add(l);
            }
        }
        if (!table.isEmpty()) blocks.add(new Block(Kind.TABLE, String.join("\n", table)));
        flushPara(blocks, para);
        return blocks;
    }

    private static void flushPara(List<Block> blocks, List<String> para) {
        if (!para.isEmpty()) {
            blocks.add(new Block(Kind.PARAGRAPH, String.join(" ", para)));
            para.clear();
        }
    }

    /** Merge equations into neighbouring explanations, tables into their captions, and list runs. */
    private List<Block> glue(List<Block> in) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Block b = in.get(i);
            Block prev = out.isEmpty() ? null : out.get(out.size() - 1);
            boolean prevIsContent = prev != null && prev.kind != Kind.HEADING;
            if (b.kind == Kind.EQUATION && prevIsContent) {
                out.set(out.size() - 1, new Block(Kind.PARAGRAPH, prev.text + "\n" + b.text));
            } else if (b.kind == Kind.PARAGRAPH && prevIsContent && prev.text.contains("\n")
                    && b.text.toLowerCase().matches("^(where|here)\\b.*")) {
                out.set(out.size() - 1, new Block(Kind.PARAGRAPH, prev.text + "\n" + b.text));
            } else if (b.kind == Kind.TABLE && prevIsContent
                    && (prev.text.strip().endsWith(":") || Text.wordCount(prev.text) <= 40)) {
                out.set(out.size() - 1, new Block(Kind.PARAGRAPH, prev.text + "\n" + b.text));
            } else if (b.kind == Kind.LIST && prev != null && prev.kind == Kind.LIST) {
                out.set(out.size() - 1, new Block(Kind.LIST, prev.text + "\n" + b.text));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private List<String> splitIfHuge(Block b) {
        if (b.kind != Kind.PARAGRAPH || Text.wordCount(b.text) <= maxWords) return List.of(b.text);
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int words = 0;
        for (String s : Text.sentences(b.text)) {
            int w = Text.wordCount(s);
            if (words > 0 && words + w > maxWords) {
                pieces.add(cur.toString().strip());
                cur.setLength(0);
                words = 0;
            }
            cur.append(s).append(' ');
            words += w;
        }
        if (!cur.isEmpty()) pieces.add(cur.toString().strip());
        return pieces;
    }

    @Override
    public String name() {
        return "structure-" + maxWords;
    }
}
