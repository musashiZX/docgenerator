package com.docgen.api;

import com.docgen.document.DocumentLoader;
import com.docgen.index.BookmarkResolver;
import com.docgen.mutation.MutationValidator;
import com.docgen.mutation.NodeHashGuard;
import com.docgen.mutation.StaleTargetException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DocumentLoader.DocumentNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(DocumentLoader.DocumentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MutationValidator.MutationValidationException.class)
    public ResponseEntity<Map<String, Object>> validationFailed(
            MutationValidator.MutationValidationException ex) {
        List<Map<String, Object>> details = ex.errors().stream()
                .map(error -> Map.<String, Object>of(
                        "mutation_index", error.mutationIndex(),
                        "code", error.code(),
                        "message", error.message()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "validation_failed", "details", details));
    }

    @ExceptionHandler(BookmarkResolver.UnknownTargetException.class)
    public ResponseEntity<Map<String, String>> unknownTarget(BookmarkResolver.UnknownTargetException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(StaleTargetException.class)
    public ResponseEntity<Map<String, String>> staleTarget(StaleTargetException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(NodeHashGuard.MutationInvariantViolation.class)
    public ResponseEntity<Map<String, Object>> invariantViolation(
            NodeHashGuard.MutationInvariantViolation ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "invariant_violation",
                "message", ex.getMessage(),
                "changed_ids", ex.changedIds(),
                "targeted_ids", ex.targetedIds()));
    }
}
