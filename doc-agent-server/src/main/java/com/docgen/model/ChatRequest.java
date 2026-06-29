package com.docgen.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank String sessionId,
        @NotBlank String docName,
        @NotBlank String message,
        String model,
        String selectedText
) {}
