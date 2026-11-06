package dev.noella.imboni.lesson;

import dev.noella.imboni.retrieval.RetrievedPassage;
import dev.noella.imboni.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks each statement against the passages it cites. Deterministic and cheap, so it runs on
 * every request. Its blind spots are measured in the eval (injected-error detection), not assumed.
 *
 * <p>For a "fact":
 * <ol>
 *   <li>Every number in the statement must appear in the cited passages (catches changed values).</li>
 *   <li>Term support = share of the statement's content terms found in the cited passages.
 *   ≥ {@value #SUPPORTED} supported, ≥ {@value #WEAK} weak, else unsupported.</li>
 *   <li>Negation must match the closest source sentence ("is" vs "is not").</li>
 *   <li>If the cited passages fail but another retrieved passage passes → weak (mis-cited).</li>
 * </ol>
 * For an "instruction": only rule 1 applies, against all retrieved passages, plus a lenient term
 * check, since teaching steps legitimately use words that aren't in the sources.
 */
public class GroundingChecker {

    public static final double SUPPORTED = 0.7;
    public static final double WEAK = 0.5;

    private static final Pattern NEGATION = Pattern.compile("\\b(not|no|never|cannot|can't|doesn't|don't|isn't|aren't|won't|without|neither|nor)\\b");

    public enum Verdict { SUPPORTED, WEAK, UNSUPPORTED }

    public record StatementCheck(String section, LessonDraft.Statement statement, Verdict verdict, double support, String reason) {}

    public List<StatementCheck> check(LessonDraft draft, List<RetrievedPassage> passages) {
        Map<String, String> bySource = passages.stream()
                .collect(Collectors.toMap(RetrievedPassage::sourceId, p -> p.chunk().section() + "\n" + p.chunk().text()));
        String all = String.join("\n", bySource.values());
        List<StatementCheck> out = new ArrayList<>();
        draft.sections().forEach((section, stmts) -> {
            for (LessonDraft.Statement s : stmts) out.add(checkOne(section, s, bySource, all));
        });
        return out;
    }

    StatementCheck checkOne(String section, LessonDraft.Statement s, Map<String, String> bySource, String all) {
        String cited = s.sources().stream().map(id -> bySource.getOrDefault(id, "")).collect(Collectors.joining("\n"));

        if (!s.isFact()) {
            Set<String> missingNums = missingNumbers(s.text(), all);
            if (!missingNums.isEmpty())
                return new StatementCheck(section, s, Verdict.UNSUPPORTED, 0, "instruction introduces numbers not in sources: " + missingNums);
            return new StatementCheck(section, s, Verdict.SUPPORTED, 1, "instruction");
        }
        if (cited.isBlank()) return new StatementCheck(section, s, Verdict.UNSUPPORTED, 0, "no valid citation");

        Result onCited = evaluate(s.text(), cited);
        if (onCited.verdict == Verdict.SUPPORTED) return new StatementCheck(section, s, Verdict.SUPPORTED, onCited.support, onCited.reason);
        Result onAll = evaluate(s.text(), all);
        if (onAll.verdict == Verdict.SUPPORTED)
            return new StatementCheck(section, s, Verdict.WEAK, onAll.support, "supported by an uncited passage (mis-cited)");
        return new StatementCheck(section, s, onCited.verdict, onCited.support, onCited.reason);
    }

    private record Result(Verdict verdict, double support, String reason) {}

    private Result evaluate(String statement, String source) {
        Set<String> missingNums = missingNumbers(statement, source);
        if (!missingNums.isEmpty()) return new Result(Verdict.UNSUPPORTED, 0, "numbers not in cited source: " + missingNums);
        String mismatch = numbersVsClosestSentences(statement, source);
        if (mismatch != null) return new Result(Verdict.UNSUPPORTED, 0, mismatch);

        List<String> terms = Text.contentTerms(statement);
        if (terms.isEmpty()) return new Result(Verdict.WEAK, 0, "no checkable terms");
        Set<String> srcTerms = new HashSet<>(Text.contentTerms(source));
        long hit = terms.stream().filter(srcTerms::contains).count();
        double support = hit * 1.0 / terms.size();

        if (support >= WEAK) {
            String best = closestSentence(statement, source);
            if (best != null && negated(statement) != negated(best))
                return new Result(Verdict.UNSUPPORTED, support, "negation differs from source sentence: \"" + best + "\"");
        }
        if (support >= SUPPORTED) return new Result(Verdict.SUPPORTED, support, "term support " + pct(support));
        if (support >= WEAK) return new Result(Verdict.WEAK, support, "partial term support " + pct(support));
        return new Result(Verdict.UNSUPPORTED, support, "low term support " + pct(support));
    }

    private static Set<String> missingNumbers(String statement, String source) {
        Set<String> src = Text.numbers(source);
        Set<String> missing = new java.util.LinkedHashSet<>();
        for (String n : Text.numbers(statement)) if (!src.contains(n)) missing.add(n);
        return missing;
    }

    /**
     * v2 of the number check. v1 only asked "does each number appear somewhere in the passage?", and
     * the planted-error eval showed it missed changed values whenever the new value happened to occur
     * elsewhere in the passage (e.g. mass 2 kg → 5 kg in a passage that also mentions a 5 kg trolley).
     * Now the numbers must match, with multiplicity, the single source sentence the statement is
     * closest to (a statement that merges two sentences will be flagged: acceptable, it goes to review).
     */
    private static String numbersVsClosestSentences(String statement, String source) {
        List<String> nums = Text.numberList(statement);
        if (nums.isEmpty()) return null;
        Set<String> st = Text.contentTermSet(statement);
        List<String> sents = Text.sentences(source);
        List<String> ranked = sents.stream()
                .sorted(java.util.Comparator.comparingDouble((String x) -> -overlap(st, x)))
                .limit(1).toList();
        Map<String, Integer> available = new java.util.HashMap<>();
        for (String s : ranked) for (String n : Text.numberList(s)) available.merge(n, 1, Integer::sum);
        Map<String, Integer> needed = new java.util.HashMap<>();
        for (String n : nums) needed.merge(n, 1, Integer::sum);
        for (var e : needed.entrySet()) {
            if (available.getOrDefault(e.getKey(), 0) < e.getValue())
                return "number " + e.getKey() + " doesn't match the closest source sentence: \"" + ranked.get(0) + "\"";
        }
        return null;
    }

    private static double overlap(Set<String> st, String sentence) {
        Set<String> t = Text.contentTermSet(sentence);
        if (t.isEmpty()) return 0;
        return st.stream().filter(t::contains).count() * 1.0 / Math.max(1, st.size());
    }

    private static String closestSentence(String statement, String source) {
        Set<String> st = Text.contentTermSet(statement);
        String best = null;
        double bestScore = 0;
        for (String sent : Text.sentences(source)) {
            Set<String> t = Text.contentTermSet(sent);
            if (t.isEmpty()) continue;
            long inter = st.stream().filter(t::contains).count();
            double score = inter * 1.0 / Math.max(1, st.size());
            if (score > bestScore) {
                bestScore = score;
                best = sent;
            }
        }
        return best;
    }

    private static boolean negated(String s) {
        return NEGATION.matcher(Text.normalize(s)).find();
    }

    private static String pct(double d) {
        return Math.round(d * 100) + "%";
    }
}
