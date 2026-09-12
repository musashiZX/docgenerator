package com.docgen.document;

import com.docgen.config.AppProperties;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.model.FormatMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.mutation.FormatApplier;
import com.docgen.mutation.ModifyApplier;
import com.docgen.support.FixtureFactory;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.HTMLSettings;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Isolates the WRONG_DOCUMENT_ERR crash found in manual testing. The
 * in-memory-only version of this test (no save/reload) did NOT reproduce
 * it, so this version goes through the REAL app flow: save to disk, reload,
 * apply, save, reload — matching exactly what ProposalService does between
 * propose and approve.
 */
class DocumentPreviewServiceRealBugTest {

    @TempDir
    Path tempDir;

    private DocumentLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
    }

    private void renderToHtml(WordprocessingMLPackage document) throws Exception {
        HTMLSettings settings = Docx4J.createHTMLSettings();
        settings.setOpcPackage(document);
        settings.setImageDirPath("");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Docx4J.toHTML(settings, out, Docx4J.FLAG_EXPORT_PREFER_XSL);
    }

    @Test
    void fullSaveReloadCycleMatchingRealAppFlow() throws Exception {
        Path path = loader.docsDirectory().resolve("work.docx");
        FixtureFactory.table2x2WithEmptyCell().save(path.toFile());

        // propose(): load, ensure bookmarks, save (persists stable ids)
        WordprocessingMLPackage doc1 = loader.load(path);
        new BookmarkIndexer().ensureBookmarks(doc1);
        loader.save(doc1, path);

        // approve() for the modify: load fresh, apply, save
        WordprocessingMLPackage doc2 = loader.load(path);
        new ModifyApplier(new BookmarkResolver()).apply(
                doc2, new ModifyMutation("modify", "dg_tbl0_r0_c1", "", 0, "New content"));
        loader.save(doc2, path);

        // approve() for the format: load fresh, apply, save
        WordprocessingMLPackage doc3 = loader.load(path);
        new FormatApplier(new BookmarkResolver()).apply(
                doc3, new FormatMutation("format", "dg_tbl0_r0_c1", "New content", 0, true, null, null, null, "center"));
        loader.save(doc3, path);

        // preview(): load fresh, render to HTML — this is what actually crashed
        WordprocessingMLPackage doc4 = loader.load(path);
        renderToHtml(doc4);
    }

    @Test
    void realGuangdeliDocumentBaselineNoEditsAtAll() throws Exception {
        // Isolates whether the crash is pre-existing in this document
        // (unrelated to any of my changes) or actually caused by the new
        // empty-cell modify/format code path.
        Path source = Path.of("C:\\Users\\chenz\\Documents\\docGenerator\\docs\\"
                + "21260  GUANGDELI, Dried Beancurd Roll, 25x300g.docx");
        Path path = loader.docsDirectory().resolve("baseline.docx");
        Files.copy(source, path);

        WordprocessingMLPackage doc1 = loader.load(path);
        new BookmarkIndexer().ensureBookmarks(doc1);
        loader.save(doc1, path);

        WordprocessingMLPackage doc2 = loader.load(path);
        renderToHtml(doc2); // no modify, no format — just load/save/reload/render
    }

    @Test
    void realGuangdeliDocumentEmptyCellModifyAndFormatThenRender() throws Exception {
        Path source = Path.of("C:\\Users\\chenz\\Documents\\docGenerator\\docs\\"
                + "21260  GUANGDELI, Dried Beancurd Roll, 25x300g.docx");
        Path path = loader.docsDirectory().resolve("real.docx");
        Files.copy(source, path);

        WordprocessingMLPackage doc1 = loader.load(path);
        new BookmarkIndexer().ensureBookmarks(doc1);
        loader.save(doc1, path);

        WordprocessingMLPackage doc1b = loader.load(path);
        com.docgen.index.StructuralIndexBuilder indexBuilder = new com.docgen.index.StructuralIndexBuilder();
        Map<String, String> texts = indexBuilder.build(doc1b, "real.docx").blocks().stream()
                .collect(Collectors.toMap(
                        com.docgen.model.BlockDescriptor::targetId,
                        b -> b.text() == null ? "" : b.text()));
        String emptyCellId = texts.entrySet().stream()
                .filter(e -> e.getKey().startsWith("dg_tbl") && e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();
        System.out.println("Using empty cell: " + emptyCellId);

        WordprocessingMLPackage doc2 = loader.load(path);
        new ModifyApplier(new BookmarkResolver()).apply(
                doc2, new ModifyMutation("modify", emptyCellId, "", 0, "New content"));
        loader.save(doc2, path);

        WordprocessingMLPackage doc3 = loader.load(path);
        new FormatApplier(new BookmarkResolver()).apply(
                doc3, new FormatMutation("format", emptyCellId, "New content", 0, true, null, null, null, "center"));
        loader.save(doc3, path);

        WordprocessingMLPackage doc4 = loader.load(path);
        renderToHtml(doc4);
    }

    @Test
    void justModifySaveReloadThenRender() throws Exception {
        // Narrower: is the modify's save/reload cycle alone enough, without format at all?
        Path path = loader.docsDirectory().resolve("work2.docx");
        FixtureFactory.table2x2WithEmptyCell().save(path.toFile());

        WordprocessingMLPackage doc1 = loader.load(path);
        new BookmarkIndexer().ensureBookmarks(doc1);
        loader.save(doc1, path);

        WordprocessingMLPackage doc2 = loader.load(path);
        new ModifyApplier(new BookmarkResolver()).apply(
                doc2, new ModifyMutation("modify", "dg_tbl0_r0_c1", "", 0, "New content"));
        loader.save(doc2, path);

        WordprocessingMLPackage doc3 = loader.load(path);
        renderToHtml(doc3);
    }
}
