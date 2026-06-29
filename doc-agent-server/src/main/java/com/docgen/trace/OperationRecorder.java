package com.docgen.trace;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Facade for recording operations anywhere in the request lifecycle.
 * Future features (version commits, batch jobs) open their own session via {@link TraceWriter}.
 */
@Component
public class OperationRecorder {

    public static final String REQUEST_ATTR = "docgen.operationTraceSession";

    private final TraceWriter traceWriter;

    public OperationRecorder(TraceWriter traceWriter) {
        this.traceWriter = traceWriter;
    }

    public OperationTraceSession current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            Object session = request.getAttribute(REQUEST_ATTR);
            if (session instanceof OperationTraceSession traceSession) {
                return traceSession;
            }
        }
        return OperationTraceSession.noop();
    }

    public OperationTraceSession openStandalone(String kind, Map<String, Object> meta) {
        try {
            return traceWriter.openSession(kind, meta);
        } catch (Exception ex) {
            return OperationTraceSession.noop();
        }
    }

    public void record(OperationType type, String summary, Map<String, Object> detail) {
        current().record(type, summary, detail);
    }

    public void record(OperationType type, String summary) {
        record(type, summary, Map.of());
    }
}
