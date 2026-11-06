package dev.noella.imboni.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.noella.imboni.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a model so the whole pipeline runs with no API key.
 * It reads the same prompt a real model gets and builds the lesson by <b>copying</b> the most
 * relevant source sentences (extractive), so its facts are grounded by construction.
 *
 * <p>That makes it useless for measuring a real model's hallucination rate. The eval uses it to
 * exercise the pipeline and wraps it in {@code NoisyLlm} to inject known errors and measure whether
 * the checker catches them. Real-model numbers require provider=openai|anthropic.
 */
public class OfflineExtractiveLlm implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SOURCE = Pattern.compile("(?ms)^\\[(S\\d+)] \\((.*?)\\)\\n(.*?)(?=^\\[S\\d+] \\(|\\z)");
    private static final Pattern FIELD = Pattern.compile("(?m)^(topic|grade_level|duration_minutes|learning_goals): (.*)$");

    @Override
    public Result complete(Request req) {
        long t0 = System.nanoTime();
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher fm = FIELD.matcher(req.user());
        while (fm.find()) fields.putIfAbsent(fm.group(1), fm.group(2).strip());
        String topic = fields.getOrDefault("topic", "");
        Set<String> qTerms = Text.contentTermSet(topic + " " + fields.getOrDefault("learning_goals", ""));

        record Sent(String sourceId, String label, String text, double score, int order) {}
        List<Sent> sents = new ArrayList<>();
        Matcher m = SOURCE.matcher(req.user());
        int order = 0;
        while (m.find()) {
            String id = m.group(1);
            for (String s : Text.sentences(m.group(3))) {
                if (s.startsWith("|") || s.startsWith("#") || Text.wordCount(s) < 3) continue;
                Set<String> t = Text.contentTermSet(s);
                double score = qTerms.stream().filter(t::contains).count() + 0.01 * Math.min(t.size(), 20);
                sents.add(new Sent(id, m.group(2), s, score, order++));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", topic.isBlank() ? "Lesson" : capitalize(topic) + " (" + fields.getOrDefault("grade_level", "") + ")");
        Map<String, List<Map<String, Object>>> sections = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();

        List<Sent> ranked = sents.stream().filter(s -> s.score >= 1)
                .sorted(Comparator.comparingDouble(Sent::score).reversed().thenComparingInt(Sent::order)).toList();
        List<Sent> key = ranked.stream().filter(s -> !isEquation(s.text)).limit(4).toList();
        List<Sent> examples = ranked.stream().filter(s -> isEquation(s.text) || !Text.numbers(s.text).isEmpty())
                .filter(s -> !key.contains(s)).limit(2).toList();

        if (key.isEmpty()) gaps.add("The provided passages do not directly address: " + topic);
        String firstSrc = key.isEmpty() ? (sents.isEmpty() ? "S1" : sents.get(0).sourceId) : key.get(0).sourceId;

        sections.put("objectives", List.of(stmt("Students can explain the key ideas of " + topic + " using the class material.",
                firstSrc, "instruction")));
        sections.put("key_ideas", key.stream().map(s -> stmt(s.text, s.sourceId, "fact")).toList());
        sections.put("worked_example", examples.stream().map(s -> stmt(s.text, s.sourceId, "fact")).toList());
        List<Map<String, Object>> activity = new ArrayList<>();
        activity.add(stmt("In pairs, students read the cited passage and restate each key idea in their own words.", firstSrc, "instruction"));
        if (!examples.isEmpty())
            activity.add(stmt("Students work through the worked example step by step, then explain each step to a partner.",
                    examples.get(0).sourceId, "instruction"));
        sections.put("activity", activity);
        sections.put("check_for_understanding", key.stream().limit(2)
                .map(s -> stmt("Ask students to explain this idea from the passage: \"" + shorten(s.text) + "\"", s.sourceId, "instruction"))
                .toList());
        out.put("sections", sections);
        out.put("gaps", gaps);

        String text;
        try {
            text = JSON.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Result(text, Text.estimateTokens(req.system() + req.user()), Text.estimateTokens(text),
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static Map<String, Object> stmt(String text, String source, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("sources", List.of(source));
        m.put("kind", kind);
        return m;
    }

    private static boolean isEquation(String s) {
        return (s.contains("=") || s.contains("→")) && s.length() <= 90;
    }

    private static String shorten(String s) {
        return s.length() > 90 ? s.substring(0, 87) + "..." : s;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public String modelName() {
        return "offline-extractive";
    }
}
