package com.docgen.document;

import com.docgen.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Copy a pristine golden .docx into the server working docs directory. */
@Service
public class BaselineRestoreService {

    private final DocumentLoader documentLoader;
    private final Path repoRoot;

    public BaselineRestoreService(DocumentLoader documentLoader, AppProperties appProperties) {
        this.documentLoader = documentLoader;
        Path configured = Path.of(appProperties.repoRoot());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        this.repoRoot = configured.toAbsolutePath().normalize();
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public Path resolveGolden(String goldenRelativePath) {
        if (goldenRelativePath == null || goldenRelativePath.isBlank()) {
            throw new IllegalArgumentException("golden_path is required.");
        }
        Path golden = repoRoot.resolve(goldenRelativePath).normalize();
        if (!golden.startsWith(repoRoot)) {
            throw new IllegalArgumentException("Invalid golden path.");
        }
        if (!Files.isRegularFile(golden)) {
            throw new DocumentLoader.DocumentNotFoundException(
                    "Golden document not found: " + golden);
        }
        return golden;
    }

    public Path workingPath(String docName) {
        return documentLoader.docsDirectory()
                .resolve(DocumentLoader.sanitizeFilename(docName));
    }

    public void restore(String docName, String goldenRelativePath) throws IOException {
        Path golden = resolveGolden(goldenRelativePath);
        Path working = workingPath(docName);
        Files.createDirectories(working.getParent());
        Files.copy(golden, working, StandardCopyOption.REPLACE_EXISTING);
    }
}
