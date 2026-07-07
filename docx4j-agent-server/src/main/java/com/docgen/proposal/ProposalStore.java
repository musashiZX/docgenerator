package com.docgen.proposal;

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

/**
 * File-backed proposal persistence under {@code docs/.proposals/{id}/}:
 * {@code proposal.json} plus {@code before.docx} (pre-proposal snapshot).
 */
@Component
public class ProposalStore {

    private final Path proposalsDir;
    private final ObjectMapper mapper;

    public ProposalStore(DocumentLoader documentLoader, ObjectMapper mapper) throws IOException {
        this.proposalsDir = documentLoader.docsDirectory().resolve(".proposals");
        this.mapper = mapper.copy().findAndRegisterModules();
        Files.createDirectories(proposalsDir);
    }

    public void save(Proposal proposal, byte[] beforeDocx) {
        try {
            Path dir = proposalsDir.resolve(proposal.id());
            Files.createDirectories(dir);
            if (beforeDocx != null) {
                Files.write(dir.resolve("before.docx"), beforeDocx);
            }
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("proposal.json").toFile(), proposal);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist proposal " + proposal.id(), e);
        }
    }

    public void update(Proposal proposal) {
        save(proposal, null);
    }

    public Optional<Proposal> load(String id) {
        Path file = proposalsDir.resolve(id).resolve("proposal.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), Proposal.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read proposal " + id, e);
        }
    }

    public byte[] loadBeforeSnapshot(String id) {
        try {
            return Files.readAllBytes(proposalsDir.resolve(id).resolve("before.docx"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read before.docx for proposal " + id, e);
        }
    }

    /** All proposals, newest first; optionally filtered by document name. */
    public List<Proposal> list(String docName) {
        try (Stream<Path> dirs = Files.list(proposalsDir)) {
            List<Proposal> proposals = new ArrayList<>();
            dirs.filter(Files::isDirectory).forEach(dir ->
                    load(dir.getFileName().toString()).ifPresent(proposal -> {
                        if (docName == null || docName.equals(proposal.docName())) {
                            proposals.add(proposal);
                        }
                    }));
            proposals.sort(Comparator.comparing(Proposal::createdAt).reversed());
            return proposals;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list proposals", e);
        }
    }
}
