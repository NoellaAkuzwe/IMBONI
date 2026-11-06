package dev.noella.imboni.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Flags text that probably came out of extraction/OCR wrong. These flags don't block ingestion:
 * they travel with the chunk, and any lesson that cites a flagged chunk goes to teacher review.
 *
 * <p>Checks (each returns a stable code used in the eval):
 * <ul>
 *   <li>{@code garbled_chars}: Unicode replacement characters or a high share of stray symbols.</li>
 *   <li>{@code low_text_density}: mostly non-letters, typical of a failed OCR page.</li>
 *   <li>{@code broken_table}: rows of one table have different column counts.</li>
 *   <li>{@code table_without_heading}: a page starts with a table, i.e. its heading/caption was
 *   probably left on the previous page.</li>
 *   <li>{@code suspicious_equation}: OCR confusions inside an equation (digits mixed with l/I, e.g. "l/2" for "1/2"),
 *   unbalanced brackets, or a dangling operator.</li>
 * </ul>
 */
public class ExtractionQualityChecker {

    private static final Pattern OCR_CONFUSION = Pattern.compile("(?<![A-Za-z])(\\d+[lI]\\d*|[lI]\\d+|[lI]/\\d+)(?![A-Za-z])");
    private static final Pattern DANGLING_OP = Pattern.compile("(^\\s*[=+*/^]|[=+\\-*/^]\\s*$)");
    private static final Pattern STRAY = Pattern.compile("[\\u00a4\\u00a6\\u00a7\\u00a8\\u00ac\\u00af\\u00b4\\u00b8~`@$_\\\\{}<>]");

    public List<String> check(String text, boolean startOfPage) {
        Set<String> issues = new LinkedHashSet<>();
        if (text.indexOf('\uFFFD') >= 0) issues.add("garbled_chars");
        long nonSpace = text.chars().filter(c -> !Character.isWhitespace(c)).count();
        long letters = text.chars().filter(Character::isLetter).count();
        long stray = STRAY.matcher(text).results().count();
        if (nonSpace > 0 && stray * 1.0 / nonSpace > 0.02) issues.add("garbled_chars");
        if (nonSpace >= 30 && letters * 1.0 / nonSpace < 0.45 && !mostlyTable(text)) issues.add("low_text_density");

        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (startOfPage && !lines.isEmpty() && lines.get(0).startsWith("|")) issues.add("table_without_heading");

        // Table rows: compare column counts within each run of '|' lines (ignoring separator rows).
        int expected = -1;
        for (String l : lines) {
            if (!l.startsWith("|")) { expected = -1; continue; }
            if (l.matches("\\|[\\s:\\-|]+\\|?")) continue;
            int cols = l.replaceAll("^\\||\\|$", "").split("\\|", -1).length;
            if (expected == -1) expected = cols;
            else if (cols != expected) issues.add("broken_table");
        }

        for (String l : lines) {
            if (!isEquationLine(l)) continue;
            if (OCR_CONFUSION.matcher(l).find() || DANGLING_OP.matcher(l).find() || !balanced(l)) {
                issues.add("suspicious_equation");
            }
        }
        return new ArrayList<>(issues);
    }

    private static boolean mostlyTable(String text) {
        long tableLines = text.lines().filter(l -> l.strip().startsWith("|")).count();
        return tableLines * 2 >= text.lines().count();
    }

    /** Short line containing '=' or a reaction arrow and few words: treat as an equation. */
    public static boolean isEquationLine(String l) {
        if (l.startsWith("|") || l.startsWith("#")) return false;
        boolean hasOp = l.contains("=") || l.contains("→") || l.contains("->");
        return hasOp && l.length() <= 90 && l.split("\\s+").length <= 16;
    }

    private static boolean balanced(String s) {
        int paren = 0, bracket = 0;
        for (char c : s.toCharArray()) {
            if (c == '(') paren++;
            if (c == ')') paren--;
            if (c == '[') bracket++;
            if (c == ']') bracket--;
            if (paren < 0 || bracket < 0) return false;
        }
        return paren == 0 && bracket == 0;
    }
}
