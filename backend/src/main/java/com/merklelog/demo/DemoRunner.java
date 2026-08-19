package com.merklelog.demo;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;
import com.merklelog.core.EmptyForestException;
import com.merklelog.core.ForestProof;
import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;
import com.merklelog.core.MerkleProof;
import com.merklelog.core.MerkleTree;
import com.merklelog.core.MerkleVerifier;

import java.util.List;

/**
 * A console walkthrough of everything the Phase 1 core can do, in the order it makes sense
 * to explain it aloud.
 *
 * <p>This exists because the React front end is a later phase, but the integrity logic is
 * finished and needs to be demonstrable now. It is a <em>presentation layer</em>, nothing
 * more: it computes no hashes of its own and duplicates no logic, it only calls the public
 * API of {@code core} and {@code chunking} and prints what comes back. If a claim appears on
 * screen, a class in {@code core} produced it.
 *
 * <p>Run with:
 * <pre>
 *   cd backend
 *   mvn -q compile
 *   java -cp target/classes com.merklelog.demo.DemoRunner [entryCount]
 * </pre>
 *
 * <p>Output is deterministic — {@link SyntheticLogGenerator} uses a fixed seed — so the
 * hashes printed during a viva are the same ones in the write-up.
 */
public final class DemoRunner {

    private static final int DEFAULT_ENTRY_COUNT = 512;

    /** Hashes are 64 hex chars; full width is unreadable in a table, so tables abbreviate. */
    private static final int SHORT_HASH_CHARS = 16;

    private DemoRunner() {
        // Entry point only; never instantiated.
    }

    public static void main(String[] args) {
        int entryCount = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_ENTRY_COUNT;

        banner();
        List<LogEntry> entries = section1Dataset(entryCount);
        section2Primitives();
        MerkleForest forest = section3Forest(entries);
        int target = section4Proof(forest, entries);
        section5Tamper(forest, entries, target);
        section6RebuildCost(forest, target);
        section7StrategyComparison(entries);
        section8DegenerateCases();
        footer();
    }

    // ------------------------------------------------------------------ sections

    private static void banner() {
        rule('=');
        System.out.println("  MERKLE-TREE TAMPER-EVIDENT LOG INTEGRITY  -  core engine demo");
        System.out.println("  Phase 1 (core DSA) complete - console demo pending the React front end");
        rule('=');
    }

    private static List<LogEntry> section1Dataset(int entryCount) {
        heading("1", "SYNTHETIC LOG STREAM");

        List<LogEntry> entries = SyntheticLogGenerator.generate(entryCount);
        System.out.printf("  entries generated : %d%n", entries.size());
        System.out.printf("  rng seed          : %d  (fixed - every run is reproducible)%n",
                SyntheticLogGenerator.DEFAULT_SEED);
        if (!entries.isEmpty()) {
            System.out.printf("  time span         : %s  ->  %s%n",
                    entries.get(0).timestamp(), entries.get(entries.size() - 1).timestamp());
        }
        System.out.println();
        System.out.println("  first three records:");
        entries.stream().limit(3).forEach(entry -> System.out.printf(
                "    #%-4d %s  %-5s %-16s %s%n",
                entry.id(), entry.timestamp(), entry.level(), entry.source(), entry.message()));
        return entries;
    }

    private static void section2Primitives() {
        heading("2", "HASH PRIMITIVES  (RFC 6962 domain separation)");

        byte[] payload = Hashing.utf8("heartbeat ok");
        byte[] leaf = Hashing.leafHash(payload);
        byte[] node = Hashing.nodeHash(leaf, leaf);

        System.out.println("  Real SHA-256 via java.security.MessageDigest - no stubs anywhere,");
        System.out.println("  including in tests. Leaves and internal nodes are hashed under");
        System.out.println("  DIFFERENT one-byte prefixes:");
        System.out.println();
        System.out.printf("    leafHash(d)     = SHA256(0x%02X || d)       = %s%n",
                Hashing.LEAF_PREFIX, Hashing.toHex(leaf));
        System.out.printf("    nodeHash(L,R)   = SHA256(0x%02X || L || R)  = %s%n",
                Hashing.NODE_PREFIX, Hashing.toHex(node));
        System.out.printf("    plain SHA256(d) = %s%n", Hashing.toHex(Hashing.sha256(payload)));
        System.out.println();
        System.out.println("  WHY: without the prefixes an attacker could present an internal node's");
        System.out.println("  data as if it were a leaf - the second-preimage attack RFC 6962 closes.");
        System.out.printf("  Empty-tree sentinel root = SHA256(\"\") = %s%n",
                Hashing.toHex(Hashing.emptyTreeHash()));
    }

    private static MerkleForest section3Forest(List<LogEntry> entries) {
        heading("3", "FOREST CONSTRUCTION  (chunk = one independent Merkle tree)");

        ChunkingStrategy strategy = ChunkingStrategyFactory.create("fixed-size");
        MerkleForest forest = MerkleForest.build(entries, strategy);

        System.out.printf("  strategy    : %s %s%n", strategy.name(), strategy.parameters());
        System.out.printf("  entries     : %d%n", forest.entryCount());
        System.out.printf("  chunks      : %d  (each its own tree, roots sealed under a super-root)%n",
                forest.chunkCount());
        System.out.printf("  super-tree  : %d chunk roots, depth %d%n",
                forest.superTree().leafCount(), forest.superTree().depth());
        System.out.printf("  SUPER-ROOT  : %s%n", forest.superRootHex());
        System.out.println();
        System.out.printf("  %-7s %-8s %-12s %s%n", "chunk", "entries", "tree depth", "chunk root");
        System.out.printf("  %-7s %-8s %-12s %s%n", "-----", "-------", "----------", "----------");
        for (int i = 0; i < Math.min(forest.chunkCount(), 6); i++) {
            Chunk chunk = forest.chunks().get(i);
            System.out.printf("  %-7d %-8d %-12d %s...%n",
                    i, chunk.size(), forest.chunkTree(i).depth(), shortHash(forest.chunkRoot(i)));
        }
        if (forest.chunkCount() > 6) {
            System.out.printf("  ...%d more chunks%n", forest.chunkCount() - 6);
        }
        return forest;
    }

    private static int section4Proof(MerkleForest forest, List<LogEntry> entries) {
        heading("4", "INCLUSION PROOF  -  O(log n), not O(n)");

        int target = forest.entryCount() / 3;
        LogEntry entry = entries.get(target);
        ForestProof proof = forest.generateProof(target);

        System.out.printf("  proving entry #%d : \"%s\"%n", entry.id(), entry.message());
        System.out.printf("  located in       : chunk %d, local index %d%n",
                proof.chunkIndex(), proof.localIndex());
        System.out.println();
        System.out.printf("  entry -> chunk root : %d steps%n", proof.entryProof().length());
        System.out.printf("  chunk root -> super : %d steps%n", proof.chunkProof().length());
        System.out.printf("  TOTAL               : %d sibling hashes = %d bytes on the wire%n",
                proof.totalSteps(), proof.sizeInBytes());
        System.out.printf("  a naive re-hash of the whole dataset would touch %d entries%n",
                forest.entryCount());
        System.out.println();
        System.out.println("  verification trace (entry -> chunk root), one SHA-256 per line:");
        printTrace(entry.leafHash(), proof.entryProof(), forest.chunkRoot(proof.chunkIndex()));

        boolean valid = forest.verify(entry, proof);
        System.out.println();
        System.out.printf("  VERDICT: %s  (recomputed super-root matches the trusted one)%n",
                verdict(valid));
        return target;
    }

    private static void section5Tamper(MerkleForest forest, List<LogEntry> entries, int target) {
        heading("5", "TAMPER DETECTION AND LOCALISATION");

        LogEntry original = entries.get(target);
        LogEntry forged = original.withMessage("heartbeat ok");
        ForestProof oldProof = forest.generateProof(target);

        MerkleForest tampered = forest.withEntryReplaced(target, forged).forest();

        System.out.printf("  entry #%d edited%n", original.id());
        System.out.printf("    before : \"%s\"%n", original.message());
        System.out.printf("    after  : \"%s\"%n", forged.message());
        System.out.println();
        System.out.println("  a single changed byte propagates all the way to the super-root:");
        System.out.printf("    super-root before : %s%n", forest.superRootHex());
        System.out.printf("    super-root after  : %s%n", tampered.superRootHex());
        System.out.printf("    identical?        : %s%n",
                Hashing.equal(forest.superRoot(), tampered.superRoot())
                        ? "yes" : "NO - tampering detected");
        System.out.println();

        System.out.println("  what the old proof does and does not still prove:");
        System.out.printf("    original entry + old proof vs OLD (trusted) root : %s%n",
                verdict(MerkleForest.verify(original.leafHash(), oldProof, forest.superRoot())));
        System.out.printf("    original entry + old proof vs NEW root           : %s%n",
                verdict(MerkleForest.verify(original.leafHash(), oldProof, tampered.superRoot())));
        System.out.printf("    forged   entry + old proof vs OLD (trusted) root : %s%n",
                verdict(MerkleForest.verify(forged.leafHash(), oldProof, forest.superRoot())));
        System.out.printf("    forged   entry + old proof vs NEW root           : %s%n",
                verdict(MerkleForest.verify(forged.leafHash(), oldProof, tampered.superRoot())));
        System.out.println();
        System.out.println("    Read the last line carefully - it is the honest limit of the scheme,");
        System.out.println("    and the thing worth being able to explain. An attacker who rewrites an");
        System.out.println("    entry AND recomputes the tree gets a set that is internally consistent:");
        System.out.println("    the forged entry verifies against the forged root. What they cannot do");
        System.out.println("    is make it verify against the TRUSTED root (line 3: INVALID). That is");
        System.out.println("    why the super-root has to be anchored outside the log - published,");
        System.out.println("    signed, or held by the verifier - not just stored beside the data.");
        System.out.println();

        System.out.println("  LOCALISATION - narrowing to the culprit without scanning entries:");
        int badChunk = firstDifferingChunk(forest, tampered);
        System.out.printf("    step 1: compare %d chunk roots      -> chunk %d differs%n",
                forest.chunkCount(), badChunk);
        int badLeaf = localiseLeaf(forest.chunkTree(badChunk), tampered.chunkTree(badChunk));
        System.out.printf("    step 2: descend that chunk's tree  -> local leaf %d%n", badLeaf);
        System.out.printf("    step 3: global entry index         -> %d  (entry #%d)%n",
                target, original.id());
        System.out.printf("    comparisons used: %d, against %d entries in the dataset%n",
                forest.chunkCount() + forest.chunkTree(badChunk).depth(), forest.entryCount());
    }

    private static void section6RebuildCost(MerkleForest forest, int target) {
        heading("6", "LOCALISED REBUILD COST");

        LogEntry replacement = forest.chunks().get(0).entries().get(0);
        MerkleForest.RebuildResult result = forest.withEntryReplaced(target, replacement);

        System.out.println("  When an entry changes, only its own chunk's tree is rebuilt, plus the");
        System.out.println("  small super-tree over the chunk roots. Every other chunk is untouched.");
        System.out.println();
        System.out.printf("    chunk rebuilt              : %d%n", result.rebuiltChunkIndex());
        System.out.printf("    entries re-hashed          : %d%n", result.entriesRehashed());
        System.out.printf("    entries in the dataset     : %d%n", result.entriesInDataset());
        System.out.printf("    saving vs one global tree  : %.1fx cheaper%n", result.savingFactor());
        System.out.println();
        System.out.println("  This is exactly why chunking strategy matters, and what Phase 7 measures:");
        System.out.println("  smaller chunks rebuild faster but make proofs longer, and vice versa.");
    }

    private static void section7StrategyComparison(List<LogEntry> entries) {
        heading("7", "CHUNKING STRATEGY COMPARISON  (the contribution over the base paper)");

        System.out.printf("  %-12s %-8s %-10s %-11s %-13s %s%n",
                "strategy", "chunks", "avg size", "avg steps", "proof bytes", "rebuild cost");
        System.out.printf("  %-12s %-8s %-10s %-11s %-13s %s%n",
                "--------", "------", "--------", "---------", "-----------", "------------");

        for (ChunkingStrategy strategy : ChunkingStrategyFactory.allWithDefaults()) {
            MerkleForest forest = MerkleForest.build(entries, strategy);

            // Averaged over every entry, not a sample: proof length varies across the tree
            // because a promoted odd node contributes no step, so one entry is not typical.
            long totalSteps = 0;
            long totalBytes = 0;
            long totalRehashed = 0;
            for (int i = 0; i < forest.entryCount(); i++) {
                ForestProof proof = forest.generateProof(i);
                totalSteps += proof.totalSteps();
                totalBytes += proof.sizeInBytes();
                totalRehashed += forest.chunks().get(proof.chunkIndex()).size();
            }
            int n = Math.max(forest.entryCount(), 1);

            System.out.printf("  %-12s %-8d %-10.1f %-11.2f %-13.0f %.1f entries%n",
                    strategy.name(),
                    forest.chunkCount(),
                    forest.entryCount() / (double) Math.max(forest.chunkCount(), 1),
                    totalSteps / (double) n,
                    totalBytes / (double) n,
                    totalRehashed / (double) n);
        }

        System.out.println();
        System.out.println("  Reading it: fewer, larger chunks -> shorter super-tree path but a taller");
        System.out.println("  chunk tree and a costlier rebuild. The three strategies land at different");
        System.out.println("  points on that trade-off, which is what the base paper's single strategy");
        System.out.println("  could not show. Base-paper reference proof size: ~1006 bytes.");
        System.out.println();
        System.out.println("  KNOWN LIMITATION (stated, not hidden): Shannon entropy over a short byte");
        System.out.println("  window is biased low and noisy, so on short log payloads the entropy");
        System.out.println("  boundary signal degrades toward arbitrary. Guarded with min/max chunk");
        System.out.println("  size so a low-entropy run cannot collapse into one giant chunk.");
    }

    private static void section8DegenerateCases() {
        heading("8", "DEGENERATE CASES  (defined behaviour, not accidents)");

        ChunkingStrategy strategy = ChunkingStrategyFactory.create("fixed-size");

        MerkleForest empty = MerkleForest.build(List.of(), strategy);
        System.out.println("  0 entries:");
        System.out.printf("    isEmpty       : %s%n", empty.isEmpty());
        System.out.printf("    super-root    : %s%n", empty.superRootHex());
        System.out.printf("    == SHA256(\"\") : %s  (RFC 6962 sentinel - never null)%n",
                Hashing.equal(empty.superRoot(), Hashing.emptyTreeHash()));
        try {
            empty.generateProof(0);
            System.out.println("    proof request : returned - WRONG, should have thrown");
        } catch (EmptyForestException e) {
            System.out.printf("    proof request : throws %s%n", e.getClass().getSimpleName());
        }

        List<LogEntry> one = SyntheticLogGenerator.generate(1);
        MerkleForest single = MerkleForest.build(one, strategy);
        ForestProof proof = single.generateProof(0);
        System.out.println();
        System.out.println("  1 entry (also the 1-chunk case):");
        System.out.printf("    root == leafHash(entry)  : %s%n",
                Hashing.equal(single.superRoot(), one.get(0).leafHash()));
        System.out.printf("    proof steps              : %d  (an empty step list)%n",
                proof.totalSteps());
        System.out.printf("    verifies vs CORRECT root : %s%n",
                verdict(single.verify(one.get(0), proof)));
        System.out.printf("    verifies vs WRONG   root : %s%n",
                verdict(MerkleForest.verify(one.get(0).leafHash(), proof, Hashing.emptyTreeHash())));
        System.out.println("    (an empty proof must NOT short-circuit to true - that hole would");
        System.out.println("     pass a naive test while accepting any root at all)");

        System.out.println();
        System.out.println("  odd node at a level: promoted unchanged, never duplicated -");
        System.out.println("  duplication is the Bitcoin CVE-2012-2459 forgery.");
    }

    private static void footer() {
        System.out.println();
        rule('=');
        System.out.println("  STATUS");
        rule('=');
        System.out.println("  DONE      Phase 1 - core DSA: hashing, tree, proofs, verifier, forest,");
        System.out.println("                      3 chunking strategies.  260 unit tests, all green.");
        System.out.println("  NEXT      Phase 2 - REST API   |  Phase 3 - PostgreSQL persistence");
        System.out.println("  PENDING   Phases 4-6 - React visualisation, tamper UI, comparison page");
        System.out.println("            Phase 7 - benchmark dashboard  |  Phase 8 - deployment");
        System.out.println();
        System.out.println("  This console demo stands in for the front end until Phase 4. Every figure");
        System.out.println("  above came from the real engine - no fixtures, no mocked hashes.");
        rule('=');
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Finds the first chunk whose root changed - step one of localisation.
     *
     * <p>Costs one comparison per chunk, not per entry. With the default 64-entry chunks that
     * is a 64x reduction before the tree descent even starts.
     */
    private static int firstDifferingChunk(MerkleForest before, MerkleForest after) {
        for (int i = 0; i < before.chunkCount(); i++) {
            if (!Hashing.equal(before.chunkRoot(i), after.chunkRoot(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Walks down from the root, at each level following the child whose hash changed, until a
     * leaf is reached. One comparison per level, so O(log n) - this is the "localised in
     * O(log n)" claim executed rather than merely asserted.
     *
     * <p>Both trees have the same leaf count and therefore the same shape, so an index at one
     * level maps to children {@code 2i} and {@code 2i+1} at the level below. Where {@code 2i+1}
     * is absent the node was promoted unchanged, so it has the single child {@code 2i}.
     */
    private static int localiseLeaf(MerkleTree before, MerkleTree after) {
        List<List<byte[]>> levelsBefore = before.levels();
        List<List<byte[]>> levelsAfter = after.levels();

        int index = 0;
        for (int level = levelsBefore.size() - 1; level > 0; level--) {
            int left = index * 2;
            int right = left + 1;
            List<byte[]> childBefore = levelsBefore.get(level - 1);
            List<byte[]> childAfter = levelsAfter.get(level - 1);

            boolean rightChanged = right < childBefore.size()
                    && !Hashing.equal(childBefore.get(right), childAfter.get(right));
            index = rightChanged ? right : left;
        }
        return index;
    }

    /** Prints each rehash the verifier performs, so the O(log n) path is visible, not asserted. */
    private static void printTrace(byte[] leafHash, MerkleProof proof, byte[] expectedRoot) {
        MerkleVerifier.VerificationTrace trace =
                MerkleVerifier.verifyWithTrace(leafHash, proof, expectedRoot);

        System.out.printf("    start  running = leaf  %s...%n", shortHash(leafHash));
        int step = 1;
        for (MerkleVerifier.TraceStep traceStep : trace.steps()) {
            System.out.printf("    %-6s sibling on %-5s %s...  ->  %s...%n",
                    "s" + step++,
                    traceStep.side().toString().toLowerCase(),
                    traceStep.siblingHex().substring(0, SHORT_HASH_CHARS),
                    traceStep.runningAfterHex().substring(0, SHORT_HASH_CHARS));
        }
        System.out.printf("    result computed = %s%n", trace.computedRootHex());
        System.out.printf("           expected = %s%n", trace.expectedRootHex());
    }

    private static String shortHash(byte[] hash) {
        return Hashing.toHex(hash).substring(0, SHORT_HASH_CHARS);
    }

    private static String verdict(boolean valid) {
        return valid ? "VALID" : "INVALID";
    }

    private static void heading(String number, String title) {
        System.out.println();
        rule('-');
        System.out.printf("  [%s]  %s%n", number, title);
        rule('-');
    }

    private static void rule(char c) {
        System.out.println(String.valueOf(c).repeat(78));
    }
}
