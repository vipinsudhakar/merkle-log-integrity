package com.merklelog.core;

import com.merklelog.chunking.ContentAnchoredChunking;
import com.merklelog.chunking.FixedSizeChunking;
import com.merklelog.chunking.MemoryPressureProfile;
import com.merklelog.chunking.ResourceAwareChunking;
import com.merklelog.chunking.ResourceAwareSizer;
import com.merklelog.demo.SyntheticLogGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The base paper's pipeline (Yağız et al. 2026, Algorithm 1): one global tree, rebuilt once per
 * batch. It is the baseline for rebuild cost, so these tests pin down that it behaves as the
 * paper describes rather than as a weakened stand-in.
 */
@DisplayName("PaperPipeline — the base paper's global-tree pipeline")
class PaperPipelineTest {

    private static final List<LogEntry> STREAM = SyntheticLogGenerator.generate(2_000);

    @Test
    @DisplayName("Lemma 4: the root is the same whatever the batching, equal to one global tree")
    void rootIsIndependentOfBatching() {
        byte[] globalRoot = MerkleTree.fromEntries(STREAM).root();

        assertThat(PaperPipeline.ingest(STREAM, new ResourceAwareChunking()).root()).isEqualTo(globalRoot);
        assertThat(PaperPipeline.ingest(STREAM, new FixedSizeChunking(7)).root()).isEqualTo(globalRoot);
        assertThat(PaperPipeline.ingest(STREAM, new ResourceAwareChunking(
                new ResourceAwareSizer(), MemoryPressureProfile.paperStressTest())).root()).isEqualTo(globalRoot);
    }

    @Test
    @DisplayName("ingestion hashes each entry once, then rebuilds all nodes after every batch")
    void ingestCostMatchesAlgorithmOne() {
        // Batches of 500: rebuilds over 500, 1000, 1500 and 2000 leaves.
        PaperPipeline pipeline = PaperPipeline.ingest(STREAM, new FixedSizeChunking(500));

        long expected = 2_000 + (499 + 999 + 1_499 + 1_999);
        assertThat(pipeline.batchCount()).isEqualTo(4);
        assertThat(pipeline.ingestHashOperations()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an edit costs a full rebuild: one new leaf plus all n - 1 nodes (limitation L4)")
    void editCostsAFullRebuild() {
        PaperPipeline pipeline = PaperPipeline.ingest(STREAM, new ResourceAwareChunking());

        PaperPipeline.Result result = pipeline.withEntryReplaced(1_234, STREAM.get(1_234).withMessage("x"));

        assertThat(result.hashOperations()).isEqualTo(1 + (2_000 - 1));
        assertThat(result.pipeline().root()).isNotEqualTo(pipeline.root());
    }

    @Test
    @DisplayName("the same edit in a CAAC forest costs a small fraction of the paper's rebuild")
    void caacForestEditIsCheaper() {
        LogEntry replacement = STREAM.get(1_234).withMessage("x");

        long paper = PaperPipeline.ingest(STREAM, new ResourceAwareChunking())
                .withEntryReplaced(1_234, replacement).hashOperations();
        long caac = MerkleForest.build(STREAM, new ContentAnchoredChunking())
                .withEntryReplaced(1_234, replacement).hashOperations();

        assertThat(caac).isLessThan(paper / 5);
    }

    @Test
    @DisplayName("proofs from the global tree verify against the anchored root")
    void proofsVerify() {
        PaperPipeline pipeline = PaperPipeline.ingest(STREAM, new ResourceAwareChunking());

        for (int i = 0; i < STREAM.size(); i += 97) {
            assertThat(MerkleVerifier.verify(STREAM.get(i), pipeline.generateProof(i), pipeline.root()))
                    .as("entry %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("an empty stream gives the empty-tree root; edits on it are rejected")
    void emptyStream() {
        PaperPipeline empty = PaperPipeline.ingest(List.of(), new ResourceAwareChunking());

        assertThat(empty.root()).isEqualTo(Hashing.emptyTreeHash());
        assertThat(empty.batchCount()).isZero();
        assertThatThrownBy(() -> empty.withEntryReplaced(0, STREAM.get(0)))
                .isInstanceOf(EmptyForestException.class);
        assertThatThrownBy(() -> PaperPipeline.ingest(STREAM, new ResourceAwareChunking())
                .withEntryReplaced(2_000, STREAM.get(0)))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
