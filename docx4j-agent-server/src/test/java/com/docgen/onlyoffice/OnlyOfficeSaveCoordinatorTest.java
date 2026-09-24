package com.docgen.onlyoffice;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlyOfficeSaveCoordinatorTest {

    @Test
    void completeResolvesTheWaitingFuture() throws Exception {
        OnlyOfficeSaveCoordinator coordinator = new OnlyOfficeSaveCoordinator();
        CompletableFuture<Void> future = coordinator.awaitNextSave("doc.docx");

        assertFalse(future.isDone());
        coordinator.complete("doc.docx");

        future.get(1, TimeUnit.SECONDS); // must not throw/timeout
        assertTrue(future.isDone());
    }

    @Test
    void completeExceptionallyFailsTheWaitingFuture() {
        OnlyOfficeSaveCoordinator coordinator = new OnlyOfficeSaveCoordinator();
        CompletableFuture<Void> future = coordinator.awaitNextSave("doc.docx");

        coordinator.completeExceptionally("doc.docx", new IllegalStateException("boom"));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void completeForAnUnregisteredDocIsANoOp() {
        OnlyOfficeSaveCoordinator coordinator = new OnlyOfficeSaveCoordinator();
        coordinator.complete("never-registered.docx"); // must not throw
    }

    @Test
    void secondAwaitReplacesTheFirstWaiter() {
        // Registering a new wait for the same doc (e.g. re-opening the editor
        // after a prior save) must not leave the old future dangling forever.
        OnlyOfficeSaveCoordinator coordinator = new OnlyOfficeSaveCoordinator();
        CompletableFuture<Void> first = coordinator.awaitNextSave("doc.docx");
        CompletableFuture<Void> second = coordinator.awaitNextSave("doc.docx");

        coordinator.complete("doc.docx");

        assertTrue(second.isDone());
        assertFalse(first.isDone(), "the superseded future is simply orphaned, not completed");
    }
}
