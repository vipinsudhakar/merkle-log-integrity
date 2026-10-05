# merkle-log-integrity

**Content-Anchored Adaptive Chunking (CAAC) for tamper-evident log integrity.**

An Advanced DSA course project (B.Tech AI & Data Science). Log entries are SHA-256 hashed
into Merkle trees, so any change to a single entry is detected and localised in O(log n)
using inclusion proofs, instead of re-hashing the whole dataset in O(n).

Our contribution is a chunking strategy, CAAC, which extends the adaptive chunking of
Yağız, Horasan and Yurttakal (2026) so that an edit or insertion only touches the chunks
around it, instead of forcing a full rebuild.

## The idea

Logs are split into chunks. Each chunk gets its own Merkle tree, and the chunk roots are
sealed under one **super-root**, the single hash that gets published as the trusted anchor.

```
                     super-root            <- published / anchored
                 /       |       \
           root(C0)   root(C1)   root(C2)  <- one Merkle tree per chunk
            /   \       /   \       /   \
          ...   ...   ...   ...   ...   ...  <- log entries
```

How the log is cut into chunks decides how long proofs are, and how much has to be
re-hashed when something changes. That is what this project compares.

## The base paper, and what we change

In the base paper, chunk size adapts to memory pressure (their Eq. 1–2): smaller batches when
memory is tight, larger when it is free. But the chunks do not shape the tree: the paper keeps
**one global tree and rebuilds it after every batch**, which is O(n) per batch (their stated
limitation L4). The cut points also depend on how much memory happens to be free, so the same
log can split differently on different runs.

**CAAC keeps the paper's memory-aware size range and adds two things:**

1. **Content-anchored cut points.** Inside the size range, a chunk ends after an entry whose
   leaf hash matches a bit pattern. Cut points depend on the content itself, so the same log
   always splits the same way, and inserting or editing one entry only moves the boundaries
   next to it.
2. **One tree per chunk.** An edit re-hashes one chunk plus the small super-tree, O(c + k),
   instead of the whole log.

> The paper decides **how big** a chunk may be; CAAC also decides **exactly where** to cut,
> so changes stay local.

## Strategies compared

| Strategy | Cuts when | Status |
|---|---|---|
| Fixed-size | every N entries | ✅ done |
| Time-window | an entry falls outside the current time window | ✅ done |
| Entropy | the rolling Shannon entropy of recent payload bytes crosses a threshold | ✅ done |
| Resource-aware (base paper) | batch size from memory pressure (Eq. 1–2), one global tree rebuilt per batch | ✅ done |
| **CAAC (ours)** | content-anchored cut inside the memory-aware size range, one tree per chunk | ✅ done |

The base paper's method is implemented as faithfully as we can, not weakened, so the
comparison is fair.

## Results

Full results: [`docs/benchmarks/`](docs/benchmarks/) (`results.json`, `results.csv`, and the
method). Synthetic IoT logs at the paper's sizes (1k–100k entries); hash-operation counts are
exact, timings are the mean of 5 runs. Tamper detection is 100 % precision and recall for every
strategy at 1–50 % corruption, as in the paper's Table 6.

**100,000 entries:**

| Subject | Chunks | Chunk roots changed by one insertion | Hashes to rebuild after an **insertion** | Hashes to rebuild after an **edit** | Proof length (hashes) |
|---|---|---|---|---|---|
| Fixed-size | 1,563 | 782 | 100,839 | 1,624 | 16.9 |
| Time-window | 7,886 | 1.0 | 7,931 | 7,909 | 17.3 |
| Entropy | 5,129 | 1.7 | 5,196 | 5,149 | 17.4 |
| Resource-aware (paper's sizing, in our forest) | 1,429 | 715 | 100,783 | 1,497 | 17.4 |
| **CAAC (ours)** | **1,397** | **1.2** | **1,603** | **1,491** | **17.4** |
| Base paper's pipeline (one global tree) | 1 | — | 100,001 | 100,000 | 16.9 |

What this shows:

- **Insertion is where CAAC wins.** Count-based cutting (fixed-size, and the paper's sizing)
  shifts every boundary after an inserted entry, so about half of all chunks must be rebuilt
  (~100k hashes). The paper's single global tree must be rebuilt in full (100k). CAAC rebuilds
  about one chunk plus the super-tree: **1,603 hashes, ~63× less**.
- **Time-window and entropy are local too,** but they make 4–6× more, much smaller chunks, so
  every rebuild pays for a bigger super-tree: 3–5× CAAC's cost. They also ignore the device's
  memory budget.
- **Edits cost the same for every forest at similar chunk sizes** (~1,500 hashes for CAAC,
  fixed-size and resource-aware), and ~67× less than the paper's full rebuild.
- **Proofs stay O(log n)** for everyone: ~17 hashes at 100k. The global tree is 14 hashes at
  10k, matching the paper's 14.
- **CAAC still adapts to memory pressure** like the paper's method: under the paper's stress
  profile its chunks shrink from ~70 to ~8 entries and recover afterwards.

So CAAC is the only strategy that keeps insertions local **and** keeps chunk sizes at the
paper's memory-aware target.

Caveats: our engine is Java and the paper's is Python, so absolute timings are not comparable.
The paper reports proof sizes with hex-encoded hashes, so we compare hash counts, not bytes. The
paper's pipeline rebuilds its tree after every 70-entry batch (Algorithm 1 as published), which
makes its ingest slow at large n; bigger batches would help its ingest, but not its edit or
insertion cost.
## Visualiser (coming next)

A React app backed by a Spring Boot API that runs the real engine:

- **Chunking strip**: the log stream with each strategy's cut points; adjust parameters and
  memory pressure
- **Tree and proof**: draw a chunk's Merkle tree and step through an entry's proof up to the
  super-root
- **Tamper and insert**: change or insert an entry and see, strategy by strategy, which
  hashes and chunks change
- **Results dashboard**: the benchmark graphs, plus the history of anchored roots

## Stack

- **Backend:** Java 21, Spring Boot 3, Maven, JUnit 5 + AssertJ
- **Frontend:** React (Vite, TypeScript), Tailwind CSS, Recharts
- **Database:** PostgreSQL 18 (Flyway), for datasets and the trusted root anchor history
- **Deployment:** Render

## Running it

Requires Java 21 and Maven.

```bash
cd backend
mvn test                                                    # 346 unit tests
mvn -q compile
java -cp target/classes com.merklelog.demo.DemoRunner       # console demo, 512 entries
java -cp target/classes com.merklelog.demo.DemoRunner 2048  # any size
java -cp target/classes com.merklelog.benchmark.BenchmarkRunner   # benchmark, ~6 min
```

The console demo walks through the hash primitives, building the forest, an inclusion proof
with its full verification trace, tamper detection and localisation, rebuild cost, and a
comparison of the chunking strategies. The synthetic log stream uses a fixed seed, so every
run prints identical hashes; a captured run is in [`docs/demo-output.txt`](docs/demo-output.txt).

The API (`mvn spring-boot:run`) and the frontend (`cd frontend && npm run dev`) are coming
next.

## Status

- ✅ **Core engine**: RFC 6962 hashing, Merkle trees, inclusion proofs, verifier, per-chunk
  forest, console demo
- ✅ **Strategies**: all five, including CAAC and the base paper's method, plus the paper's
  global-tree pipeline; localised rebuild with counted hash operations. 334 tests, all passing.
- ✅ **Benchmarks**: five strategies + the paper's pipeline at the paper's sizes, 5 runs each
- ⏳ **API and database**: Spring Boot 3.5 + PostgreSQL 18 schema (Flyway) in place; REST endpoints next
- ⏳ **Visualiser**: the four views above
- ⏳ **Deployment** on Render

Design details: [`docs/architecture.md`](docs/architecture.md). Full project spec:
[`instructions.md`](instructions.md).

## Team

- Vipin Sudhakar — CB.AI.U4AID25166
- Rithvik Arulprakash — CB.AI.U4AID25148
- Harshith KV — CB.AI.U4AID25119
- Venugopalan G — CB.AI.U4AID25115

## Reference

Yağız, M. A., Horasan, F., Yurttakal, A. H. *Lightweight Tamper-Evident Log Integrity
Verification for IoT Edge Environments: A Merkle-Tree Pipeline with Adaptive Chunking.*
arXiv:2605.00065, 2026.
