package com.merklelog.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.merklelog.core.MerkleTreeTest.ceilLog2;
import static com.merklelog.core.MerkleTreeTest.entries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for proof generation and verification on the happy path.
 *
 * <p>Detection of tampering and rejection of forged proofs live in
 * {@code TamperDetectionTest}; this class establishes that honest proofs verify, and that
 * they are the size the complexity argument claims.
 */
@DisplayName("MerkleProof — generation and verification")
class MerkleProofTest {

    @Nested
    @DisplayName("correctness")
    class Correctness {

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 9, 16, 17, 33, 100, 257})
        @DisplayName("every leaf in the tree produces a proof that verifies")
        void everyLeafVerifies(int n) {
            List<LogEntry> entries = entries(n);
            MerkleTree tree = MerkleTree.fromEntries(entries);
            byte[] root = tree.root();

            // Not a sample — every single leaf. A bug affecting only right-edge or promoted
            // nodes would slip past a test that checked just the first few.
            for (int i = 0; i < n; i++) {
                MerkleProof proof = tree.generateProof(i);

                assertThat(MerkleVerifier.verify(entries.get(i), proof, root))
                        .as("leaf %d of %d must verify", i, n)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the recomputed root equals the tree's actual root")
        void recomputedRootMatchesTreeRoot() {
            List<LogEntry> entries = entries(11);
            MerkleTree tree = MerkleTree.fromEntries(entries);

            for (int i = 0; i < entries.size(); i++) {
                byte[] recomputed = MerkleVerifier.computeRoot(
                        entries.get(i).leafHash(), tree.generateProof(i));

                assertThat(recomputed).as("leaf %d", i).isEqualTo(tree.root());
            }
        }

        @Test
        @DisplayName("a proof verifies without any access to the tree or the other entries")
        void proofIsSelfContained() {
            List<LogEntry> entries = entries(64);
            MerkleTree tree = MerkleTree.fromEntries(entries);

            // Everything an auditor gets: one entry, its proof, and the published root.
            LogEntry entry = entries.get(42);
            MerkleProof proof = tree.generateProof(42);
            byte[] publishedRoot = tree.root();

            // The tree and the other 63 entries are now irrelevant — this is the property
            // that makes Merkle proofs worth the machinery.
            assertThat(MerkleVerifier.verify(entry, proof, publishedRoot)).isTrue();
        }
    }

    @Nested
    @DisplayName("size — the O(log n) claim")
    class ProofSize {

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 9, 16, 17, 100, 1000, 4096})
        @DisplayName("no proof is longer than ceil(log2 n)")
        void proofLengthIsLogarithmic(int n) {
            MerkleTree tree = MerkleTree.fromEntries(entries(n));
            int bound = ceilLog2(n);

            for (int i = 0; i < n; i++) {
                assertThat(tree.generateProof(i).length())
                        .as("proof for leaf %d of %d", i, n)
                        .isLessThanOrEqualTo(bound);
            }
        }

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 4, 8, 16, 32, 1024})
        @DisplayName("in a perfectly balanced tree every proof is exactly log2 n long")
        void balancedTreeProofsAreExactlyLogN(int n) {
            // When n is a power of two no node is ever promoted, so the bound is tight and
            // the complexity claim can be asserted as an equality rather than a bound.
            MerkleTree tree = MerkleTree.fromEntries(entries(n));
            int expected = ceilLog2(n);

            for (int i = 0; i < n; i++) {
                assertThat(tree.generateProof(i).length()).as("leaf %d", i).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("proof length grows by one step when the dataset doubles")
        void doublingDatasetAddsOneStep() {
            // The headline property, stated directly: 1024x the data costs 10 extra hashes.
            int small = MerkleTree.fromEntries(entries(1024)).generateProof(0).length();
            int large = MerkleTree.fromEntries(entries(2048)).generateProof(0).length();

            assertThat(large).isEqualTo(small + 1);
            assertThat(MerkleTree.fromEntries(entries(1_048_576)).generateProof(0).length())
                    .isEqualTo(small + 10);
        }

        @Test
        @DisplayName("proof size in bytes is 32 per step")
        void proofSizeInBytes() {
            MerkleProof proof = MerkleTree.fromEntries(entries(1000)).generateProof(0);

            assertThat(proof.sizeInBytes()).isEqualTo(proof.length() * 32);
            // 1000 entries -> 10 siblings -> 320 bytes, well under the base paper's ~1006.
            assertThat(proof.sizeInBytes()).isEqualTo(320);
        }

        @Test
        @DisplayName("a promoted leaf gets a shorter proof than the depth")
        void promotedLeafHasShorterProof() {
            // With 3 leaves, leaf 2 is promoted from level 0 to level 1, so it needs only one
            // sibling instead of two. This is why the bound is <= rather than ==.
            MerkleTree tree = MerkleTree.fromEntries(entries(3));

            assertThat(tree.depth()).isEqualTo(2);
            assertThat(tree.generateProof(0).length()).isEqualTo(2);
            assertThat(tree.generateProof(2).length()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("single-leaf tree — the empty proof")
    class SingleLeafTree {

        @Test
        @DisplayName("the proof is empty and still verifies against the correct root")
        void emptyProofVerifies() {
            List<LogEntry> one = entries(1);
            MerkleTree tree = MerkleTree.fromEntries(one);
            MerkleProof proof = tree.generateProof(0);

            assertThat(proof.length()).isZero();
            assertThat(proof.sizeInBytes()).isZero();
            assertThat(MerkleVerifier.verify(one.get(0), proof, tree.root())).isTrue();
        }

        @Test
        @DisplayName("an empty proof is rejected against a wrong root — it is not vacuously true")
        void emptyProofRejectsWrongRoot() {
            // The trap this guards: a verifier that returns early for a zero-step proof would
            // pass the test above while accepting anything at all. Verification of an empty
            // proof must still be a real comparison.
            List<LogEntry> one = entries(1);
            MerkleTree tree = MerkleTree.fromEntries(one);
            MerkleProof proof = tree.generateProof(0);

            byte[] wrongRoot = Hashing.leafHash(Hashing.utf8("some other data"));
            assertThat(MerkleVerifier.verify(one.get(0), proof, wrongRoot)).isFalse();

            // ...and a different entry must not verify against the real root either.
            LogEntry impostor = one.get(0).withMessage("different");
            assertThat(MerkleVerifier.verify(impostor, proof, tree.root())).isFalse();
        }

        @Test
        @DisplayName("computeRoot on an empty proof returns the leaf hash unchanged")
        void emptyProofComputesLeafHash() {
            LogEntry only = entries(1).get(0);
            MerkleProof empty = new MerkleProof(0, List.of(), 1);

            assertThat(MerkleVerifier.computeRoot(only.leafHash(), empty)).isEqualTo(only.leafHash());
        }
    }

    @Nested
    @DisplayName("verification trace, for the step-through UI")
    class Trace {

        @Test
        @DisplayName("the trace has one step per proof rung and ends at the root")
        void traceMirrorsTheProof() {
            List<LogEntry> entries = entries(8);
            MerkleTree tree = MerkleTree.fromEntries(entries);
            MerkleProof proof = tree.generateProof(5);

            MerkleVerifier.VerificationTrace trace =
                    MerkleVerifier.verifyWithTrace(entries.get(5).leafHash(), proof, tree.root());

            assertThat(trace.valid()).isTrue();
            assertThat(trace.steps()).hasSize(proof.length());
            assertThat(trace.computedRoot()).isEqualTo(tree.root());
            assertThat(trace.computedRootHex()).isEqualTo(trace.expectedRootHex());
        }

        @Test
        @DisplayName("each trace step chains into the next, starting at the leaf hash")
        void traceStepsChainTogether() {
            List<LogEntry> entries = entries(16);
            MerkleTree tree = MerkleTree.fromEntries(entries);
            byte[] leafHash = entries.get(9).leafHash();

            MerkleVerifier.VerificationTrace trace =
                    MerkleVerifier.verifyWithTrace(leafHash, tree.generateProof(9), tree.root());

            byte[] expectedInput = leafHash;
            for (MerkleVerifier.TraceStep step : trace.steps()) {
                assertThat(step.runningBefore()).isEqualTo(expectedInput);
                // The output must genuinely be the hash of the two inputs in the stated order.
                byte[] recombined = step.side() == ProofStep.Side.LEFT
                        ? Hashing.nodeHash(step.sibling(), step.runningBefore())
                        : Hashing.nodeHash(step.runningBefore(), step.sibling());
                assertThat(step.runningAfter()).isEqualTo(recombined);
                expectedInput = step.runningAfter();
            }
            assertThat(expectedInput).isEqualTo(tree.root());
        }

        @Test
        @DisplayName("a failing verification still produces a full trace, flagged invalid")
        void traceReportsFailure() {
            // The UI needs to show where a bad proof diverges, not just that it failed.
            List<LogEntry> entries = entries(8);
            MerkleTree tree = MerkleTree.fromEntries(entries);
            LogEntry tampered = entries.get(3).withMessage("tampered");

            MerkleVerifier.VerificationTrace trace = MerkleVerifier.verifyWithTrace(
                    tampered.leafHash(), tree.generateProof(3), tree.root());

            assertThat(trace.valid()).isFalse();
            assertThat(trace.steps()).hasSize(3);
            assertThat(trace.computedRoot()).isNotEqualTo(trace.expectedRoot());
        }
    }

    @Nested
    @DisplayName("validation and immutability")
    class ValidationAndImmutability {

        @Test
        @DisplayName("proofs cannot be requested for out-of-range or absent leaves")
        void rejectsBadIndex() {
            MerkleTree tree = MerkleTree.fromEntries(entries(4));

            assertThatThrownBy(() -> tree.generateProof(4)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> tree.generateProof(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> MerkleTree.fromEntries(List.of()).generateProof(0))
                    .isInstanceOf(IndexOutOfBoundsException.class)
                    .hasMessageContaining("no leaves");
        }

        @Test
        @DisplayName("a proof cannot be mutated after it is issued")
        void proofIsImmutable() {
            MerkleTree tree = MerkleTree.fromEntries(entries(8));
            MerkleProof proof = tree.generateProof(2);

            assertThatThrownBy(() -> proof.steps().add(proof.steps().get(0)))
                    .isInstanceOf(UnsupportedOperationException.class);

            // Mutating a handed-out sibling hash must not affect the proof either.
            byte[] handed = proof.steps().get(0).siblingHash();
            handed[0] ^= 0xFF;
            assertThat(MerkleVerifier.verify(entries(8).get(2), proof, tree.root())).isTrue();
        }

        @Test
        @DisplayName("verification rejects a leaf hash of the wrong length")
        void rejectsMalformedLeafHash() {
            MerkleTree tree = MerkleTree.fromEntries(entries(4));
            MerkleProof proof = tree.generateProof(0);

            assertThatThrownBy(() -> MerkleVerifier.verify(new byte[16], proof, tree.root()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("a proof step rejects a malformed sibling hash")
        void proofStepValidatesSibling() {
            assertThatThrownBy(() -> new ProofStep(new byte[31], ProofStep.Side.LEFT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
