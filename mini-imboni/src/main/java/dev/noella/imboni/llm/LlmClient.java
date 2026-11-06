package dev.noella.imboni.llm;

/** One chat-style completion. Implementations must report token usage (estimated if the provider doesn't). */
public interface LlmClient {

    Result complete(Request request);

    String modelName();

    record Request(String system, String user, int maxOutputTokens) {}

    record Result(String text, int inputTokens, int outputTokens, long latencyMs) {}
}
