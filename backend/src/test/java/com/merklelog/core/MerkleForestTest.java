package com.merklelog.core;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;
import com.merklelog.chunking.FixedSizeChunking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.merklelog.core.MerkleTreeTest.entries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MerkleForest — per-chunk trees under a super-root")
class MerkleForestTest {

    static Stream<ChunkingStrategy> allStrategies() {
        return ChunkingStrategyFactory.allWithDefaults().stream();
    }

    @Nested
    @DisplayName("proofs")
    class Proofs {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.merklelog.core.MerkleForestTest#allStrategies")
        @DisplayName("every entry verifies under every strategy")
        void everyEntryVerifiesUnderEveryStrategy(ChunkingStrategy strategy) {
            List<LogEntry> input = entries(500);
            MerkleForest forest = MerkleForest.build(input, strategy);
            byte[] trustedRoot = forest.superRoot();

            for (int i = 0; i < input.size(); i++) {
                ForestProof proof = forest.generateProof(i);
                assertThat(MerkleForest.verify(input.get(i).leafHash(), proof, trustedRoot))
                        .as("entry %d under %s", i, strategy.name())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a proof verifies with no access to the forest or the other entries")
        void proofIsSelfContained() {
            List<LogEntry> input = entries(300);
            MerkleForest forest = MerkleForest.build(input, new FixedSizeChunking(32));

            // All an auditor holds: one entry, its proof, and the published super-root.
            assertThat(MerkleForest.verify(
                    input.get(211).leafHash(), forest.generateProof(211), forest.superRoot())).isTrue();
        }

        @ParameterizedTest(name = "chunkSize = {0}")
        @ValueSource(ints = {1, 2, 8, 64, 256, 1024})
        @DisplayName("proof cost is log(chunkSize) + log(chunkCount), never linear")
        void proofCostIsLogarithmic(int chunkSize) {
            int total = 1024;
            MerkleForest forest = MerkleForest.build(entries(total), new FixedSizeChunking(chunkSize));

            int chunkCount = forest.chunkCount();
            int bound = MerkleTreeTest.ceilLog2(chunkSize) + MerkleTreeTest.ceilLog2(chunkCount);

            for (int i = 0; i < total; i++) {
                assertThat(forest.generateProof(i).totalSteps())
                        .as("entry %d with chunkSize %d", i, chunkSize)
                        .isLessThanOrEqualTo(bound);
            }
        }

        @Test
        @DisplayName("the two proof stages split the work as documented")
        void proofStagesAreCorrect() {
            // 1024 entries in chunks of 64 = 16 chunks. Entry proof climbs log2(64) = 6,
            // chunk proof climbs log2(16) = 4.
            MerkleForest forest = MerkleForest.build(entries(1024), new FixedSizeChunking(64));
            ForestProof proof = forest.generateProof(100);

            assertThat(forest.chunkCount()).isEqualTo(16);
            assertThat(proof.entryProof().length()).isEqualTo(6);
            assertThat(proof.chunkProof().length()).isEqualTo(4);
            assertThat(proof.totalSteps()).isEqualTo(10);
            assertThat(proof.sizeInBytes()).isEqualTo(10 * 32);
        }

        @Test
        @DisplayName("the entry index maps to the right chunk and position")
        void indexMappingIsCorrect() {
            MerkleForest forest = MerkleForest.build(entries(250), new FixedSizeChunking(64));

            ForestProof first = forest.generateProof(0);
            assertThat(first.chunkIndex()).isZero();
            assertThat(first.localIndex()).isZero();

            ForestProof boundary = forest.generateProof(64);
            assertThat(boundary.chunkIndex()).isEqualTo(1);
            assertThat(boundary.localIndex()).isZero();

            ForestProof middle = forest.generateProof(130);
            assertThat(middle.chunkIndex()).isEqualTo(2);
            assertThat(middle.localIndex()).isEqualTo(2);

            ForestProof last = forest.generateProof(249);
            assertThat(last.chunkIndex()).isEqualTo(3);
            assertThat(last.localIndex()).isEqualTo(57);
        }

        @Test
        @DisplayName("a proof for one entry does not vouch for another")
        void proofsAreNotTransferable() {
            List<LogEntry> input = entries(200);
            MerkleForest forest = MerkleForest.build(input, new FixedSizeChunking(16));
            ForestProof proofForFifty = forest.generateProof(50);

            for (int i = 0; i < input.size(); i++) {
                if (i == 50) continue;
                assertThat(MerkleForest.verify(input.get(i).leafHash(), proofForFifty, forest.superRoot()))
                        .as("entry %d", i).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("tamper detection across the forest")
    class TamperDetection {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.merklelog.core.MerkleForestTest#allStrategies")
        @DisplayName("tampering with any entry changes the super-root and fails verification")
        void tamperingIsDetected(ChunkingStrategy strategy) {
            List<LogEntry> input = entries(200);
            MerkleForest forest = MerkleForest.build(input, strategy);
            byte[] trustedRoot = forest.superRoot();

            for (int i = 0; i < input.size(); i++) {
                LogEntry tampered = input.get(i).withMessage("tampered");
                MerkleForest.RebuildResult rebuilt = forest.withEntryReplaced(i, tampered);

                assertThat(rebuilt.forest().superRoot())
                        .as("entry %d under %s", i, strategy.name())
                        .isNotEqualTo(trustedRoot);

                // Even with a freshly generated proof from the rebuilt forest, the tampered
                // entry cannot be made to match the root the auditor already trusts.
                assertThat(MerkleForest.verify(
                        tampered.leafHash(), rebuilt.forest().generateProof(i), trustedRoot)).isFalse();
            }
        }

        @Test
        @DisplayName("only the tampered entry fails — the rest stay provable")
        void tamperingIsLocalised() {
            List<LogEntry> input = entries(256);
            MerkleForest forest = MerkleForest.build(input, new FixedSizeChunking(32));

            int culprit = 100;
            List<Integer> failures = new ArrayList<>();
            for (int i = 0; i < input.size(); i++) {
                LogEntry claimed = (i == culprit) ? input.get(i).withMessage("tampered") : input.get(i);
                if (!MerkleForest.verify(claimed.leafHash(), forest.generateProof(i), forest.superRoot())) {
                    failures.add(i);
                }
            }

            assertThat(failures).containsExactly(culprit);
        }
    }

    @Nested
    @DisplayName("localised rebuild — the practical payoff")
    class LocalisedRebuild {

        @Test
        @DisplayName("only the affected chunk is re-hashed, not the dataset")
        void onlyTheAffectedChunkIsRebuilt() {
            MerkleForest forest = MerkleForest.build(entries(10_000), new FixedSizeChunking(64));

            MerkleForest.RebuildResult result =
                    forest.withEntryReplaced(5_000, entries(1).get(0).withMessage("replacement"));

            assertThat(result.rebuiltChunkIndex()).isEqualTo(5_000 / 64);
            assertThat(result.entriesRehashed()).isEqualTo(64);
            assertThat(result.entriesInDataset()).isEqualTo(10_000);
            // 156x cheaper than re-hashing everything — the number the Phase 7 dashboard reports.
            assertThat(result.savingFactor()).isGreaterThan(150.0);
        }

        @Test
        @DisplayName("chunks other than the rebuilt one keep their exact roots")
        void untouchedChunksKeepTheirRoots() {
            MerkleForest forest = MerkleForest.build(entries(500), new FixedSizeChunking(50));
            MerkleForest rebuilt = forest.withEntryReplaced(120, entries(1).get(0)).forest();

            for (int c = 0; c < forest.chunkCount(); c++) {
                if (c == 2) {
                    assertThat(rebuilt.chunkRoot(c)).as("rebuilt chunk").isNotEqualTo(forest.chunkRoot(c));
                } else {
                    assertThat(rebuilt.chunkRoot(c)).as("chunk %d untouched", c).isEqualTo(forest.chunkRoot(c));
                }
            }
        }

        @Test
        @DisplayName("rebuilding does not mutate the original forest")
        void rebuildIsNonMutating() {
            // The Phase 5 UI shows original and tampered side by side, so both must survive.
            MerkleForest original = MerkleForest.build(entries(100), new FixedSizeChunking(25));
            byte[] rootBefore = original.superRoot();

            original.withEntryReplaced(10, entries(1).get(0).withMessage("tampered"));

            assertThat(original.superRoot()).isEqualTo(rootBefore);
        }

        @Test
        @DisplayName("restoring the original entry restores the original super-root")
        void restoringContentRestoresRoot() {
            List<LogEntry> input = entries(128);
            MerkleForest original = MerkleForest.build(input, new FixedSizeChunking(16));
            byte[] originalRoot = original.superRoot();

            MerkleForest tampered = original.withEntryReplaced(70, input.get(70).withMessage("x")).forest();
            assertThat(tampered.superRoot()).isNotEqualTo(originalRoot);

            MerkleForest restored = tampered.withEntryReplaced(70, input.get(70)).forest();
            assertThat(restored.superRoot()).isEqualTo(originalRoot);
        }

        @Test
        @DisplayName("chunk boundaries are preserved across a rebuild")
        void rebuildPreservesBoundaries() {
            // Re-running the strategy could move every boundary and turn a one-entry edit into
            // a full rebuild, which would make rebuild cost meaningless to measure.
            MerkleForest forest = MerkleForest.build(entries(300), new FixedSizeChunking(40));
            MerkleForest rebuilt = forest.withEntryReplaced(50, entries(1).get(0)).forest();

            assertThat(rebuilt.chunks().stream().map(Chunk::size).toList())
                    .isEqualTo(forest.chunks().stream().map(Chunk::size).toList());
        }
    }

    @Nested
    @DisplayName("degenerate cases — defined, not incidental")
    class DegenerateCases {

        @Test
        @DisplayName("no entries: no chunks, empty-tree super-root, proofs rejected")
        void emptyForest() {
            MerkleForest forest = MerkleForest.build(List.of(), new FixedSizeChunking(64));

            assertThat(forest.isEmpty()).isTrue();
            assertThat(forest.chunkCount()).isZero();
            assertThat(forest.entryCount()).isZero();
            assertThat(forest.superRoot()).isNotNull().isEqualTo(Hashing.emptyTreeHash());

            assertThatThrownBy(() -> forest.generateProof(0))
                    .isInstanceOf(EmptyForestException.class)
                    .hasMessageContaining("empty");
            assertThatThrownBy(() -> forest.withEntryReplaced(0, entries(1).get(0)))
                    .isInstanceOf(EmptyForestException.class);
        }

        @Test
        @DisplayName("one chunk: the super-root IS that chunk's root, promoted unchanged")
        void singleChunkPromotesItsRoot() {
            // Falls out of the existing rules rather than being special-cased: a single-leaf
            // tree's root is its leaf, and here that leaf is the chunk root.
            MerkleForest forest = MerkleForest.build(entries(40), new FixedSizeChunking(64));

            assertThat(forest.chunkCount()).isEqualTo(1);
            assertThat(forest.superRoot()).isEqualTo(forest.chunkRoot(0));
            assertThat(forest.superTree().depth()).isZero();

            // The chunk proof stage is therefore empty, and the whole proof is the entry stage.
            ForestProof proof = forest.generateProof(20);
            assertThat(proof.chunkProof().length()).isZero();
            assertThat(proof.totalSteps()).isEqualTo(proof.entryProof().length());
        }

        @Test
        @DisplayName("one entry total: root is the leaf hash and both proof stages are empty")
        void singleEntryForest() {
            List<LogEntry> one = entries(1);
            MerkleForest forest = MerkleForest.build(one, new FixedSizeChunking(64));

            assertThat(forest.chunkCount()).isEqualTo(1);
            assertThat(forest.superRoot()).isEqualTo(one.get(0).leafHash());

            ForestProof proof = forest.generateProof(0);
            assertThat(proof.totalSteps()).isZero();
            assertThat(proof.sizeInBytes()).isZero();

            // Both stages empty — and it must still be a real check, not a free pass.
            assertThat(MerkleForest.verify(one.get(0).leafHash(), proof, forest.superRoot())).isTrue();
            assertThat(MerkleForest.verify(
                    one.get(0).withMessage("different").leafHash(), proof, forest.superRoot())).isFalse();
            assertThat(MerkleForest.verify(
                    one.get(0).leafHash(), proof, Hashing.leafHash(Hashing.utf8("wrong")))).isFalse();
        }

        @Test
        @DisplayName("one entry per chunk: the maximally shallow forest still verifies")
        void oneEntryPerChunk() {
            // Every chunk tree has depth 0, so all the work lands in the super-tree. The
            // opposite extreme from the single-chunk case above.
            List<LogEntry> input = entries(64);
            MerkleForest forest = MerkleForest.build(input, new FixedSizeChunking(1));

            assertThat(forest.chunkCount()).isEqualTo(64);

            for (int i = 0; i < input.size(); i++) {
                ForestProof proof = forest.generateProof(i);
                assertThat(proof.entryProof().length()).as("entry stage %d", i).isZero();
                assertThat(proof.chunkProof().length()).as("chunk stage %d", i).isEqualTo(6);
                assertThat(MerkleForest.verify(input.get(i).leafHash(), proof, forest.superRoot())).isTrue();
            }
        }

        @Test
        @DisplayName("out-of-range entry indices are rejected")
        void rejectsBadIndices() {
            MerkleForest forest = MerkleForest.build(entries(100), new FixedSizeChunking(16));

            assertThatThrownBy(() -> forest.generateProof(100)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> forest.generateProof(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> forest.chunkTree(99)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("strategy comparison — what Phase 6 will show")
    class StrategyComparison {

        @Test
        @DisplayName("the same data under different strategies gives different structure but equal correctness")
        void strategiesDifferInShapeNotCorrectness() {
            List<LogEntry> input = entries(1000);

            for (ChunkingStrategy strategy : ChunkingStrategyFactory.allWithDefaults()) {
                MerkleForest forest = MerkleForest.build(input, strategy);

                assertThat(forest.entryCount()).isEqualTo(1000);
                assertThat(forest.strategyName()).isEqualTo(strategy.name());
                // Correctness is invariant across strategies; only cost and shape change.
                assertThat(MerkleForest.verify(
                        input.get(500).leafHash(), forest.generateProof(500), forest.superRoot())).isTrue();
            }
        }

        @Test
        @DisplayName("smaller chunks trade a shorter entry proof for a longer chunk proof")
        void chunkSizeShiftsWorkBetweenTheTwoStages() {
            // The trade-off the comparison page exists to make visible.
            List<LogEntry> input = entries(4096);

            ForestProof small = MerkleForest.build(input, new FixedSizeChunking(8)).generateProof(2000);
            ForestProof large = MerkleForest.build(input, new FixedSizeChunking(512)).generateProof(2000);

            assertThat(small.entryProof().length()).isLessThan(large.entryProof().length());
            assertThat(small.chunkProof().length()).isGreaterThan(large.chunkProof().length());
        }

        @Test
        @DisplayName("smaller chunks make rebuilds cheaper")
        void smallerChunksRebuildCheaper() {
            List<LogEntry> input = entries(4096);
            LogEntry replacement = input.get(2000).withMessage("tampered");

            int smallCost = MerkleForest.build(input, new FixedSizeChunking(16))
                    .withEntryReplaced(2000, replacement).entriesRehashed();
            int largeCost = MerkleForest.build(input, new FixedSizeChunking(512))
                    .withEntryReplaced(2000, replacement).entriesRehashed();

            assertThat(smallCost).isEqualTo(16);
            assertThat(largeCost).isEqualTo(512);
        }
    }
}
