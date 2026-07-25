package com.merklelog.core;

import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Randomised property tests over the whole core.
 *
 * <p>The example-based tests elsewhere check cases someone thought of. These check properties
 * that must hold for <em>every</em> input, across many shapes at once — the sizes that are
 * awkward to enumerate by hand, and the exhaustive bit-level sweeps that would be tedious to
 * write out.
 *
 * <p>Every generator is seeded with a fixed value. A property test that produced different
 * inputs on each run would be a test that fails for someone else and not for you, and one
 * that cannot be reproduced during a viva demonstration.
 */
@DisplayName("Property tests — invariants over randomised inputs")
class MerklePropertyTest {

    private static final long SEED = 20260725L;
    private static final Instant BASE = Instant.parse("2026-07-25T10:00:00Z");

    /** Random entries with varied payload length and content. */
    private static List<LogEntry> randomEntries(Random random, int count) {
        String[] levels = {"DEBUG", "INFO", "WARN", "ERROR"};
        List<LogEntry> entries = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int messageLength = 1 + random.nextInt(80);
            StringBuilder message = new StringBuilder(messageLength);
            for (int c = 0; c < messageLength; c++) {
                message.append((char) ('a' + random.nextInt(26)));
            }
            entries.add(new LogEntry(
                    i,
                    BASE.plusSeconds(random.nextInt(10_000)),
                    levels[random.nextInt(levels.length)],
                    "device-" + random.nextInt(20),
                    message.toString()));
        }
        return entries;
    }

    @Test
    @DisplayName("for random tree sizes, every leaf's proof verifies and no other leaf's does")
    void everyLeafVerifiesAndOnlyThatLeaf() {
        Random random = new Random(SEED);

        for (int trial = 0; trial < 60; trial++) {
            int n = 1 + random.nextInt(512);
            List<LogEntry> entries = randomEntries(random, n);
            MerkleTree tree = MerkleTree.fromEntries(entries);
            byte[] root = tree.root();

            for (int i = 0; i < n; i++) {
                MerkleProof proof = tree.generateProof(i);

                assertThat(MerkleVerifier.verify(entries.get(i), proof, root))
                        .as("n=%d leaf=%d must verify", n, i).isTrue();

                // The same proof must not vouch for a different entry. Checking one other
                // leaf per proof keeps this O(n) per trial while still covering every proof.
                int other = (i + 1 + random.nextInt(Math.max(1, n - 1))) % n;
                if (other != i) {
                    assertThat(MerkleVerifier.verify(entries.get(other), proof, root))
                            .as("n=%d leaf=%d proof must not verify leaf %d", n, i, other).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("proof length never exceeds ceil(log2 n), for random sizes")
    void proofLengthStaysLogarithmic() {
        Random random = new Random(SEED);

        for (int trial = 0; trial < 100; trial++) {
            int n = 1 + random.nextInt(2048);
            MerkleTree tree = MerkleTree.fromEntries(randomEntries(random, n));
            int bound = MerkleTreeTest.ceilLog2(n);

            for (int i = 0; i < n; i++) {
                assertThat(tree.generateProof(i).length())
                        .as("n=%d leaf=%d", n, i).isLessThanOrEqualTo(bound);
            }
        }
    }

    @Test
    @DisplayName("every single-bit flip in every payload is detected — 100%, no exceptions")
    void everySingleBitFlipIsDetected() {
        // The strongest statement the project can make about detection, checked exhaustively
        // over every bit of every payload rather than sampled.
        Random random = new Random(SEED);
        int flipsChecked = 0;

        for (int trial = 0; trial < 8; trial++) {
            int n = 1 + random.nextInt(40);
            List<LogEntry> entries = randomEntries(random, n);
            MerkleTree tree = MerkleTree.fromEntries(entries);
            byte[] trustedRoot = tree.root();

            for (int i = 0; i < n; i++) {
                byte[] payload = entries.get(i).message().getBytes(StandardCharsets.ISO_8859_1);

                for (int bytePos = 0; bytePos < payload.length; bytePos++) {
                    for (int bit = 0; bit < 8; bit++) {
                        byte[] flipped = payload.clone();
                        flipped[bytePos] ^= (byte) (1 << bit);

                        LogEntry tampered = entries.get(i)
                                .withMessage(new String(flipped, StandardCharsets.ISO_8859_1));

                        List<LogEntry> mutated = new ArrayList<>(entries);
                        mutated.set(i, tampered);

                        // Both detection routes must fire: the root moves, and the tampered
                        // entry fails against the root the auditor trusts.
                        assertThat(MerkleTree.fromEntries(mutated).root())
                                .as("trial %d entry %d byte %d bit %d: root must change",
                                        trial, i, bytePos, bit)
                                .isNotEqualTo(trustedRoot);

                        assertThat(MerkleVerifier.verify(tampered, tree.generateProof(i), trustedRoot))
                                .as("trial %d entry %d byte %d bit %d: must fail verification",
                                        trial, i, bytePos, bit)
                                .isFalse();

                        flipsChecked++;
                    }
                }
            }
        }

        // Guards against the test silently doing nothing if the generator ever changes.
        assertThat(flipsChecked).isGreaterThan(1_000);
    }

    @Test
    @DisplayName("distinct datasets produce distinct roots across many random pairs")
    void distinctDatasetsProduceDistinctRoots() {
        Random random = new Random(SEED);
        List<String> roots = new ArrayList<>();

        for (int trial = 0; trial < 200; trial++) {
            roots.add(MerkleTree.fromEntries(randomEntries(random, 1 + random.nextInt(50))).rootHex());
        }

        // A collision here would mean two different datasets share a root — either a broken
        // build or a broken canonical encoding. (A genuine SHA-256 collision is not the
        // plausible explanation.)
        assertThat(roots).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("forest proofs verify for every entry, under every strategy, at random sizes")
    void forestProofsVerifyAcrossStrategies() {
        Random random = new Random(SEED);

        for (ChunkingStrategy strategy : ChunkingStrategyFactory.allWithDefaults()) {
            for (int trial = 0; trial < 8; trial++) {
                int n = 1 + random.nextInt(400);
                List<LogEntry> entries = randomEntries(random, n);
                MerkleForest forest = MerkleForest.build(entries, strategy);
                byte[] superRoot = forest.superRoot();

                for (int i = 0; i < n; i++) {
                    assertThat(MerkleForest.verify(entries.get(i).leafHash(), forest.generateProof(i), superRoot))
                            .as("%s n=%d entry=%d", strategy.name(), n, i).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("tampering any entry of a random forest is always detected, under every strategy")
    void forestTamperingAlwaysDetected() {
        Random random = new Random(SEED);

        for (ChunkingStrategy strategy : ChunkingStrategyFactory.allWithDefaults()) {
            for (int trial = 0; trial < 6; trial++) {
                int n = 1 + random.nextInt(200);
                List<LogEntry> entries = randomEntries(random, n);
                MerkleForest forest = MerkleForest.build(entries, strategy);
                byte[] trustedRoot = forest.superRoot();

                for (int i = 0; i < n; i++) {
                    LogEntry tampered = entries.get(i).withMessage(entries.get(i).message() + "!");
                    MerkleForest rebuilt = forest.withEntryReplaced(i, tampered).forest();

                    assertThat(rebuilt.superRoot())
                            .as("%s n=%d entry=%d", strategy.name(), n, i).isNotEqualTo(trustedRoot);
                }
            }
        }
    }

    @Test
    @DisplayName("chunking always partitions the input, for random sizes and content")
    void chunkingAlwaysPartitions() {
        Random random = new Random(SEED);

        for (ChunkingStrategy strategy : ChunkingStrategyFactory.allWithDefaults()) {
            for (int trial = 0; trial < 40; trial++) {
                List<LogEntry> input = randomEntries(random, random.nextInt(600));

                List<LogEntry> rebuilt = new ArrayList<>();
                strategy.chunk(input).forEach(chunk -> rebuilt.addAll(chunk.entries()));

                assertThat(rebuilt)
                        .as("%s with %d entries", strategy.name(), input.size())
                        .containsExactlyElementsOf(input);
            }
        }
    }
}
