package com.docgen.document;

import com.docgen.config.AppProperties;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.DeleteApplier;
import com.docgen.mutation.FormatApplier;
import com.docgen.mutation.InsertApplier;
import com.docgen.mutation.ModifyApplier;
import com.docgen.mutation.MutationApplier;
import com.docgen.mutation.MutationValidator;
import com.docgen.mutation.NodeHashGuard;
import com.docgen.mutation.TableStructuralApplier;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real GUANGDELI document has a logo image plus several floating
 * shapes/textboxes (mc:AlternateContent, wp:anchor) — the harder, more
 * realistic case than a simple inline picture. This proves that editing
 * unrelated text/table content elsewhere in the document does not move or
 * corrupt those images: same bytes, same anchor offsets/extents, same count,
 * before and after both a text modify and a structural table-row insert.
 */
class ImagePositionStabilityTest {

    private static final Path REAL_GUANGDELI = Path.of("C:\\Users\\chenz\\Documents\\docGenerator\\docs\\"
            + "21260  GUANGDELI, Dried Beancurd Roll, 25x300g.docx");

    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();
    private final BookmarkIndexer indexer = new BookmarkIndexer();
    private final BookmarkResolver resolver = new BookmarkResolver();
    private final MutationApplier applier = new MutationApplier(
            new MutationValidator(),
            new ModifyApplier(resolver),
            new InsertApplier(resolver, indexer),
            new DeleteApplier(resolver),
            new TableStructuralApplier(resolver, indexer),
            new FormatApplier(resolver),
            new NodeHashGuard(indexBuilder));

    private DocumentLoader loader;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
    }

    @Test
    void textModifyDoesNotMoveOrAlterEmbeddedImages() throws Exception {
        Path path = loader.docsDirectory().resolve("work.docx");
        Files.copy(REAL_GUANGDELI, path);

        WordprocessingMLPackage doc = loader.load(path);
        indexer.ensureBookmarks(doc);
        loader.save(doc, path);
        DocxImageState before = DocxImageState.read(path);

        WordprocessingMLPackage doc2 = loader.load(path);
        StructuralIndex index = indexBuilder.build(doc2, "work.docx");
        applier.apply(new com.docgen.document.DocumentSession(doc2),
                new MutationBatch(1, "img stability", List.of(
                        new ModifyMutation("modify", "dg_tbl0_r2_c1", "300G", 0, "250G"))),
                index);
        loader.save(doc2, path);

        DocxImageState after = DocxImageState.read(path);
        assertEquals(before, after, "text modify must not change embedded image bytes/anchors");
    }

    @Test
    void tableRowInsertDoesNotMoveOrAlterEmbeddedImages() throws Exception {
        Path path = loader.docsDirectory().resolve("work2.docx");
        Files.copy(REAL_GUANGDELI, path);

        WordprocessingMLPackage doc = loader.load(path);
        indexer.ensureBookmarks(doc);
        loader.save(doc, path);
        DocxImageState before = DocxImageState.read(path);

        WordprocessingMLPackage doc2 = loader.load(path);
        StructuralIndex index = indexBuilder.build(doc2, "work2.docx");
        // Anchor on any table_cell row that structurally accepts a 3-cell insert
        // (mirrors the additives-table eval case) — table 2's row 2 in this document.
        applier.apply(new com.docgen.document.DocumentSession(doc2),
                new MutationBatch(1, "img stability structural", List.of(
                        new InsertMutation("insert", "dg_tbl2_r2_c0", "after", "table_row",
                                null, null, List.of("E224", "Netherlands", "Drink additives")))),
                index);
        loader.save(doc2, path);

        DocxImageState after = DocxImageState.read(path);
        assertEquals(before, after, "a structural table-row insert must not change embedded image bytes/anchors");
    }

    /** Media file hashes + drawing anchor offsets/extents, order-preserved. */
    private record DocxImageState(List<String> mediaHashes, List<String> anchors) {

        static DocxImageState read(Path docxPath) throws Exception {
            List<String> mediaHashes = new ArrayList<>();
            String documentXml = null;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(docxPath))) {
                ZipEntry entry;
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().startsWith("word/media/")) {
                        byte[] bytes = zis.readAllBytes();
                        byte[] digest = sha256.digest(bytes);
                        mediaHashes.add(entry.getName() + ":" + bytesToHex(digest));
                    } else if (entry.getName().equals("word/document.xml")) {
                        documentXml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            mediaHashes.sort(String::compareTo);
            return new DocxImageState(mediaHashes, extractAnchors(documentXml == null ? "" : documentXml));
        }

        private static List<String> extractAnchors(String xml) {
            List<String> anchors = new ArrayList<>();
            Pattern anchorPattern = Pattern.compile("<wp:anchor\\b[^>]*>(.*?)</wp:anchor>", Pattern.DOTALL);
            Matcher m = anchorPattern.matcher(xml);
            while (m.find()) {
                anchors.add("anchor:" + summarize(m.group(1)));
            }
            Pattern inlinePattern = Pattern.compile("<wp:inline\\b[^>]*>(.*?)</wp:inline>", Pattern.DOTALL);
            Matcher m2 = inlinePattern.matcher(xml);
            while (m2.find()) {
                anchors.add("inline:" + summarize(m2.group(1)));
            }
            return anchors;
        }

        private static String summarize(String block) {
            String posH = firstGroup(block, "<wp:positionH[^>]*>.*?<wp:posOffset>(-?\\d+)</wp:posOffset>");
            String posV = firstGroup(block, "<wp:positionV[^>]*>.*?<wp:posOffset>(-?\\d+)</wp:posOffset>");
            String cx = firstGroup(block, "<wp:extent cx=\"(\\d+)\"");
            String cy = firstGroup(block, "<wp:extent[^>]*cy=\"(\\d+)\"");
            String rid = firstGroup(block, "r:embed=\"(rId\\d+)\"");
            return "posH=" + posH + " posV=" + posV + " cx=" + cx + " cy=" + cy + " rid=" + rid;
        }

        private static String firstGroup(String text, String pattern) {
            Matcher m = Pattern.compile(pattern, Pattern.DOTALL).matcher(text);
            return m.find() ? m.group(1) : "null";
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}
