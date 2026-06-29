package com.docgen.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTraceFilter extends OncePerRequestFilter {

    private final TraceWriter traceWriter;
    private final ObjectMapper mapper;

    public ApiTraceFilter(TraceWriter traceWriter) {
        this.traceWriter = traceWriter;
        this.mapper = TraceFiles.mapper();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || !traceWriter.enabled();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("method", request.getMethod());
        meta.put("path", request.getRequestURI());
        if (request.getQueryString() != null) {
            meta.put("query", request.getQueryString());
        }

        OperationTraceSession session;
        try {
            session = traceWriter.openSession(slug(request.getRequestURI()), meta);
        } catch (IOException ex) {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
            wrappedResponse.copyBodyToResponse();
            return;
        }

        wrappedRequest.setAttribute(OperationRecorder.REQUEST_ATTR, session);
        response.setHeader("X-Trace-Id", session.traceId());
        response.setHeader("X-Trace-Dir", session.directory().toString());

        long started = System.currentTimeMillis();
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
            session.record(OperationType.HTTP_REQUEST, "HTTP " + request.getMethod() + " completed",
                    Map.of("status", wrappedResponse.getStatus(),
                            "duration_ms", System.currentTimeMillis() - started));
            session.markSuccess(Map.of("http_status", wrappedResponse.getStatus()));
        } catch (Exception ex) {
            session.markFailure(ex.getMessage(), Map.of("http_status", wrappedResponse.getStatus()));
            throw ex;
        } finally {
            saveHttpArtifacts(session, wrappedRequest, wrappedResponse);
            session.close();
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void saveHttpArtifacts(
            OperationTraceSession session,
            ContentCachingRequestWrapper request,
            ContentCachingResponseWrapper response) {
        try {
            byte[] reqBody = request.getContentAsByteArray();
            if (reqBody.length > 0) {
                session.writeArtifact("request.json", parseBody(reqBody, request.getContentType()));
            }
            byte[] resBody = response.getContentAsByteArray();
            if (resBody.length > 0) {
                Object body = parseBody(resBody, response.getContentType());
                if (shouldExternalise(body)) {
                    session.writeArtifact("response_preview.json", body);
                } else {
                    session.writeArtifact("response.json", body);
                }
            }
        } catch (Exception ignored) {
            // tracing must not break the API
        }
    }

    private Object parseBody(byte[] body, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("json")) {
            try {
                return mapper.readValue(body, Object.class);
            } catch (Exception ex) {
                return Map.of("_raw", new String(body, StandardCharsets.UTF_8));
            }
        }
        if (ct.contains("multipart")) {
            return Map.of("_type", "multipart", "bytes", body.length);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.length() > 4000) {
            return Map.of("_type", "text", "bytes", body.length, "preview", text.substring(0, 4000));
        }
        return Map.of("_type", "text", "body", text);
    }

    private boolean shouldExternalise(Object body) {
        if (body instanceof Map<?, ?> map && map.containsKey("preview_html")) {
            Object html = map.get("preview_html");
            return html instanceof String s && s.length() > 2000;
        }
        return false;
    }

    private static String slug(String path) {
        String slug = path.replaceAll("^/api/?", "")
                .replace('/', '_')
                .replaceAll("[^a-zA-Z0-9_-]", "");
        return slug.isBlank() ? "api" : slug.substring(0, Math.min(slug.length(), 40));
    }
}
