package com.merklelog.chunking;

import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test helpers for the edit-locality property: after a change to the log, how many chunk roots
 * are new?
 *
 * <p>A chunk root that appears in both forests covers exactly the same entries in both, so it
 * needs no re-hashing and no re-anchoring. Every root that does not appear in the old forest is a
 * chunk that changed.
 */
final class ChunkLocality {

    private ChunkLocality() {
    }

    /** A copy of the log with one new entry inserted before {@code position}. */
    static List<LogEntry> insertAt(List<LogEntry> entries, int position) {
        LogEntry before = entries.get(position - 1);
        LogEntry injected = new LogEntry(9_000_000L, before.timestamp().plusMillis(1),
                "WARN", "edge-gateway-01", "injected entry");
        List<LogEntry> copy = new ArrayList<>(entries);
        copy.add(position, injected);
        return copy;
    }

    /** A copy of the log with the entry at {@code position} edited. */
    static List<LogEntry> editAt(List<LogEntry> entries, int position) {
        List<LogEntry> copy = new ArrayList<>(entries);
        copy.set(position, copy.get(position).withMessage("edited entry"));
        return copy;
    }

    /** Number of chunk roots in the changed log's forest that the original forest does not have. */
    static int chunkRootsChanged(ChunkingStrategy strategy, List<LogEntry> original, List<LogEntry> changed) {
        Set<String> originalRoots = chunkRoots(MerkleForest.build(original, strategy));
        int newRoots = 0;
        for (String root : chunkRoots(MerkleForest.build(changed, strategy))) {
            if (!originalRoots.contains(root)) {
                newRoots++;
            }
        }
        return newRoots;
    }

    private static Set<String> chunkRoots(MerkleForest forest) {
        Set<String> roots = new HashSet<>();
        for (int c = 0; c < forest.chunkCount(); c++) {
            roots.add(Hashing.toHex(forest.chunkRoot(c)));
        }
        return roots;
    }
}
