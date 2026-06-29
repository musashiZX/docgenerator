package com.docgen.trace;

/**
 * Canonical operation types for the audit trail.
 * Designed to support future version-control replay and rollback.
 */
public enum OperationType {
    HTTP_REQUEST,
    AGENT_TURN_START,
    AGENT_TURN_END,
    LLM_ROUND,
    TOOL_DISPATCH,
    TOOL_RESULT,
    DOCUMENT_SNAPSHOT,
    DOCUMENT_SAVE,
    DOCUMENT_UPLOAD,
    DOCUMENT_CREATE,
    ERROR
}
