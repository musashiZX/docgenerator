package com.docgen.trace;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(
        int sequence,
        Instant timestamp,
        OperationType type,
        String summary,
        Map<String, Object> detail
) {}
