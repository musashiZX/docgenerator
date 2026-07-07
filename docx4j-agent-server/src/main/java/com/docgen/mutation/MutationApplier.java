package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.model.ApplyResult;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Batch orchestrator: validate → hash snapshot → apply each mutation →
 * verify invariant. Any failure restores the session to its pre-batch state.
 */
@Service
public class MutationApplier {

    private final MutationValidator validator;
    private final ModifyApplier modifyApplier;
    private final NodeHashGuard hashGuard;

    public MutationApplier(
            MutationValidator validator,
            ModifyApplier modifyApplier,
            NodeHashGuard hashGuard) {
        this.validator = validator;
        this.modifyApplier = modifyApplier;
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
        try {
            for (Mutation mutation : batch.mutations()) {
                switch (mutation) {
                    case ModifyMutation modify -> {
                        modifyApplier.apply(session.document(), modify);
                        targetedIds.add(modify.targetId());
                    }
                }
            }
        } catch (RuntimeException ex) {
            session.restore(snapshot.documentBytes());
            throw ex;
        }

        Set<String> changed = hashGuard.verify(session, index.documentId(), snapshot, targetedIds);
        return new ApplyResult(batch.mutations().size(), changed);
    }
}
