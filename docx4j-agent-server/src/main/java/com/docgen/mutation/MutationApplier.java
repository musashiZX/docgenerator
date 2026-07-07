package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.model.ApplyResult;
import com.docgen.model.DeleteMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
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

    private final MutationValidator validator;
    private final ModifyApplier modifyApplier;
    private final InsertApplier insertApplier;
    private final DeleteApplier deleteApplier;
    private final NodeHashGuard hashGuard;

    public MutationApplier(
            MutationValidator validator,
            ModifyApplier modifyApplier,
            InsertApplier insertApplier,
            DeleteApplier deleteApplier,
            NodeHashGuard hashGuard) {
        this.validator = validator;
        this.modifyApplier = modifyApplier;
        this.insertApplier = insertApplier;
        this.deleteApplier = deleteApplier;
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
        Map<String, String> insertChainTail = new HashMap<>();
        try {
            for (Mutation mutation : batch.mutations()) {
                switch (mutation) {
                    case ModifyMutation modify -> {
                        modifyApplier.apply(session.document(), modify);
                        targetedIds.add(modify.targetId());
                    }
                    case InsertMutation insert -> {
                        String chainKey = insert.anchorId() + "#after";
                        String effectiveAnchor = "after".equals(insert.position())
                                && insertChainTail.containsKey(chainKey)
                                ? insertChainTail.get(chainKey)
                                : insert.anchorId();
                        String newId = insertApplier.apply(session.document(), insert, effectiveAnchor);
                        if ("after".equals(insert.position())) {
                            insertChainTail.put(chainKey, newId);
                        }
                        targetedIds.add(newId);
                        createdIds.add(newId);
                    }
                    case DeleteMutation delete -> {
                        deleteApplier.apply(session.document(), delete);
                        targetedIds.add(delete.targetId());
                    }
                }
            }
        } catch (RuntimeException ex) {
            session.restore(snapshot.documentBytes());
            throw ex;
        }

        Set<String> changed = hashGuard.verify(session, index.documentId(), snapshot, targetedIds);
        return new ApplyResult(batch.mutations().size(), changed, createdIds);
    }
}
