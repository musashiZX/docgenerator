package com.docgen.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Token counts from one or more chat/completions calls, summed across retries. */
public record TokenUsage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}
