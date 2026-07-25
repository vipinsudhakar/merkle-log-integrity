package com.merklelog.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MerkleTree} construction.
 *
 * <p>Proof generation and verification are covered separately in {@code MerkleProofTest} and
 * {@code TamperDetectionTest}; this class is only about whether the tree is <em>built</em>
 * correctly — the right shape, the right root, and the promotion rule honoured.
 */
@DisplayName("MerkleTree — construction and shape")
class MerkleTreeTest {

    // --- fixtures ----------------------------------------------------------------------

    static LogEntry entry(int i) {
        return new LogEntry(
                i,
                Instant.parse("2026-07-25T10:00:00Z").plusSeconds(i),
                "INFO",
                "sensor-" + (i % 4),
                "reading #" + i);
    }

    static List<LogEntry> entries(int count) {
        List<LogEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(entry(i));
        }
        return list;
    }

    /** ceil(log2(n)) for n >= 1 — the upper bound on proof length. */
    static int ceilLog2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    @Nested
    @DisplayName("shape")
    class Shape {

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 9, 16, 17, 100, 1000})
        @DisplayName("depth equals ceil(log2 n) and the top level holds exactly one root")
        void depthAndTopLevel(int n) {
            MerkleTree tree = MerkleTree.fromEntries(entries(n));

            assertThat(tree.leafCount()).isEqualTo(n);
            assertThat(tree.depth()).isEqualTo(ceilLog2(n));

            List<List<byte[]>> levels = tree.levels();
            assertThat(levels).hasSize(ceilLog2(n) + 1);
            assertThat(levels.get(levels.size() - 1)).hasSize(1);
        }

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 3, 5, 7, 8, 9, 17, 100})
        @DisplayName("each level is exactly ceil(size / 2) of the level below")
        void levelSizesHalveUpward(int n) {
            List<List<byte[]>> levels = MerkleTree.fromEntries(entries(n)).levels();

            for (int i = 1; i < levels.size(); i++) {
                int below = levels.get(i - 1).size();
                assertThat(levels.get(i)).as("level %d", i).hasSize((below + 1) / 2);
            }
        }

        @Test
        @DisplayName("a single-leaf tree has the leaf hash as its root and depth 0")
        void singleLeafTree() {
            LogEntry only = entry(0);
            MerkleTree tree = MerkleTree.fromEntries(List.of(only));

            assertThat(tree.leafCount()).isEqualTo(1);
            assertThat(tree.depth()).isZero();
            // No node hashing happens at all — the root IS the leaf hash.
            assertThat(tree.root()).isEqualTo(only.leafHash());
        }

        @Test
        @DisplayName("an empty tree reports the RFC 6962 empty-tree hash, not null")
        void emptyTree() {
            MerkleTree tree = MerkleTree.fromEntries(List.of());

            assertThat(tree.isEmpty()).isTrue();
            assertThat(tree.leafCount()).isZero();
            assertThat(tree.depth()).isZero();
            assertThat(tree.root()).isNotNull().isEqualTo(Hashing.emptyTreeHash());
            assertThat(tree.toNodeTree()).isNull();
        }
    }

    @Nested
    @DisplayName("odd-node promotion")
    class OddNodePromotion {

        @Test
        @DisplayName("three leaves build the documented shape, hash for hash")
        void threeLeavesBuildExpectedShape() {
            // Fully hand-computed so the promotion rule is pinned to concrete values rather
            // than to whatever the implementation happens to produce.
            List<LogEntry> three = entries(3);
            byte[] a = three.get(0).leafHash();
            byte[] b = three.get(1).leafHash();
            byte[] c = three.get(2).leafHash();

            byte[] ab = Hashing.nodeHash(a, b);
            byte[] expectedRoot = Hashing.nodeHash(ab, c); // c promoted, then combined

            MerkleTree tree = MerkleTree.fromEntries(three);
            List<List<byte[]>> levels = tree.levels();

            assertThat(levels).hasSize(3);
            assertThat(levels.get(0)).containsExactly(a, b, c);
            assertThat(levels.get(1)).containsExactly(ab, c); // c carried up UNCHANGED
            assertThat(tree.root()).isEqualTo(expectedRoot);
        }

        @Test
        @DisplayName("the odd node is promoted, never hashed with itself")
        void oddNodeIsNotDuplicated() {
            List<LogEntry> three = entries(3);
            byte[] c = three.get(2).leafHash();

            List<byte[]> levelOne = MerkleTree.fromEntries(three).levels().get(1);

            // Bitcoin's rule would give H(c, c) here. That is CVE-2012-2459: because H(x,x)
            // is reachable from two different leaf lists, the root stops uniquely committing
            // to its leaves. We promote instead.
            assertThat(levelOne.get(1)).isEqualTo(c).isNotEqualTo(Hashing.nodeHash(c, c));
        }

        @Test
        @DisplayName("appending a leaf that duplicates the last one changes the root")
        void duplicateLastLeafChangesRoot() {
            // The concrete forgery that duplication would enable: with Bitcoin's rule the
            // 3-leaf list [a,b,c] and the 4-leaf list [a,b,c,c] hash to the SAME root.
            // Under promotion they must differ.
            List<LogEntry> three = entries(3);
            List<LogEntry> threePlusRepeat = new ArrayList<>(three);
            threePlusRepeat.add(three.get(2));

            assertThat(MerkleTree.fromEntries(three).root())
                    .isNotEqualTo(MerkleTree.fromEntries(threePlusRepeat).root());
        }
    }

    @Nested
    @DisplayName("root sensitivity")
    class RootSensitivity {

        @Test
        @DisplayName("changing any single entry changes the root")
        void anyEntryChangeChangesRoot() {
            List<LogEntry> original = entries(9);
            byte[] originalRoot = MerkleTree.fromEntries(original).root();

            for (int i = 0; i < original.size(); i++) {
                List<LogEntry> mutated = new ArrayList<>(original);
                mutated.set(i, mutated.get(i).withMessage("tampered"));

                assertThat(MerkleTree.fromEntries(mutated).root())
                        .as("mutating entry %d must change the root", i)
                        .isNotEqualTo(originalRoot);
            }
        }

        @Test
        @DisplayName("reordering entries changes the root — the tree commits to order")
        void reorderingChangesRoot() {
            List<LogEntry> original = entries(8);
            List<LogEntry> swapped = new ArrayList<>(original);
            swapped.set(0, original.get(1));
            swapped.set(1, original.get(0));

            assertThat(MerkleTree.fromEntries(swapped).root())
                    .isNotEqualTo(MerkleTree.fromEntries(original).root());
        }

        @Test
        @DisplayName("identical entry lists produce identical roots — build is deterministic")
        void buildIsDeterministic() {
            assertThat(MerkleTree.fromEntries(entries(37)).root())
                    .isEqualTo(MerkleTree.fromEntries(entries(37)).root());
        }
    }

    @Nested
    @DisplayName("node-tree view (visualisation)")
    class NodeTreeView {

        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {1, 2, 3, 4, 7, 8, 16})
        @DisplayName("the node view has the same root hash and leaf count as the level view")
        void nodeViewAgreesWithLevelView(int n) {
            MerkleTree tree = MerkleTree.fromEntries(entries(n));
            MerkleNode root = tree.toNodeTree();

            assertThat(root.hash()).isEqualTo(tree.root());
            assertThat(countLeaves(root)).isEqualTo(n);
        }

        @Test
        @DisplayName("leaves appear left to right in entry order")
        void leavesAreInEntryOrder() {
            MerkleTree tree = MerkleTree.fromEntries(entries(7));
            List<Integer> order = new ArrayList<>();
            collectLeafIndices(tree.toNodeTree(), order);

            assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6);
        }

        @Test
        @DisplayName("a single-leaf tree's node view is one leaf with no children")
        void singleLeafNodeView() {
            MerkleNode root = MerkleTree.fromEntries(entries(1)).toNodeTree();

            assertThat(root.isLeaf()).isTrue();
            assertThat(root.leafIndex()).isZero();
            assertThat(root.height()).isZero();
            assertThat(root.left()).isNull();
            assertThat(root.right()).isNull();
        }

        private int countLeaves(MerkleNode node) {
            return node.isLeaf() ? 1 : countLeaves(node.left()) + countLeaves(node.right());
        }

        private void collectLeafIndices(MerkleNode node, List<Integer> out) {
            if (node.isLeaf()) {
                out.add(node.leafIndex());
                return;
            }
            collectLeafIndices(node.left(), out);
            collectLeafIndices(node.right(), out);
        }
    }

    @Nested
    @DisplayName("input validation and immutability")
    class ValidationAndImmutability {

        @Test
        @DisplayName("leaf hashes of the wrong length are rejected")
        void rejectsWrongLengthLeafHash() {
            List<byte[]> bad = List.of(Hashing.leafHash(Hashing.utf8("ok")), new byte[8]);

            assertThatThrownBy(() -> MerkleTree.fromLeafHashes(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("index 1");
        }

        @Test
        @DisplayName("out-of-range leaf access is rejected")
        void rejectsBadLeafIndex() {
            MerkleTree tree = MerkleTree.fromEntries(entries(4));

            assertThatThrownBy(() -> tree.leafHash(4)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> tree.leafHash(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> MerkleTree.fromEntries(List.of()).leafHash(0))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("mutating a returned hash cannot corrupt the tree")
        void returnedHashesAreCopies() {
            MerkleTree tree = MerkleTree.fromEntries(entries(4));
            byte[] rootBefore = tree.root();

            byte[] handed = tree.root();
            handed[0] ^= 0xFF; // caller vandalises their copy

            assertThat(tree.root()).isEqualTo(rootBefore);
        }

        @Test
        @DisplayName("mutating the caller's leaf-hash list cannot corrupt the tree")
        void inputHashesAreCopied() {
            List<byte[]> input = new ArrayList<>(List.of(
                    Hashing.leafHash(Hashing.utf8("a")),
                    Hashing.leafHash(Hashing.utf8("b"))));

            MerkleTree tree = MerkleTree.fromLeafHashes(input);
            byte[] rootBefore = tree.root();

            input.get(0)[0] ^= 0xFF;

            assertThat(tree.root()).isEqualTo(rootBefore);
        }
    }
}
