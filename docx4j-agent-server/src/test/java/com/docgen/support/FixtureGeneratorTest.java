package com.docgen.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Generates committed fixtures under src/test/resources/fixtures/. */
class FixtureGeneratorTest {

    @Test
    void generateSingleParagraphFixture() throws Exception {
        Path moduleDir = moduleRoot();
        Path fixtures = moduleDir.resolve("src/test/resources/fixtures/single-paragraph.docx");
        Files.createDirectories(fixtures.getParent());
        FixtureFactory.writeSingleParagraph(fixtures, "Hello");
    }

    @Test
    void generateTable3x3Fixture() throws Exception {
        Path moduleDir = moduleRoot();
        Path fixtures = moduleDir.resolve("src/test/resources/fixtures/table-3x3.docx");
        Files.createDirectories(fixtures.getParent());
        FixtureFactory.writeTable3x3(fixtures);
    }

    static Path moduleRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("pom.xml"))) {
            return cwd;
        }
        Path nested = cwd.resolve("docx4j-agent-server");
        if (Files.exists(nested.resolve("pom.xml"))) {
            return nested;
        }
        throw new IllegalStateException("Cannot locate docx4j-agent-server module directory.");
    }
}
