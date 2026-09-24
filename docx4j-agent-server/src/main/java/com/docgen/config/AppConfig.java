package com.docgen.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, LlmProperties.class, OnlyOfficeProperties.class})
public class AppConfig {
}
