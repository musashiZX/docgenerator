package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.support.FixtureFactory;
import com.docgen.support.FixtureGeneratorTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden scenario for insert + delete: the resulting id → text map must match
 * expected-text.json exactly (strict equality also proves the deleted block is gone).
 */
class GoldenInsertDeleteTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

    @Test
    void insertDeleteScenarioMatchesExpectedTexts() throws Exception {
        Path goldenDir = FixtureGeneratorTest.moduleRoot()
                .resolve("src/test/resources/golden/insert-delete");
        Path inputDocx = goldenDir.resolve("input.docx");
        if (!Files.exists(inputDocx)) {
            Files.createDirectories(goldenDir);
            FixtureFactory.writeParagraphs(inputDocx, "Alpha", "Bravo", "Charlie");
        }

        MutationBatch batch = mapper.readValue(
                goldenDir.resolve("mutation.json").toFile(), MutationBatch.class);
        Map<String, String> expected = mapper.readValue(
                goldenDir.resolve("expected-text.json").toFile(),
                new TypeReference<Map<String, String>>() {});

        WordprocessingMLPackage document = WordprocessingMLPackage.load(inputDocx.toFile());
        new BookmarkIndexer().ensureBookmarks(document);
        StructuralIndex index = indexBuilder.build(document, "input.docx");
        DocumentSession session = new DocumentSession(document);

        MutationApplier applier = new MutationApplier(
                new MutationValidator(),
                new ModifyApplier(new BookmarkResolver()),
                new InsertApplier(new BookmarkResolver(), new BookmarkIndexer()),
                new DeleteApplier(new BookmarkResolver()),
                new TableStructuralApplier(new BookmarkResolver(), new BookmarkIndexer()),
                new FormatApplier(new BookmarkResolver()),
                new NodeHashGuard(indexBuilder));
        applier.apply(session, batch, index);

        Map<String, String> actual = new HashMap<>();
        for (BlockDescriptor block : indexBuilder.build(session.document(), "input.docx").blocks()) {
            actual.put(block.targetId(), block.text());
        }
        assertEquals(expected, actual, "full id→text map must match (deleted block absent)");
    }
}
