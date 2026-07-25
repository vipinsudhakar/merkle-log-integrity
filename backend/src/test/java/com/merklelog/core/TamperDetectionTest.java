package com.merklelog.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static com.merklelog.core.MerkleTreeTest.entries;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests the project actually stands on: tampering is detected, and forged proofs are not
 * accepted.
 *
 * <p>Everything else in {@code core} is machinery. If these pass, the system does what it
 * claims; if any of them fail, nothing else matters.
 */
@DisplayName("Tamper detection — the core guarantee")
class TamperDetectionTest {

    @Nested
    @DisplayName("detection")
    class Detection {

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 3, 8, 9, 17, 64, 100})
        @DisplayName("tampering with ANY single entry is detected, for every entry")
        void everySingleEntryTamperIsDetected(int n) {
            List<LogEntry> original = entries(n);
            MerkleTree originalTree = MerkleTree.fromEntries(original);
            byte[] trustedRoot = originalTree.root();

            for (int i = 0; i < n; i++) {
                List<LogEntry> tampered = new ArrayList<>(original);
                tampered.set(i, tampered.get(i).withMessage("PAYMENT APPROVED"));

                MerkleTree tamperedTree = MerkleTree.fromEntries(tampered);

                // 1. The root moves. This alone flags that *something* changed, in O(1)
                //    comparison against a trusted root.
                assertThat(tamperedTree.root())
                        .as("tampering entry %d must change the root", i)
                        .isNotEqualTo(trustedRoot);

                // 2. The tampered entry fails verification against the trusted root, even
                //    using the proof freshly generated from the tampered tree. An attacker
                //    who rewrites both the log and the tree still cannot match the old root.
                assertThat(MerkleVerifier.verify(
                        tampered.get(i), tamperedTree.generateProof(i), trustedRoot))
                        .as("tampered entry %d must not verify against the trusted root", i)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a single flipped bit anywhere in a payload is detected")
        void singleBitFlipIsDetected() {
            List<LogEntry> original = entries(32);
            byte[] trustedRoot = MerkleTree.fromEntries(original).root();

            String message = original.get(7).message();
            byte[] messageBytes = Hashing.utf8(message);

            // Flip each bit of the message in turn. SHA-256's avalanche property means the
            // leaf hash changes completely, but the test asserts it rather than assuming it.
            for (int bytePos = 0; bytePos < messageBytes.length; bytePos++) {
                for (int bit = 0; bit < 8; bit++) {
                    byte[] flipped = messageBytes.clone();
                    flipped[bytePos] ^= (byte) (1 << bit);

                    List<LogEntry> tampered = new ArrayList<>(original);
                    tampered.set(7, original.get(7).withMessage(new String(flipped, java.nio.charset.StandardCharsets.ISO_8859_1)));

                    assertThat(MerkleTree.fromEntries(tampered).root())
                            .as("flipping bit %d of byte %d must change the root", bit, bytePos)
                            .isNotEqualTo(trustedRoot);
                }
            }
        }

        @Test
        @DisplayName("untampered entries still verify after a neighbour is tampered with")
        void untamperedEntriesStillVerifyInTheTamperedTree() {
            // Localisation: the damage is confined to the path from the tampered leaf. Every
            // other entry remains provably intact *within the new tree*, which is what lets
            // the UI point at one specific bad entry instead of declaring the whole log bad.
            List<LogEntry> tampered = new ArrayList<>(entries(16));
            tampered.set(6, tampered.get(6).withMessage("tampered"));

            MerkleTree tamperedTree = MerkleTree.fromEntries(tampered);

            for (int i = 0; i < tampered.size(); i++) {
                assertThat(MerkleVerifier.verify(
                        tampered.get(i), tamperedTree.generateProof(i), tamperedTree.root()))
                        .as("entry %d is internally consistent", i)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the tampered entry can be localised by checking entries against the trusted root")
        void tamperingCanBeLocalised() {
            // How the Phase 5 UI identifies *which* entry was altered: hold the trusted root,
            // check each entry's proof, and the one that fails is the culprit.
            List<LogEntry> original = entries(16);
            MerkleTree originalTree = MerkleTree.fromEntries(original);

            List<LogEntry> tampered = new ArrayList<>(original);
            int culprit = 11;
            tampered.set(culprit, original.get(culprit).withMessage("tampered"));

            List<Integer> failures = new ArrayList<>();
            for (int i = 0; i < tampered.size(); i++) {
                // Proofs come from the original tree — the auditor's copy of the structure.
                if (!MerkleVerifier.verify(tampered.get(i), originalTree.generateProof(i), originalTree.root())) {
                    failures.add(i);
                }
            }

            assertThat(failures).containsExactly(culprit);
        }

        @Test
        @DisplayName("deleting an entry is detected")
        void deletionIsDetected() {
            List<LogEntry> original = entries(9);
            byte[] trustedRoot = MerkleTree.fromEntries(original).root();

            List<LogEntry> withDeletion = new ArrayList<>(original);
            withDeletion.remove(4);

            assertThat(MerkleTree.fromEntries(withDeletion).root()).isNotEqualTo(trustedRoot);
        }

        @Test
        @DisplayName("inserting an entry is detected")
        void insertionIsDetected() {
            List<LogEntry> original = entries(9);
            byte[] trustedRoot = MerkleTree.fromEntries(original).root();

            List<LogEntry> withInsertion = new ArrayList<>(original);
            withInsertion.add(4, original.get(0).withMessage("injected"));

            assertThat(MerkleTree.fromEntries(withInsertion).root()).isNotEqualTo(trustedRoot);
        }
    }

    @Nested
    @DisplayName("forged proofs are rejected")
    class ForgedProofs {

        private final List<LogEntry> entries = entries(16);
        private final MerkleTree tree = MerkleTree.fromEntries(entries);

        @Test
        @DisplayName("a proof with its sides flipped does not verify")
        void flippedSidesRejected() {
            MerkleProof honest = tree.generateProof(5);

            List<ProofStep> flipped = new ArrayList<>();
            for (ProofStep step : honest.steps()) {
                flipped.add(new ProofStep(step.siblingHash(),
                        step.side() == ProofStep.Side.LEFT ? ProofStep.Side.RIGHT : ProofStep.Side.LEFT));
            }

            // If node hashing were commutative — or if the verifier sorted the pair instead of
            // honouring the recorded side — this forgery would succeed.
            assertThat(MerkleVerifier.verify(
                    entries.get(5), new MerkleProof(5, flipped, 16), tree.root())).isFalse();
        }

        @Test
        @DisplayName("a proof with its steps reordered does not verify")
        void reorderedStepsRejected() {
            MerkleProof honest = tree.generateProof(5);

            List<ProofStep> reversed = new ArrayList<>(honest.steps());
            java.util.Collections.reverse(reversed);

            assertThat(MerkleVerifier.verify(
                    entries.get(5), new MerkleProof(5, reversed, 16), tree.root())).isFalse();
        }

        @Test
        @DisplayName("a proof with one sibling substituted does not verify")
        void substitutedSiblingRejected() {
            MerkleProof honest = tree.generateProof(5);

            for (int i = 0; i < honest.length(); i++) {
                List<ProofStep> tampered = new ArrayList<>(honest.steps());
                byte[] bogus = Hashing.leafHash(Hashing.utf8("forged sibling"));
                tampered.set(i, new ProofStep(bogus, honest.steps().get(i).side()));

                assertThat(MerkleVerifier.verify(entries.get(5), new MerkleProof(5, tampered, 16), tree.root()))
                        .as("substituting sibling %d", i)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a truncated or padded proof does not verify")
        void wrongLengthProofRejected() {
            MerkleProof honest = tree.generateProof(5);

            List<ProofStep> truncated = new ArrayList<>(honest.steps().subList(0, honest.length() - 1));
            assertThat(MerkleVerifier.verify(entries.get(5), new MerkleProof(5, truncated, 16), tree.root()))
                    .as("truncated").isFalse();

            List<ProofStep> padded = new ArrayList<>(honest.steps());
            padded.add(new ProofStep(Hashing.leafHash(Hashing.utf8("extra")), ProofStep.Side.RIGHT));
            assertThat(MerkleVerifier.verify(entries.get(5), new MerkleProof(5, padded, 16), tree.root()))
                    .as("padded").isFalse();

            assertThat(MerkleVerifier.verify(entries.get(5), new MerkleProof(5, List.of(), 16), tree.root()))
                    .as("empty").isFalse();
        }

        @Test
        @DisplayName("one entry's proof cannot be reused to vouch for another entry")
        void proofsAreNotTransferableBetweenEntries() {
            MerkleProof proofForFive = tree.generateProof(5);

            for (int i = 0; i < entries.size(); i++) {
                if (i == 5) continue;
                assertThat(MerkleVerifier.verify(entries.get(i), proofForFive, tree.root()))
                        .as("entry %d must not verify with leaf 5's proof", i)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("an internal node's children cannot be passed off as a leaf")
        void internalNodeCannotMasqueradeAsLeaf() {
            // The second-preimage attack the RFC 6962 prefixes exist to stop. Take the two
            // child hashes under an internal node, concatenate them, and try to present that
            // as the raw data of a leaf. Without domain separation the resulting hash would
            // equal the internal node's hash and the tree could be reinterpreted.
            List<List<byte[]>> levels = tree.levels();
            byte[] leftChild = levels.get(0).get(0);
            byte[] rightChild = levels.get(0).get(1);
            byte[] realParent = levels.get(1).get(0);

            byte[] forged = new byte[leftChild.length + rightChild.length];
            System.arraycopy(leftChild, 0, forged, 0, leftChild.length);
            System.arraycopy(rightChild, 0, forged, leftChild.length, rightChild.length);

            assertThat(Hashing.leafHash(forged)).isNotEqualTo(realParent);
        }
    }

    @Nested
    @DisplayName("rebuild after tampering")
    class RebuildBehaviour {

        @Test
        @DisplayName("restoring the original content restores the original root")
        void restoringContentRestoresRoot() {
            // Tamper-evidence, not tamper-proofing: the root is a pure function of the data,
            // so putting the data back puts the root back. What an attacker cannot do is
            // change the data and keep the root.
            List<LogEntry> original = entries(12);
            byte[] originalRoot = MerkleTree.fromEntries(original).root();

            List<LogEntry> tampered = new ArrayList<>(original);
            tampered.set(3, original.get(3).withMessage("tampered"));
            assertThat(MerkleTree.fromEntries(tampered).root()).isNotEqualTo(originalRoot);

            tampered.set(3, original.get(3));
            assertThat(MerkleTree.fromEntries(tampered).root()).isEqualTo(originalRoot);
        }
    }
}
