package com.docgen.onlyoffice;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the async gap between "we asked the Document Server to force-save"
 * and "our own /callback endpoint actually received and persisted the
 * result" — a forcesave request from the CommandService API returns as soon
 * as it's queued, not once the callback has landed, so
 * {@link OnlyOfficeForceSaveController} waits on the future this creates and
 * {@link OnlyOfficeCallbackController} completes it once the file is written.
 */
@Component
public class OnlyOfficeSaveCoordinator {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /** Call before triggering a forcesave; returns the future to await. */
    public CompletableFuture<Void> awaitNextSave(String docName) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pending.put(docName, future);
        return future;
    }

    /** Called by the callback controller once a save has been persisted (or definitively skipped). */
    public void complete(String docName) {
        CompletableFuture<Void> future = pending.remove(docName);
        if (future != null) {
            future.complete(null);
        }
    }

    public void completeExceptionally(String docName, Throwable error) {
        CompletableFuture<Void> future = pending.remove(docName);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }
}
