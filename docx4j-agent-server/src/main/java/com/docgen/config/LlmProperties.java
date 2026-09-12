package com.docgen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM provider selection. Temporary switch so the eval suite can run on Gemini
 * while the OpenAI key is rate-limited. Set {@code app.llm.provider=gemini}
 * (env {@code LLM_PROVIDER=gemini}) and supply {@code GEMINI_API_KEY}.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        String provider,
        String geminiApiKey,
        String geminiModel,
        String geminiBaseUrl
) {
    public LlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "openai";
        }
        if (geminiModel == null || geminiModel.isBlank()) {
            geminiModel = "gemini-3.5-flash-lite";
        }
        if (geminiBaseUrl == null || geminiBaseUrl.isBlank()) {
            geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        }
    }

    public boolean isGemini() {
        return "gemini".equalsIgnoreCase(provider);
    }
}
