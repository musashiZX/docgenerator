package com.docgen.document;

import com.docgen.config.AppProperties;
import com.docgen.support.DocumentTestSupport;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentLoaderTest {

    @TempDir
    Path tempDir;

    private DocumentLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
    }

    @Test
    void loadFixtureFromClasspath() throws Exception {
        Path fixture = extractClasspathFixture("fixtures/single-paragraph.docx");
        WordprocessingMLPackage document = loader.load(fixture);

        assertNotNull(document.getMainDocumentPart());
        assertEquals("Hello", DocumentTestSupport.firstParagraphPlainText(document));
    }

    @Test
    void saveAndReloadPreservesFirstParagraphText() throws Exception {
        Path source = tempDir.resolve("source.docx");
        FixtureFactory.writeSingleParagraph(source, "Hello");

        WordprocessingMLPackage document = loader.load(source);
        Path saved = tempDir.resolve("saved.docx");
        loader.save(document, saved);

        WordprocessingMLPackage reloaded = loader.load(saved);
        assertEquals("Hello", DocumentTestSupport.firstParagraphPlainText(reloaded));
    }

    @Test
    void saveAndReloadPreservesWhitespaceOnlyRun() throws Exception {
        Path source = tempDir.resolve("spaced-runs.docx");
        FixtureFactory.writeParagraphWithRuns(source, "Product", " ", "name:");

        WordprocessingMLPackage document = loader.load(source);
        Path saved = tempDir.resolve("saved-spaced-runs.docx");
        loader.save(document, saved);

        WordprocessingMLPackage reloaded = loader.load(saved);
        assertEquals("Product name:", DocumentTestSupport.firstParagraphPlainText(reloaded));
    }

    @Test
    void validateRejectsEmptyBytes() {
        assertThrows(IllegalArgumentException.class, () -> loader.validate(new byte[0]));
    }

    @Test
    void validateAcceptsValidDocx() throws Exception {
        Path source = tempDir.resolve("valid.docx");
        FixtureFactory.writeSingleParagraph(source, "Hello");
        byte[] bytes = Files.readAllBytes(source);
        loader.validate(bytes);
    }

    private static Path extractClasspathFixture(String resourcePath) throws Exception {
        try (InputStream in = DocumentLoaderTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath fixture: " + resourcePath);
            }
            Path target = Files.createTempFile("fixture-", ".docx");
            Files.write(target, in.readAllBytes());
            return target;
        }
    }
}
