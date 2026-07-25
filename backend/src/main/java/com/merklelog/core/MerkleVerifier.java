package com.merklelog.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Verifies Merkle inclusion proofs.
 *
 * <h2>Deliberately stateless, and deliberately ignorant of the tree</h2>
 *
 * <p>Nothing here holds a reference to a {@link MerkleTree}. A verifier is given three
 * things — a leaf hash, a proof, and a root it already trusts — and nothing else. That is
 * the point of a Merkle proof: the party checking an entry does not need the dataset, so an
 * auditor with only the published root can verify one log line out of millions.
 *
 * <p>It also makes the O(log n) claim impossible to fake by accident. With no tree to
 * consult, verification can only do what it appears to do: fold the proof's steps into the
 * leaf hash, one hash per step.
 */
public final class MerkleVerifier {

    private MerkleVerifier() {
        // Static utility class; never instantiated.
    }

    /**
     * Recomputes the root implied by a leaf hash and its proof.
     *
     * <p>Cost is exactly {@code proof.length()} hash operations, which is O(log n).
     *
     * <p>An empty proof returns the leaf hash unchanged. That is correct for a single-leaf
     * tree, where the leaf hash <em>is</em> the root — and note that it is a real computation,
     * not a shortcut: the result still has to match the expected root in
     * {@link #verify(byte[], MerkleProof, byte[])}, so a wrong root is still rejected.
     */
    public static byte[] computeRoot(byte[] leafHash, MerkleProof proof) {
        Objects.requireNonNull(proof, "proof");
        byte[] running = requireHash(leafHash);

        for (ProofStep step : proof.steps()) {
            running = step.combine(running);
        }
        return running;
    }

    /**
     * Checks whether a leaf hash and proof reproduce the expected root.
     *
     * @param leafHash     hash of the entry being checked
     * @param proof        its inclusion proof
     * @param expectedRoot the root already trusted by the verifier
     * @return true only if the recomputed root matches
     */
    public static boolean verify(byte[] leafHash, MerkleProof proof, byte[] expectedRoot) {
        Objects.requireNonNull(expectedRoot, "expectedRoot");
        return Hashing.equal(computeRoot(leafHash, proof), expectedRoot);
    }

    /** Convenience overload: hashes the entry, then verifies it. */
    public static boolean verify(LogEntry entry, MerkleProof proof, byte[] expectedRoot) {
        Objects.requireNonNull(entry, "entry");
        return verify(entry.leafHash(), proof, expectedRoot);
    }

    /**
     * Verifies while recording every intermediate hash.
     *
     * <p>Feeds the Phase 5 step-through UI, which walks a proof one rung at a time and shows
     * {@code running || sibling -> SHA-256 -> next}. Kept alongside the plain path rather
     * than reimplemented in the API layer, so what the UI displays is exactly what the
     * verifier computed.
     */
    public static VerificationTrace verifyWithTrace(byte[] leafHash, MerkleProof proof, byte[] expectedRoot) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(expectedRoot, "expectedRoot");

        byte[] running = requireHash(leafHash);
        List<TraceStep> trace = new ArrayList<>(proof.length());

        for (ProofStep step : proof.steps()) {
            byte[] before = running;
            running = step.combine(running);
            trace.add(new TraceStep(before, step.siblingHash(), step.side(), running));
        }
        return new VerificationTrace(
                running, expectedRoot.clone(), Hashing.equal(running, expectedRoot), trace);
    }

    private static byte[] requireHash(byte[] leafHash) {
        Objects.requireNonNull(leafHash, "leafHash");
        if (leafHash.length != Hashing.HASH_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "leafHash must be " + Hashing.HASH_LENGTH_BYTES + " bytes, got " + leafHash.length);
        }
        return leafHash.clone();
    }

    /**
     * The full record of a verification: every intermediate hash, plus the verdict.
     *
     * @param computedRoot the root the proof actually produced
     * @param expectedRoot the root it was checked against
     * @param valid        whether the two match
     * @param steps        one entry per proof rung, leaf upward
     */
    public record VerificationTrace(byte[] computedRoot, byte[] expectedRoot, boolean valid, List<TraceStep> steps) {

        public VerificationTrace {
            steps = Collections.unmodifiableList(new ArrayList<>(steps));
        }

        public String computedRootHex() {
            return Hashing.toHex(computedRoot);
        }

        public String expectedRootHex() {
            return Hashing.toHex(expectedRoot);
        }
    }

    /**
     * One rung of a verification, with both inputs and the output.
     *
     * @param runningBefore the hash carried in from the level below
     * @param sibling       the sibling hash supplied by the proof
     * @param side          which side the sibling was on
     * @param runningAfter  the resulting parent hash
     */
    public record TraceStep(byte[] runningBefore, byte[] sibling, ProofStep.Side side, byte[] runningAfter) {

        public String runningBeforeHex() {
            return Hashing.toHex(runningBefore);
        }

        public String siblingHex() {
            return Hashing.toHex(sibling);
        }

        public String runningAfterHex() {
            return Hashing.toHex(runningAfter);
        }
    }
}
