package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeHashGuardTest {

    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();
    private final NodeHashGuard guard = new NodeHashGuard(indexBuilder);
    private final ModifyApplier modifyApplier = new ModifyApplier(new BookmarkResolver());

    private DocumentSession session;

    @BeforeEach
    void setUp() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        new BookmarkIndexer().ensureBookmarks(document);
        session = new DocumentSession(document);
    }

    @Test
    void singleModifyReportsOnlyThatIdChanged() throws Exception {
        NodeHashGuard.GuardSnapshot snapshot = guard.begin(session, "doc");

        modifyApplier.apply(session.document(),
                new ModifyMutation("modify", "dg_tbl0_r1_c1", "R1C1", 0, "CENTER"));

        Set<String> changed = guard.verify(session, "doc", snapshot, Set.of("dg_tbl0_r1_c1"));
        assertEquals(Set.of("dg_tbl0_r1_c1"), changed);
    }

    @Test
    void extraChangeOutsideTargetSetFailsVerify() throws Exception {
        NodeHashGuard.GuardSnapshot snapshot = guard.begin(session, "doc");

        // Simulated collateral damage: edit a block that is NOT in the targeted set.
        modifyApplier.apply(session.document(),
                new ModifyMutation("modify", "dg_tbl0_r0_c0", "R0C0", 0, "ROGUE"));

        assertThrows(NodeHashGuard.MutationInvariantViolation.class,
                () -> guard.verify(session, "doc", snapshot, Set.of("dg_tbl0_r1_c1")));
    }

    @Test
    void sessionRestoredAfterFailedVerify() throws Exception {
        Map<String, String> before = blockTexts();
        NodeHashGuard.GuardSnapshot snapshot = guard.begin(session, "doc");

        modifyApplier.apply(session.document(),
                new ModifyMutation("modify", "dg_tbl0_r0_c0", "R0C0", 0, "ROGUE"));
        assertThrows(NodeHashGuard.MutationInvariantViolation.class,
                () -> guard.verify(session, "doc", snapshot, Set.of("dg_tbl0_r1_c1")));

        assertEquals(before, blockTexts(), "document must match the begin() snapshot after rollback");
    }

    private Map<String, String> blockTexts() throws Exception {
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : indexBuilder.build(session.document(), "doc").blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }
}
