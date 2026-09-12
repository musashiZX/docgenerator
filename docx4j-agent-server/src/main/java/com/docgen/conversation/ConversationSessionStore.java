package com.docgen.conversation;

import com.docgen.document.DocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** File-backed session persistence under {@code docs/.sessions/{sessionId}.json}. */
@Component
public class ConversationSessionStore {

    private final Path sessionsDir;
    private final ObjectMapper mapper;

    public ConversationSessionStore(DocumentLoader documentLoader, ObjectMapper mapper) throws IOException {
        this.sessionsDir = documentLoader.docsDirectory().resolve(".sessions");
        this.mapper = mapper.copy().findAndRegisterModules();
        Files.createDirectories(sessionsDir);
    }

    public void save(ConversationSession session) {
        try {
            Path file = sessionsDir.resolve(session.id() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), session);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist session " + session.id(), e);
        }
    }

    public Optional<ConversationSession> load(String id) {
        Path file = sessionsDir.resolve(id + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), ConversationSession.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read session " + id, e);
        }
    }

    /** All sessions for a document, newest first. */
    public List<ConversationSession> list(String docName) {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<ConversationSession> sessions = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".json")).forEach(f -> {
                String id = f.getFileName().toString().replaceFirst("\\.json$", "");
                load(id).ifPresent(session -> {
                    if (docName == null || docName.equals(session.docName())) {
                        sessions.add(session);
                    }
                });
            });
            sessions.sort(Comparator.comparing(ConversationSession::createdAt).reversed());
            return sessions;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list sessions", e);
        }
    }

    public Optional<ConversationSession> findActive(String docName) {
        return list(docName).stream()
                .filter(s -> s.status() == ConversationStatus.ACTIVE)
                .findFirst();
    }
}
