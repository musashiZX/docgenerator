package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.document.TextNormalizer;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mechanical proof of "no collateral change": hashes every block's normalized
 * plain text before a batch and verifies that the set of changed blocks equals
 * exactly the set of targeted ids. Text only — formatting drift is the
 * RunEditor's responsibility, not the guard's.
 */
@Component
public class NodeHashGuard {

    public record GuardSnapshot(Map<String, String> hashes, byte[] documentBytes) {
    }

    private final StructuralIndexBuilder indexBuilder;

    public NodeHashGuard(StructuralIndexBuilder indexBuilder) {
        this.indexBuilder = indexBuilder;
    }

    public GuardSnapshot begin(DocumentSession session, String documentId) throws Exception {
        return new GuardSnapshot(hashBlocks(session, documentId), session.snapshot());
    }

    /**
     * Re-hash after apply; changed ids must equal targeted ids exactly.
     * On violation the session is restored from the begin() snapshot.
     *
     * @return the changed id set (== targetedIds on success)
     */
    public Set<String> verify(
            DocumentSession session,
            String documentId,
            GuardSnapshot snapshot,
            Set<String> targetedIds) throws Exception {

        Map<String, String> after = hashBlocks(session, documentId);
        Set<String> changed = new HashSet<>();

        for (Map.Entry<String, String> entry : snapshot.hashes().entrySet()) {
            String afterHash = after.get(entry.getKey());
            if (afterHash == null || !afterHash.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String id : after.keySet()) {
            if (!snapshot.hashes().containsKey(id)) {
                changed.add(id);
            }
        }

        if (!changed.equals(targetedIds)) {
            session.restore(snapshot.documentBytes());
            throw new MutationInvariantViolation(changed, targetedIds);
        }
        return changed;
    }

    private Map<String, String> hashBlocks(DocumentSession session, String documentId) throws Exception {
        StructuralIndex index = indexBuilder.build(session.document(), documentId);
        Map<String, String> hashes = new HashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            hashes.put(block.targetId(), sha256(TextNormalizer.normalize(block.text())));
        }
        return hashes;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class MutationInvariantViolation extends RuntimeException {
        private final Set<String> changedIds;
        private final Set<String> targetedIds;

        public MutationInvariantViolation(Set<String> changedIds, Set<String> targetedIds) {
            super("Collateral change detected. changed=" + changedIds + " targeted=" + targetedIds
                    + " — batch rolled back.");
            this.changedIds = changedIds;
            this.targetedIds = targetedIds;
        }

        public Set<String> changedIds() {
            return changedIds;
        }

        public Set<String> targetedIds() {
            return targetedIds;
        }
    }
}
