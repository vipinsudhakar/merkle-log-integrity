package com.merklelog.benchmark;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;
import com.merklelog.chunking.ContentAnchoredChunking;
import com.merklelog.chunking.FixedSizeChunking;
import com.merklelog.chunking.MemoryPressureProfile;
import com.merklelog.chunking.ResourceAwareChunking;
import com.merklelog.chunking.ResourceAwareSizer;
import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.demo.SyntheticLogGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Runs the comparison the project reports: five chunking strategies in our per-chunk forest,
 * plus the base paper's global-tree pipeline, at the paper's dataset sizes.
 *
 * <p>Writes {@code results.json} (read by the API and the dashboard) and {@code results.csv}.
 *
 * <pre>
 *   cd backend
 *   mvn -q compile
 *   java -cp target/classes com.merklelog.benchmark.BenchmarkRunner            # full run
 *   java -cp target/classes com.merklelog.benchmark.BenchmarkRunner --quick    # 1k and 5k, 2 runs
 * </pre>
 *
 * <h2>Methodology</h2>
 *
 * <ul>
 *   <li><b>Data:</b> {@link SyntheticLogGenerator} with its fixed seed, so every run sees the
 *       same entries. Sizes 1k, 5k, 10k, 50k, 100k, as in the paper's Table 2.</li>
 *   <li><b>Counted metrics</b> (hash operations, chunk counts, proof lengths, chunk roots changed)
 *       are deterministic for fixed data, so they are measured once.</li>
 *   <li><b>Timed metrics</b> (ingest, proof generation, verification, edit) are measured over
 *       5 runs after a warm-up pass, and reported as mean and standard deviation, as the paper
 *       does. They depend on the machine.</li>
 *   <li><b>Heap</b> is an approximation: used heap after a GC with the structure alive, minus
 *       used heap before building it.</li>
 *   <li>Every subject is measured by the same code through {@link Subject}.</li>
 * </ul>
 */
public final class BenchmarkRunner {

    static final List<Integer> FULL_SIZES = List.of(1_000, 5_000, 10_000, 50_000, 100_000);
    static final List<Integer> QUICK_SIZES = List.of(1_000, 5_000);
    static final double[] TAMPER_RATIOS = {0.01, 0.05, 0.10, 0.20, 0.50};

    /** Entries sampled for proof metrics; evenly spaced across the dataset. */
    private static final int PROOF_SAMPLES = 1_000;
    /** Positions sampled for edit and insertion metrics; evenly spaced. */
    private static final int CHANGE_SAMPLES = 20;
    /** Dataset size for the tamper-detection and pressure experiments (the paper uses 10k for Table 6). */
    static final int EXPERIMENT_SIZE = 10_000;

    private final List<Integer> sizes;
    private final int runs;
    private final int experimentSize;

    BenchmarkRunner(List<Integer> sizes, int runs) {
        this(sizes, runs, EXPERIMENT_SIZE);
    }

    /** @param experimentSize dataset size for the tamper and pressure experiments (10k in the paper) */
    BenchmarkRunner(List<Integer> sizes, int runs, int experimentSize) {
        this.sizes = sizes;
        this.runs = runs;
        this.experimentSize = experimentSize;
    }

    public static void main(String[] args) throws IOException {
        boolean quick = List.of(args).contains("--quick");
        Path outDir = Path.of(args.length > 0 && !args[args.length - 1].startsWith("--")
                ? args[args.length - 1] : "../docs/benchmarks");

        BenchmarkRunner runner = quick ? new BenchmarkRunner(QUICK_SIZES, 2) : new BenchmarkRunner(FULL_SIZES, 5);
        Map<String, Object> results = runner.run();

        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("results.json"), Json.write(results), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("results.csv"), toCsv(results), StandardCharsets.UTF_8);
        System.out.println("Wrote " + outDir.resolve("results.json").toAbsolutePath().normalize());
    }

    static List<Subject> subjects() {
        List<Subject> subjects = new ArrayList<>();
        for (ChunkingStrategy strategy : ChunkingStrategyFactory.allWithDefaults()) {
            subjects.add(Subject.forest(strategy));
        }
        subjects.add(Subject.paperPipeline());
        return subjects;
    }

    Map<String, Object> run() {
        warmUp();

        List<Object> measurements = new ArrayList<>();
        for (int size : sizes) {
            List<LogEntry> entries = SyntheticLogGenerator.generate(size);
            for (Subject subject : subjects()) {
                log("%-15s n=%,d", subject.name(), size);
                measurements.add(measure(subject, entries));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("java", System.getProperty("java.version"));
        out.put("seed", SyntheticLogGenerator.DEFAULT_SEED);
        out.put("runs", runs);
        out.put("sizes", sizes);
        out.put("notes", List.of(
                "Counted metrics (hash operations, chunks, proof steps, chunk roots changed) are deterministic and measured once.",
                "Timed metrics are mean and standard deviation over the runs, after a warm-up; they depend on the machine.",
                "Our engine is Java; the base paper's is Python. Compare timings within this file, not with the paper's milliseconds.",
                "Proof bytes are 32 raw bytes per hash; the paper's 1006 bytes are 14 hex-encoded hashes. Compare hash counts.",
                "paper-pipeline is the base paper's Algorithm 1: one global tree rebuilt after every batch, batches from Eq. 1-2 (70 entries at baseline pressure). Its ingest cost grows with n squared over the batch size; larger batches would reduce it.",
                "heapMb is approximate: used heap after GC with the structure alive, minus before."));
        out.put("results", measurements);
        out.put("tamper", tamperDetection());
        out.put("pressure", pressureResponse());
        return out;
    }

    // ------------------------------------------------------------------ main measurements

    private Map<String, Object> measure(Subject subject, List<LogEntry> entries) {
        int n = entries.size();
        List<Integer> proofIndices = evenlySpaced(n, Math.min(PROOF_SAMPLES, n), 0);
        List<Integer> changeIndices = evenlySpaced(n, Math.min(CHANGE_SAMPLES, n - 1), 1);

        // --- counted, deterministic ---
        long before = Hashing.operationCount();
        Subject.Built built = subject.build(entries);
        long ingestHashOps = Hashing.operationCount() - before;

        List<Integer> chunkSizes = built.chunkSizes();
        List<Double> proofSteps = new ArrayList<>();
        for (int i : proofIndices) {
            proofSteps.add((double) built.proofSteps(i));
        }

        List<Double> editOps = new ArrayList<>();
        for (int i : changeIndices) {
            editOps.add((double) built.editCost(i, edited(entries.get(i))));
        }

        // A single global tree has no chunk roots to keep (reported as -1), so the question does not
        // apply, and its insertion cost depends only on n, so no second build is needed.
        List<Double> rootsChanged = built.chunkRootsChangedSince(built) < 0 ? null : new ArrayList<>();
        List<Double> insertOps = new ArrayList<>();
        for (int i : changeIndices) {
            if (rootsChanged == null) {
                insertOps.add((double) built.insertionCost(built));
            } else {
                Subject.Built afterInsert = subject.build(insertedAt(entries, i));
                rootsChanged.add((double) afterInsert.chunkRootsChangedSince(built));
                insertOps.add((double) afterInsert.insertionCost(built));
            }
        }

        // --- timed, over the runs ---
        List<Double> ingestMs = new ArrayList<>();
        List<Double> proofMicros = new ArrayList<>();
        List<Double> verifyMicros = new ArrayList<>();
        List<Double> editMicros = new ArrayList<>();
        List<Double> heapMb = new ArrayList<>();

        for (int run = 0; run < runs; run++) {
            long heapBefore = usedHeapAfterGc();
            long t0 = System.nanoTime();
            Subject.Built timed = subject.build(entries);
            ingestMs.add((System.nanoTime() - t0) / 1e6);
            heapMb.add((usedHeapAfterGc() - heapBefore) / (1024.0 * 1024.0));

            List<Object> proofs = new ArrayList<>(proofIndices.size());
            t0 = System.nanoTime();
            for (int i : proofIndices) {
                proofs.add(timed.generateProof(i));
            }
            proofMicros.add((System.nanoTime() - t0) / 1e3 / proofIndices.size());

            t0 = System.nanoTime();
            for (int k = 0; k < proofIndices.size(); k++) {
                if (!timed.verify(entries.get(proofIndices.get(k)), proofs.get(k))) {
                    throw new IllegalStateException(subject.name() + ": honest proof failed to verify");
                }
            }
            verifyMicros.add((System.nanoTime() - t0) / 1e3 / proofIndices.size());

            t0 = System.nanoTime();
            for (int i : changeIndices) {
                timed.editCost(i, edited(entries.get(i)));
            }
            editMicros.add((System.nanoTime() - t0) / 1e3 / changeIndices.size());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", subject.name());
        m.put("size", n);
        m.put("chunkCount", chunkSizes.size());
        m.put("chunkSize", Stats.of(toDoubles(chunkSizes)).withMinMax());
        m.put("proofSteps", Stats.of(proofSteps).withMinMax());
        m.put("proofBytesMean", Stats.of(proofSteps).mean() * Hashing.HASH_LENGTH_BYTES);
        m.put("ingestHashOps", ingestHashOps);
        m.put("ingestMs", Stats.of(ingestMs).map());
        m.put("ingestLogsPerSec", Stats.of(ingestMs.stream().map(ms -> n / (ms / 1000.0)).toList()).map());
        m.put("proofGenMicros", Stats.of(proofMicros).map());
        m.put("verifyMicros", Stats.of(verifyMicros).map());
        m.put("editHashOps", Stats.of(editOps).withMinMax());
        m.put("editMicros", Stats.of(editMicros).map());
        m.put("insertChunkRootsChanged", rootsChanged == null ? null : Stats.of(rootsChanged).withMinMax());
        m.put("insertHashOps", Stats.of(insertOps).withMinMax());
        m.put("heapMb", Stats.of(heapMb).map());
        return m;
    }

    // ------------------------------------------------------------------ tamper detection (paper Table 6)

    /**
     * Corrupts a fraction of entries and checks every entry against the trusted root with a proof
     * from the original structure. Detected = failed verification. Precision, recall and F1 are
     * computed from sets, as in the paper's corrected method (its defect D1).
     */
    private List<Object> tamperDetection() {
        List<LogEntry> entries = SyntheticLogGenerator.generate(experimentSize);
        List<Object> rows = new ArrayList<>();

        for (Subject subject : subjects()) {
            Subject.Built built = subject.build(entries);
            for (double ratio : TAMPER_RATIOS) {
                Random random = new Random(SyntheticLogGenerator.DEFAULT_SEED + Math.round(ratio * 1000));
                Set<Integer> tampered = new HashSet<>();
                while (tampered.size() < Math.round(ratio * entries.size())) {
                    tampered.add(random.nextInt(entries.size()));
                }

                long t0 = System.nanoTime();
                Set<Integer> detected = new HashSet<>();
                for (int i = 0; i < entries.size(); i++) {
                    LogEntry stored = tampered.contains(i) ? edited(entries.get(i)) : entries.get(i);
                    if (!built.verify(stored, built.generateProof(i))) {
                        detected.add(i);
                    }
                }
                double ms = (System.nanoTime() - t0) / 1e6;

                Set<Integer> truePositives = new HashSet<>(detected);
                truePositives.retainAll(tampered);
                int tp = truePositives.size();
                int fp = detected.size() - tp;
                int fn = tampered.size() - tp;
                double precision = detected.isEmpty() ? 1.0 : tp / (double) (tp + fp);
                double recall = tampered.isEmpty() ? 1.0 : tp / (double) (tp + fn);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subject", subject.name());
                row.put("ratio", ratio);
                row.put("tampered", tampered.size());
                row.put("detected", detected.size());
                row.put("precision", precision);
                row.put("recall", recall);
                row.put("f1", precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall));
                row.put("ms", ms);
                rows.add(row);
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ pressure response (paper Fig. 4)

    /**
     * Chunk sizes per 2000-entry window under the paper's stress profile (baseline, three stress
     * windows, recovery). Fixed-size is included as a reference that does not adapt.
     */
    private List<Object> pressureResponse() {
        MemoryPressureProfile profile = MemoryPressureProfile.paperStressTest();
        List<LogEntry> entries = SyntheticLogGenerator.generate(experimentSize);
        List<ChunkingStrategy> strategies = List.of(
                new FixedSizeChunking(),
                new ResourceAwareChunking(new ResourceAwareSizer(), profile),
                new ContentAnchoredChunking(new ResourceAwareSizer(), profile));

        List<Object> rows = new ArrayList<>();
        for (ChunkingStrategy strategy : strategies) {
            List<Chunk> chunks = strategy.chunk(entries);
            int windows = Math.max(1, experimentSize / profile.windowEntries());
            for (int w = 0; w < windows; w++) {
                int from = w * profile.windowEntries();
                int to = from + profile.windowEntries();
                List<Double> sizesInWindow = new ArrayList<>();
                int position = 0;
                for (Chunk chunk : chunks) {
                    if (position >= from && position < to) {
                        sizesInWindow.add((double) chunk.size());
                    }
                    position += chunk.size();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("subject", strategy.name());
                row.put("window", w);
                row.put("pressure", profile.pressureAt(from));
                row.put("chunks", sizesInWindow.size());
                row.put("avgChunkSize", Stats.of(sizesInWindow).mean());
                rows.add(row);
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ helpers

    /** One untimed pass at 10k so the JIT has compiled the hot paths before anything is timed. */
    private void warmUp() {
        log("warm-up");
        List<LogEntry> entries = SyntheticLogGenerator.generate(experimentSize);
        for (Subject subject : subjects()) {
            Subject.Built built = subject.build(entries);
            for (int i = 0; i < entries.size(); i += 10) {
                built.verify(entries.get(i), built.generateProof(i));
            }
            built.editCost(entries.size() / 2, edited(entries.get(entries.size() / 2)));
        }
    }

    /** {@code count} indices spread evenly over {@code [first, n - 1]}. */
    static List<Integer> evenlySpaced(int n, int count, int first) {
        List<Integer> indices = new ArrayList<>(count);
        if (count <= 0) {
            return indices;
        }
        if (count == 1) {
            indices.add(first);
            return indices;
        }
        for (int k = 0; k < count; k++) {
            indices.add(first + (int) ((long) k * (n - 1 - first) / (count - 1)));
        }
        return indices;
    }

    private static LogEntry edited(LogEntry entry) {
        return entry.withMessage(entry.message() + " [tampered]");
    }

    /** The log with one new entry inserted before {@code position}, timestamped just after its predecessor. */
    static List<LogEntry> insertedAt(List<LogEntry> entries, int position) {
        LogEntry previous = entries.get(position - 1);
        List<LogEntry> copy = new ArrayList<>(entries);
        copy.add(position, new LogEntry(9_000_000L + position, previous.timestamp().plusMillis(1),
                "WARN", "edge-gateway-01", "injected entry"));
        return copy;
    }

    private static long usedHeapAfterGc() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static List<Double> toDoubles(List<Integer> values) {
        return values.stream().map(Integer::doubleValue).toList();
    }

    private static void log(String format, Object... args) {
        System.out.printf(Locale.ROOT, "[bench] " + format + "%n", args);
    }

    // ------------------------------------------------------------------ CSV

    @SuppressWarnings("unchecked")
    private static String toCsv(Map<String, Object> results) {
        StringBuilder csv = new StringBuilder(
                "subject,size,chunkCount,chunkSizeMean,proofStepsMean,proofBytesMean,ingestHashOps,"
                        + "ingestMsMean,ingestLogsPerSecMean,proofGenMicrosMean,verifyMicrosMean,"
                        + "editHashOpsMean,editMicrosMean,insertChunkRootsChangedMean,insertHashOpsMean,heapMbMean\n");
        for (Object o : (List<Object>) results.get("results")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> roots = (Map<String, Object>) m.get("insertChunkRootsChanged");
            csv.append(String.join(",",
                    String.valueOf(m.get("subject")),
                    String.valueOf(m.get("size")),
                    String.valueOf(m.get("chunkCount")),
                    num(((Map<String, Object>) m.get("chunkSize")).get("mean")),
                    num(((Map<String, Object>) m.get("proofSteps")).get("mean")),
                    num(m.get("proofBytesMean")),
                    String.valueOf(m.get("ingestHashOps")),
                    num(((Map<String, Object>) m.get("ingestMs")).get("mean")),
                    num(((Map<String, Object>) m.get("ingestLogsPerSec")).get("mean")),
                    num(((Map<String, Object>) m.get("proofGenMicros")).get("mean")),
                    num(((Map<String, Object>) m.get("verifyMicros")).get("mean")),
                    num(((Map<String, Object>) m.get("editHashOps")).get("mean")),
                    num(((Map<String, Object>) m.get("editMicros")).get("mean")),
                    roots == null ? "" : num(roots.get("mean")),
                    num(((Map<String, Object>) m.get("insertHashOps")).get("mean")),
                    num(((Map<String, Object>) m.get("heapMb")).get("mean"))));
            csv.append('\n');
        }
        return csv.toString();
    }

    private static String num(Object value) {
        return value instanceof Double d ? String.format(Locale.ROOT, "%.4f", d) : String.valueOf(value);
    }
}
