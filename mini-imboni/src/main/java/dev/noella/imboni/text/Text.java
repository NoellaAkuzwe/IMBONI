package dev.noella.imboni.text;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small text helpers shared by keyword search, the offline embedder and the grounding checker.
 * Deliberately simple and deterministic so evaluation results are reproducible.
 */
public final class Text {

    private Text() {}

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "of", "to", "in", "on", "at", "by",
            "for", "with", "about", "as", "into", "from", "up", "down", "out", "over", "under", "is", "are",
            "was", "were", "be", "been", "being", "it", "its", "this", "that", "these", "those", "there",
            "their", "they", "them", "we", "you", "your", "our", "i", "me", "my", "he", "she", "his", "her",
            "do", "does", "did", "can", "could", "should", "would", "will", "shall", "may", "might", "must",
            "have", "has", "had", "not", "no", "so", "than", "too", "very", "just", "also", "how", "what",
            "why", "when", "where", "which", "who", "whom", "all", "any", "each", "some", "such", "more",
            "most", "other", "only", "own", "same", "both", "few", "between", "through", "during", "before",
            "after", "above", "below", "again", "further", "once", "here", "because", "while", "per",
            "lesson", "students", "student", "teach", "explain", "plan", "make", "create", "use", "using",
            "grade", "class", "help", "write", "give", "show", "need", "want", "please", "learn", "understand");

    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{Nd}]*|\\p{Nd}+(?:[.,]\\p{Nd}+)?");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{Nd}])\\d+(?:[.,]\\d+)?(?:/\\d+)?");

    /** Lowercased word tokens, including stopwords. */
    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(normalize(s));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Lowercased, stemmed, stopword-free tokens: what search and grounding compare. */
    public static List<String> contentTerms(String s) {
        List<String> out = new ArrayList<>();
        for (String w : words(s)) {
            if (w.length() < 2 && !Character.isDigit(w.charAt(0))) continue;
            if (STOPWORDS.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    public static Set<String> contentTermSet(String s) {
        return new LinkedHashSet<>(contentTerms(s));
    }

    /** Numbers (including simple fractions like 3/4) in their normalized text form. */
    public static Set<String> numbers(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(normalize(s));
        while (m.find()) out.add(m.group().replace(',', '.'));
        return out;
    }

    /** Numbers in order of appearance, with repeats (for multiset comparison). */
    public static List<String> numberList(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(normalize(s));
        while (m.find()) out.add(m.group().replace(',', '.'));
        return out;
    }

    /** NFKC + lowercase + unify the few symbols that differ between sources and model output. */
    public static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replace('−', '-')      // minus sign
                .replace('×', '*')      // multiplication sign
                .replace('→', '>')      // arrow in chemical equations
                .replace("->", ">");
    }

    /** Very light suffix stripping. Enough to match "forces"/"force", "balancing"/"balance". */
    public static String stem(String w) {
        String s = stem1(w);
        // Align -ation nouns with -ate verbs: evaporation/evaporate/evaporates → "evapor".
        if (s.endsWith("ation") && s.length() > 7) return s.substring(0, s.length() - 5);
        if (s.endsWith("at") && s.length() > 5) return s.substring(0, s.length() - 2);
        return s;
    }

    private static String stem1(String w) {
        if (w.length() <= 3 || Character.isDigit(w.charAt(0))) return w;
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("sses")) return w.substring(0, w.length() - 2);
        if (w.endsWith("ing") && w.length() > 5) return trimE(w.substring(0, w.length() - 3));
        if (w.endsWith("ed") && w.length() > 4) return trimE(w.substring(0, w.length() - 2));
        if (w.endsWith("es") && w.length() > 4 && "sxzh".indexOf(w.charAt(w.length() - 3)) >= 0)
            return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is"))
            return trimE(w.substring(0, w.length() - 1));
        return trimE(w);
    }

    private static String trimE(String w) {
        return (w.length() > 4 && w.endsWith("e")) ? w.substring(0, w.length() - 1) : w;
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int wordCount(String s) {
        return words(s).size();
    }

    /** Rough token estimate (~4 chars per token) used when a provider doesn't report usage. */
    public static int estimateTokens(String s) {
        return Math.max(1, (int) Math.ceil(s.length() / 4.0));
    }

    /** Split prose into sentences. Keeps equations and table rows as their own units. */
    public static List<String> sentences(String s) {
        List<String> out = new ArrayList<>();
        for (String line : s.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("|") || t.startsWith("#")) { out.add(t); continue; }
            for (String part : t.split("(?<=[.!?])\\s+(?=[A-Z0-9(])")) {
                String p = part.strip();
                if (!p.isEmpty()) out.add(p);
            }
        }
        return out;
    }
}
