package com.docgen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String docsDir,
        String repoRoot,
        String openaiApiKey,
        String defaultModel,
        boolean devMode
) {
    public AppProperties {
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = "gpt-4o-mini";
        }
        if (repoRoot == null || repoRoot.isBlank()) {
            repoRoot = "..";
        }
    }
}
