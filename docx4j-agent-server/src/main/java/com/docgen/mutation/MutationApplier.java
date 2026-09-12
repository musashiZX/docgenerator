package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.model.ApplyResult;
import com.docgen.model.DeleteMutation;
import com.docgen.model.FormatMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batch orchestrator: validate → hash snapshot → apply each mutation →
 * verify invariant. Any failure restores the session to its pre-batch state.
 * Inserted blocks join the targeted set under their new id; deleted blocks
 * count as changed because their hash disappears.
 */
@Service
public class MutationApplier {

    private static final Logger log = LoggerFactory.getLogger(MutationApplier.class);

    private final MutationValidator validator;
    private final ModifyApplier modifyApplier;
    private final InsertApplier insertApplier;
    private final DeleteApplier deleteApplier;
    private final TableStructuralApplier tableStructuralApplier;
    private final FormatApplier formatApplier;
    private final NodeHashGuard hashGuard;

    public MutationApplier(
            MutationValidator validator,
            ModifyApplier modifyApplier,
            InsertApplier insertApplier,
            DeleteApplier deleteApplier,
            TableStructuralApplier tableStructuralApplier,
            FormatApplier formatApplier,
            NodeHashGuard hashGuard) {
        this.validator = validator;
        this.modifyApplier = modifyApplier;
        this.insertApplier = insertApplier;
        this.deleteApplier = deleteApplier;
        this.tableStructuralApplier = tableStructuralApplier;
        this.formatApplier = formatApplier;
        this.hashGuard = hashGuard;
    }

    public ApplyResult apply(DocumentSession session, MutationBatch batch, StructuralIndex index)
            throws Exception {

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        if (!errors.isEmpty()) {
            throw new MutationValidator.MutationValidationException(errors);
        }

        NodeHashGuard.GuardSnapshot snapshot = hashGuard.begin(session, index.documentId());

        Set<String> targetedIds = new HashSet<>();
        Set<String> createdIds = new HashSet<>();
        Set<String> formattedIds = new HashSet<>();
        Map<String, String> insertChainTail = new HashMap<>();
        Map<String, String> tableRowChainTail = new HashMap<>();
        try {
            for (Mutation mutation : batch.mutations()) {
                switch (mutation) {
                    case FormatMutation format -> {
                        formatApplier.apply(session.document(), format);
                        formattedIds.add(format.targetId());
                    }
                    case ModifyMutation modify -> {
                        modifyApplier.apply(session.document(), modify);
                        targetedIds.add(modify.targetId());
                    }
                    case InsertMutation insert -> {
                        if (insert.isTableRow()) {
                            // Consecutive "after" row inserts on the same anchor stack as
                            // separate rows — each one after the first is redirected onto
                            // the row the previous insert just created (mirrors the plain
                            // paragraph chaining below).
                            String rowChainKey = insert.anchorId() + "#after";
                            String effectiveAnchor = "after".equals(insert.position())
                                    && tableRowChainTail.containsKey(rowChainKey)
                                    ? tableRowChainTail.get(rowChainKey)
                                    : insert.anchorId();
                            List<String> newIds = tableStructuralApplier.insertRow(
                                    session.document(), insert, effectiveAnchor);
                            if ("after".equals(insert.position()) && !newIds.isEmpty()) {
                                tableRowChainTail.put(rowChainKey, newIds.getFirst());
                            }
                            targetedIds.addAll(newIds);
                            createdIds.addAll(newIds);
                        } else if (insert.isTableColumn()) {
                            List<String> newIds = tableStructuralApplier.insertColumn(
                                    session.document(), insert);
                            targetedIds.addAll(newIds);
                            createdIds.addAll(newIds);
                        } else {
                            String chainKey = insert.anchorId() + "#after";
                            String effectiveAnchor = "after".equals(insert.position())
                                    && insertChainTail.containsKey(chainKey)
                                    ? insertChainTail.get(chainKey)
                                    : insert.anchorId();
                            String newId = insertApplier.apply(
                                    session.document(), insert, effectiveAnchor);
                            if ("after".equals(insert.position())) {
                                insertChainTail.put(chainKey, newId);
                            }
                            targetedIds.add(newId);
                            createdIds.add(newId);
                        }
                    }
                    case DeleteMutation delete -> {
                        if (delete.isTableRow()) {
                            Set<String> removed = tableStructuralApplier.deleteRow(
                                    session.document(), delete);
                            targetedIds.addAll(removed);
                        } else if (delete.isTableColumn()) {
                            Set<String> removed = tableStructuralApplier.deleteColumn(
                                    session.document(), delete);
                            targetedIds.addAll(removed);
                        } else {
                            deleteApplier.apply(session.document(), delete);
                            targetedIds.add(delete.targetId());
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            session.restore(snapshot.documentBytes());
            throw ex;
        }

        Set<String> changed = hashGuard.verify(session, index.documentId(), snapshot, targetedIds);
        return new ApplyResult(batch.mutations().size(), changed, createdIds, formattedIds);
    }
}
