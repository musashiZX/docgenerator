package com.docgen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Self-hosted OnlyOffice Document Server used for manual (non-AI) editing —
 * a second surface alongside the propose/approve chat flow, for direct
 * WYSIWYG edits. See docs/ONLYOFFICE.md for setup.
 */
@ConfigurationProperties(prefix = "app.onlyoffice")
public record OnlyOfficeProperties(
        boolean enabled,
        String documentServerUrl,
        String callbackBaseUrl,
        String jwtSecret
) {
    public OnlyOfficeProperties {
        if (documentServerUrl == null || documentServerUrl.isBlank()) {
            documentServerUrl = "http://localhost:8082";
        }
        if (callbackBaseUrl == null || callbackBaseUrl.isBlank()) {
            callbackBaseUrl = "http://host.docker.internal:8081";
        }
        documentServerUrl = stripTrailingSlash(documentServerUrl);
        callbackBaseUrl = stripTrailingSlash(callbackBaseUrl);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public boolean jwtEnabled() {
        return jwtSecret != null && !jwtSecret.isBlank();
    }
}
