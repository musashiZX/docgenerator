package com.docgen.api;

import com.docgen.document.DocumentLoader;
import com.docgen.index.BookmarkResolver;
import com.docgen.mutation.MutationValidator;
import com.docgen.mutation.NodeHashGuard;
import com.docgen.mutation.StaleTargetException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DocumentLoader.DocumentNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(
            DocumentLoader.DocumentNotFoundException ex,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        log.warn("[trace:{}] Document not found: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", ex.getMessage(),
                "trace_id", traceId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        log.warn("[trace:{}] Bad request: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", ex.getMessage(),
                "trace_id", traceId));
    }

    @ExceptionHandler(MutationValidator.MutationValidationException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(
            MutationValidator.MutationValidationException ex,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        List<Map<String, Object>> details = ex.errors().stream()
                .map(error -> Map.<String, Object>of(
                        "mutation_index", error.mutationIndex(),
                        "code", error.code(),
                        "message", error.message()))
                .toList();
        log.warn("[trace:{}] Validation failed: {}", traceId, details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "validation_failed",
                        "details", details,
                        "trace_id", traceId));
    }

    @ExceptionHandler(BookmarkResolver.UnknownTargetException.class)
    public ResponseEntity<Map<String, String>> unknownTarget(
            BookmarkResolver.UnknownTargetException ex,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        log.warn("[trace:{}] Unknown target: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", ex.getMessage(),
                "trace_id", traceId));
    }

    @ExceptionHandler(StaleTargetException.class)
    public ResponseEntity<Map<String, String>> staleTarget(
            StaleTargetException ex,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        log.warn("[trace:{}] Stale target: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.getMessage(),
                "trace_id", traceId));
    }

    @ExceptionHandler(NodeHashGuard.MutationInvariantViolation.class)
    public ResponseEntity<Map<String, Object>> invariantViolation(
            NodeHashGuard.MutationInvariantViolation ex,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        log.error("[trace:{}] Invariant violation changed={} targeted={} missing={} unexpected={}",
                traceId, ex.changedIds(), ex.targetedIds(),
                ex.missingTargetedIds(), ex.unexpectedChangedIds());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "invariant_violation",
                "message", ex.getMessage(),
                "changed_ids", ex.changedIds(),
                "targeted_ids", ex.targetedIds(),
                "missing_targeted_ids", ex.missingTargetedIds(),
                "unexpected_changed_ids", ex.unexpectedChangedIds(),
                "trace_id", traceId));
    }
}
