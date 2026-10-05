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

## What we measure

At the paper's dataset sizes (1k, 5k, 10k, 50k and 100k entries), averaged over 5 runs:

- **Rebuild cost after an edit**, counted as hash operations
- **Chunk roots changed after inserting one entry** (edit locality)
- Proof length and size, compared with the paper's 14-hash proofs at 10k entries
- Verification time, ingestion throughput, peak memory
- Chunk-size distribution, and how chunk sizes react to a simulated memory-pressure profile
- Tamper detection precision, recall and F1 at 1–50 % corruption

Caveats we state up front: our engine is Java and the paper's is Python, so absolute timings
are not directly comparable. The paper reports proof sizes using hex-encoded hashes, so we
compare hash counts rather than bytes.

### First results (10,000 entries, one run)

| Strategy | Chunks | Chunk roots changed by one insertion (avg / worst of 20) | Hash operations to rebuild after one edit |
|---|---|---|---|
| Fixed-size | 157 | 82.7 / 157 | 220 |
| Time-window | 797 | 1.0 / 1 | 825 |
| Entropy | 513 | 1.2 / 3 | 530 |
| Resource-aware (paper's sizing, in our forest) | 143 | 75.1 / 143 | 212 |
| **CAAC** | **149** | **1.2 / 2** | **188** |
| Base paper's pipeline (one global tree) | — | — | 10,000 |

Count-based strategies (fixed-size, and the paper's sizing) shift every later boundary on an
insertion. Time-window and entropy stay local too, but produce 3–5× more, much smaller chunks,
which makes each edit cost more. CAAC is the only one that keeps changes local **and** keeps
chunk sizes at the paper's memory-aware target. Full multi-run benchmarks are next.

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
mvn test                                                    # 334 unit tests
mvn -q compile
java -cp target/classes com.merklelog.demo.DemoRunner       # console demo, 512 entries
java -cp target/classes com.merklelog.demo.DemoRunner 2048  # any size
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
- ⏳ **Benchmarks**: all five strategies at the paper's sizes, 5 runs each
- ⏳ **API and database**: Spring Boot REST API, PostgreSQL
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
