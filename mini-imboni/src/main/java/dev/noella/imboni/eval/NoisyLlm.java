package dev.noella.imboni.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.noella.imboni.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps a model and plants known errors in its output, so we can measure whether the grounding
 * checker catches them. Three error types, mirroring how real models go wrong:
 * <ul>
 *   <li>FABRICATED: a plausible-sounding fact that isn't in any passage, with a real citation.</li>
 *   <li>NUMBER: a correct statement with one number changed.</li>
 *   <li>NEGATION: a correct statement with its meaning flipped ("is" → "is not").</li>
 *   <li>TERM_SWAP: a correct statement with one key term replaced by another term from the same
 *   material ("chlorophyll absorbs light" → "glucose absorbs light"). Every word still appears in the
 *   sources, so a lexical checker is expected to struggle here. That is the point of including it.</li>
 * </ul>
 */
public class NoisyLlm implements LlmClient {

    public enum ErrorType { FABRICATED, NUMBER, NEGATION, TERM_SWAP }

    public record Injection(ErrorType type, String text) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] FABRICATIONS = {
            "Scientists consider this the single most important idea in modern science.",
            "The same principle explains why the Moon stays in orbit around the Earth.",
            "Engineers rely on this rule when designing bridges and aircraft wings.",
            "This idea was first proposed by ancient Greek philosophers.",
            "It only applies at very low temperatures near absolute zero.",
            "Most students master this topic within a single lesson.",
    };
    private static final Pattern NUM = Pattern.compile("(?<![\\w/])(\\d+)(?![\\w/])");
    private static final Pattern VERB = Pattern.compile("\\b(is|are|can|has|have|does)\\b");

    private final LlmClient delegate;
    private int counter;
    private List<Injection> last = List.of();

    public NoisyLlm(LlmClient delegate) {
        this.delegate = delegate;
    }

    public List<Injection> lastInjections() {
        return last;
    }

    @Override
    public Result complete(Request req) {
        Result r = delegate.complete(req);
        List<Injection> inj = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(r.text());
            ArrayNode key = (ArrayNode) root.path("sections").path("key_ideas");
            ArrayNode ex = (ArrayNode) root.path("sections").path("worked_example");
            if (key == null || key.isEmpty()) {
                last = List.of();
                return r;
            }
            String src = key.get(0).path("sources").get(0).asText();

            // FABRICATED
            String fab = FABRICATIONS[counter++ % FABRICATIONS.length];
            ObjectNode f = JSON.createObjectNode();
            f.put("text", fab);
            f.putArray("sources").add(src);
            f.put("kind", "fact");
            key.add(f);
            inj.add(new Injection(ErrorType.FABRICATED, fab));

            // NUMBER: first fact (key ideas, then worked example) with a standalone integer
            outer:
            for (ArrayNode arr : List.of(key, ex)) {
                for (JsonNode n : arr) {
                    String t = n.path("text").asText();
                    if (t.equals(fab)) continue;
                    Matcher m = NUM.matcher(t);
                    if (m.find()) {
                        int v = Integer.parseInt(m.group(1));
                        String changed = t.substring(0, m.start()) + (v + 3) + t.substring(m.end());
                        ((ObjectNode) n).put("text", changed);
                        inj.add(new Injection(ErrorType.NUMBER, changed));
                        break outer;
                    }
                }
            }

            // NEGATION: first key idea with a simple verb and no existing negation
            for (JsonNode n : key) {
                String t = n.path("text").asText();
                if (t.equals(fab) || inj.stream().anyMatch(i -> i.text().equals(t))) continue;
                if (t.matches("(?i).*\\b(not|no|never)\\b.*")) continue;
                Matcher m = VERB.matcher(t);
                if (m.find()) {
                    String flipped = t.substring(0, m.end()) + " not" + t.substring(m.end());
                    ((ObjectNode) n).put("text", flipped);
                    inj.add(new Injection(ErrorType.NEGATION, flipped));
                    break;
                }
            }
            // TERM_SWAP: replace the longest word of one key idea with the longest word of another
            List<String> texts = new ArrayList<>();
            key.forEach(n -> texts.add(n.path("text").asText()));
            outerSwap:
            for (int i = 0; i < key.size(); i++) {
                String t = texts.get(i);
                if (t.equals(fab) || inj.stream().anyMatch(x -> x.text().equals(t))) continue;
                String target = longestWord(t);
                if (target == null) continue;
                for (int j = 0; j < texts.size(); j++) {
                    if (j == i || texts.get(j).equals(fab)) continue;
                    String repl = longestWord(texts.get(j));
                    if (repl == null || t.toLowerCase().contains(repl.toLowerCase())) continue;
                    // skip swaps that don't change meaning (same stem) or swap in a filler word
                    if (dev.noella.imboni.text.Text.contentTerms(repl).isEmpty()
                            || dev.noella.imboni.text.Text.stem(repl.toLowerCase()).equals(dev.noella.imboni.text.Text.stem(target.toLowerCase()))) continue;
                    String swapped = t.replaceFirst("\\b" + Pattern.quote(target) + "\\b", Matcher.quoteReplacement(repl));
                    ((ObjectNode) key.get(i)).put("text", swapped);
                    inj.add(new Injection(ErrorType.TERM_SWAP, swapped));
                    break outerSwap;
                }
            }
            last = inj;
            return new Result(JSON.writeValueAsString(root), r.inputTokens(), r.outputTokens(), r.latencyMs());
        } catch (Exception e) {
            last = List.of();
            return r;
        }
    }

    private static String longestWord(String t) {
        String best = null;
        for (String w : t.split("[^A-Za-z]+")) if (w.length() >= 6 && (best == null || w.length() > best.length())) best = w;
        return best;
    }

    @Override
    public String modelName() {
        return delegate.modelName() + "+noise";
    }
}
