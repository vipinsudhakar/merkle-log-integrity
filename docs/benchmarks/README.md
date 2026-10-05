# Benchmarks

`results.json` and `results.csv` are produced by
[`BenchmarkRunner`](../../backend/src/main/java/com/merklelog/benchmark/BenchmarkRunner.java):

```bash
cd backend
mvn -q compile
java -cp target/classes com.merklelog.benchmark.BenchmarkRunner           # full run, ~6 minutes
java -cp target/classes com.merklelog.benchmark.BenchmarkRunner --quick   # 1k and 5k entries, 2 runs
```

Don't run other heavy work while it runs; the timings are wall-clock.

## What is compared

Six subjects, all measured by the same code (`benchmark/Subject.java`):

| Subject | What it is |
|---|---|
| `fixed-size` | 64 entries per chunk, one tree per chunk under a super-root |
| `time-window` | 60-second windows, one tree per chunk |
| `entropy` | rolling Shannon entropy ≥ 4.5 bits/byte (min 16, max 256 entries), one tree per chunk |
| `resource-aware` | the base paper's batch sizing (Eq. 1–2), placed in our per-chunk forest |
| `caac` | **our strategy**: the paper's size range, cut on content anchors, one tree per chunk |
| `paper-pipeline` | the base paper's full pipeline (Algorithm 1): its batch sizing, **one global tree** rebuilt after every batch |

## Method

- **Data:** `SyntheticLogGenerator` with its fixed seed, at the paper's sizes: 1k, 5k, 10k,
  50k and 100k entries.
- **Counted metrics are exact.** Hash operations, chunk counts, proof lengths and chunk roots
  changed are deterministic for fixed data, so they are measured once. Hash operations are
  counted by `Hashing.operationCount()`, i.e. the SHA-256 computations that actually ran.
- **Timed metrics** (ingest, proof generation, verification, edit) are the mean and sample
  standard deviation over 5 runs after a warm-up pass, as in the paper.
- **Sampling:** proof metrics over 1,000 evenly spaced entries; edit and insertion metrics over
  20 evenly spaced positions.
- **Edit cost:** replace one entry and restore a valid root, counted.
- **Insertion cost:** insert one entry, then rebuild only the chunks whose roots changed, plus
  the super-tree. For the global tree: one new leaf plus all `n` internal nodes.
- **Tamper detection** (paper Table 6): 10k entries, 1–50 % corrupted; every entry is verified
  against the trusted root with a proof from the original structure; precision, recall and F1
  from sets (the paper's corrected method, its defect D1).
- **Pressure response** (paper Fig. 4): chunk sizes per 2,000-entry window under the paper's
  stress profile (0.25 → 0.85 × 3 → 0.25).

## Caveats

- Our engine is Java; the paper's is Python. Compare timings within these files, not with the
  paper's milliseconds.
- Proof bytes here are 32 raw bytes per hash. The paper's 1,006 bytes are 14 **hex-encoded**
  hashes, so compare hash counts (we get 14 steps at 10k for the global tree, matching the paper).
- `paper-pipeline` follows Algorithm 1 as published: it rebuilds the global tree after every
  batch, so ingest cost grows with n² / batch size. Its batches are 70 entries at baseline
  pressure (Eq. 1–2 with our defaults); larger batches would lower its ingest cost, but not its
  edit or insertion cost, which is always about n.
- `heapMb` is approximate: used heap after GC with the structure alive, minus before.

## Where these numbers appear

- The visualiser's **Results** section (`#results`) draws `results.json` as Figs. 5–9 and
  Tables 1–2; the API serves it at `GET /api/benchmarks` (copied onto the classpath at build).
- The headline table in the top-level [`README.md`](../../README.md) is taken from the
  100,000-entry rows.
- If you re-run the benchmark, commit the new `results.json` and rebuild the backend so the API
  serves it; counted metrics will not change, timings will.
