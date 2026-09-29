package com.docgen.llm;

import java.util.Map;

/**
 * Approximate USD-per-1M-token rates for cost monitoring in evals/logs. These
 * are estimates, not billing-accurate — update {@link #RATES} as pricing
 * changes or new models are added. Unknown models return null (tokens are
 * still reported; cost just can't be computed).
 */
public final class LlmPricing {

    private LlmPricing() {
    }

    private record Rate(double inputPer1M, double outputPer1M) {
    }

    private static final Map<String, Rate> RATES = Map.ofEntries(
            Map.entry("gpt-4o-mini", new Rate(0.15, 0.60)),
            Map.entry("gpt-4.1-mini", new Rate(0.40, 1.60)),
            Map.entry("gpt-4.1-nano", new Rate(0.10, 0.40)),
            Map.entry("gpt-4o", new Rate(2.50, 10.00)),
            // Gemini flash-lite tier — carried over from the last confirmed
            // 2.x flash-lite pricing as an estimate; 3.x pricing not yet
            // confirmed against Google's published rate card.
            Map.entry("gemini-3.5-flash-lite", new Rate(0.10, 0.40)),
            Map.entry("gemini-2.5-flash-lite", new Rate(0.10, 0.40)),
            Map.entry("gemini-flash-lite-latest", new Rate(0.10, 0.40)),
            Map.entry("gemini-3.1-flash-lite", new Rate(0.10, 0.40)),
            // No LLM call made (deterministic find/replace planner).
            Map.entry(ComplianceClient.BULK_REPLACE_MODEL, new Rate(0, 0)));

    /** Null when the model isn't in the rate table or usage is null. */
    public static Double costUsd(String model, TokenUsage usage) {
        if (model == null || usage == null) {
            return null;
        }
        Rate rate = RATES.get(model);
        if (rate == null) {
            return null;
        }
        return usage.promptTokens() / 1_000_000.0 * rate.inputPer1M()
                + usage.completionTokens() / 1_000_000.0 * rate.outputPer1M();
    }
}
