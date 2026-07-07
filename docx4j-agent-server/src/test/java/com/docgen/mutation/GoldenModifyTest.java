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
 * Golden scenario: input.docx + mutation.json → expected-text.json.
 * Text-per-id comparison for now; binary/XML docx comparison comes with
 * the Stage 6 parity harness.
 */
class GoldenModifyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

    @Test
    void modifyCellScenarioMatchesExpectedTexts() throws Exception {
        Path goldenDir = FixtureGeneratorTest.moduleRoot()
                .resolve("src/test/resources/golden/modify-cell");
        Path inputDocx = goldenDir.resolve("input.docx");
        if (!Files.exists(inputDocx)) {
            FixtureFactory.writeTable3x3(inputDocx);
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
                new NodeHashGuard(indexBuilder));
        applier.apply(session, batch, index);

        Map<String, String> actual = new HashMap<>();
        for (BlockDescriptor block : indexBuilder.build(session.document(), "input.docx").blocks()) {
            actual.put(block.targetId(), block.text());
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()),
                    "text mismatch for " + entry.getKey());
        }
    }
}
