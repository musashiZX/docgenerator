package com.docgen.config;



import org.springframework.boot.context.properties.ConfigurationProperties;



@ConfigurationProperties(prefix = "app")

public record AppProperties(

        String docsDir,

        String openaiApiKey,

        String defaultModel,

        int maxToolRounds,

        String traceDir,

        boolean traceEnabled

) {}


