# Architecture

How the core works and why it is built this way. Written to be read aloud in a viva —
every design choice below has a reason attached.

Status: engine, benchmark, API, persistence and visualiser complete; deployment pending. The objective changed in October 2026 to
**Content-Anchored Adaptive Chunking (CAAC)**; see §12 and `instructions.md`. The API,
persistence and UI are built on top of this core without changing its guarantees.

---

## 1. The problem

Verifying that a log file has not been altered normally costs **O(n)**: re-hash every entry
and compare. For a million entries that is a million hash operations to answer "is entry
#500,000 intact?".

A Merkle tree replaces that with **O(log n)**. Entries are hashed into leaves; each internal
node hashes its two children; the single root hash commits to every entry. To check one
entry you need only the sibling hashes along its path to the root — about 20 hashes for a
million entries.

Crucially, the tree does not just *detect* tampering, it **localises** it: the entry whose
proof fails is the entry that changed.

---

## 2. Layers

```
LogEntry ──canonicalBytes()──> leafHash ──┐
                                          │
ChunkingStrategy ──> Chunk ──> MerkleTree ─┴─> chunk root ──┐
                                                            │
                                          MerkleTree over chunk roots ──> super-root
```

| Package | Contains | Depends on |
|---|---|---|
| `core` | `Hashing`, `LogEntry`, `MerkleTree`, `MerkleNode`, `MerkleProof`, `ProofStep`, `MerkleVerifier`, `MerkleForest`, `ForestProof`, `PaperPipeline` | `chunking` (for `Chunk`, `ChunkingStrategy`) |
| `chunking` | `ChunkingStrategy` + five implementations (fixed-size, time-window, entropy, resource-aware, caac), `ResourceAwareSizer`, `MemoryPressureProfile`, factory | `core` (for `LogEntry`) |
| `benchmark` | `BenchmarkRunner`, `Subject` (the 5 strategies + the paper's pipeline behind one interface), `Stats`, `Json` | `core`, `chunking`, `demo` |

Neither package imports Spring. They are plain Java, unit-testable with no application
context — which is what makes the DSA contribution legible as a standalone artifact.

---

## 3. Hashing — RFC 6962 domain separation

```
leafHash(d)   = SHA256(0x00 || d)
nodeHash(l,r) = SHA256(0x01 || l || r)
```

**Why the prefixes.** Without them, an attacker could take an internal node's two child
hashes, concatenate them, and present those 64 bytes as if they were a *leaf's* data. Both
would hash identically, so the tree could be reinterpreted with a different shape but the
same root — a second-preimage attack. Tagging the two cases makes their inputs disjoint by
construction.

**Real SHA-256 only**, via `java.security.MessageDigest`. A fresh digest instance per call,
because `MessageDigest` is stateful and not thread-safe.

**Constant-time comparison.** `Hashing.equal` delegates to `MessageDigest.isEqual`, which
does not short-circuit on the first differing byte — otherwise how long a comparison takes
would leak how many leading bytes were correct.

---

## 4. Canonical serialisation

Each field is written as a **4-byte big-endian length followed by its UTF-8 bytes**.

The naive alternative — join fields with a separator — is ambiguous when a field contains
that separator:

```
source="a",   message="b|c"   ->  "a|b|c"
source="a|b", message="c"     ->  "a|b|c"    // same bytes, different entry
```

Two different entries would then share a leaf hash and could be substituted for one another
**with no cryptographic break at all**. Length prefixing states the boundaries rather than
guessing them.

Timestamps are epoch-milliseconds, not formatted strings, so roots do not vary with the
JVM's locale or time zone.

---

## 5. Tree construction

Stored as an **array of levels**, not as linked nodes:

```
level 2:            H(H(a,b), c)          <- root
level 1:      H(a,b)          c           <- c promoted, not duplicated
level 0:    a       b         c           <- leaf hashes
```

**Why levels.** Finding a sibling becomes arithmetic rather than search:

| Operation | Expression | Cost |
|---|---|---|
| sibling of node `i` | `i ^ 1` | O(1) |
| parent of node `i` | `i >> 1` | O(1) |
| read a hash | array index | O(1) |

This is the whole O(log n) claim. A pointer-linked tree would need a search or a
parent-pointer walk; an implementation that found siblings by scanning the leaf list would
return *correct* proofs while quietly costing O(n) — exactly the cost the project exists to
avoid.

`MerkleNode` provides a separate pointer-linked view for the Phase 4 visualisation. It is
O(n) to build and deliberately kept off the proof path.

### Odd nodes are promoted, never duplicated

When a level has an odd count, the last node is carried up **unchanged**.

The better-known alternative — hashing the last node with itself, as Bitcoin does — is
**CVE-2012-2459**. Because `H(x, x)` is reachable from two different leaf lists, the leaf
lists `[a,b,c]` and `[a,b,c,c]` produce the *same root*, so a root no longer uniquely
commits to its leaves. Promotion has no such ambiguity, and a test pins that exact forgery.

**Consequence:** a promoted node contributes no proof step at that level, so proofs for
right-edge leaves can be *shorter* than `ceil(log2 n)`. The bound is `≤`, not `=` — still
O(log n), and exactly `=` when n is a power of two.

---

## 6. Proofs

A proof is a list of `(siblingHash, side)` pairs from the leaf upward.

**The side must be recorded** because node hashing is not commutative:
`nodeHash(a,b) ≠ nodeHash(b,a)`. If the verifier guessed the order (say by sorting), a proof
with its steps rearranged would still verify, and the proof would attest *membership* but
not *position*.

`MerkleVerifier` holds **no reference to a tree**. It gets a leaf hash, a proof, and a
trusted root — nothing else. That is what makes a proof useful (an auditor can check one log
line without the dataset) and it makes the complexity claim impossible to fake by accident:
with no tree to consult, verification can only fold the proof, one hash per step.

**The single-leaf case.** The proof is empty and the leaf hash *is* the root. Verifying an
empty proof must still be a real comparison — a verifier that short-circuits to `true` on
zero steps would pass a naive test while accepting anything. Tested in both directions.

---

## 7. The forest — one tree per chunk

```
                     super-root
                 /       |       \
           root(C0)   root(C1)   root(C2)
            /   \       /   \       /   \
          ...   ...   ...   ...   ...   ...
```

**Why not one global tree.** If every entry were a leaf of a single tree, the chunking
strategy would change almost nothing measurable — proof length would be `log2(totalEntries)`
regardless, and tampering would force a rebuild proportional to the whole dataset. The
comparison this project makes would have nothing to compare.

Per-chunk trees change both:

- **Proof size** becomes `log2(chunkSize) + log2(chunkCount)`, so the strategy sets it
  directly. The minimum sits in the middle: tiny chunks give a short entry proof but a long
  chunk proof, huge chunks do the reverse.
- **Rebuild cost** after tampering is one chunk plus the small super-tree, *not* the
  dataset. Re-hashing 64 entries instead of 10,000 is a ~156× saving — and it is what makes
  this viable on an edge device, the setting the base paper targets.

An entry's chunk is located by **binary search** over chunk start offsets, O(log chunkCount),
not by walking the chunk list.

The super-tree's leaves are chunk roots fed in as pre-computed hashes. They are already
commitments, so they are not re-tagged with the leaf prefix.

---

## 8. Chunking strategies

| Strategy | Cuts when | Strength | Trade-off |
|---|---|---|---|
| **fixed-size** | every N entries | Uniform proof length; bounded, predictable rebuild; boundaries known without reading content | Blind to both content and the clock |
| **time-window** | entry falls outside `[start, start+window)` | Boundaries mean something to an auditor ("prove the 09:00–09:01 window") | Chunk size tracks traffic, so proof length is unpredictable |
| **entropy** | rolling Shannon entropy crosses a threshold | Boundaries follow shifts in content | Weak signal on short payloads (below) |

Every strategy must satisfy one contract, checked against all of them by
`ChunkingInvariantTest`: chunks concatenate back to **exactly** the input, no chunk is empty,
boundaries are deterministic, and empty input gives an empty list.

The first rule is the important one. A dropped entry would be covered by no tree at all, so
it could be altered freely without changing any root — while the system still *appeared* to
work.

### Entropy chunking — a stated limitation

Shannon entropy estimated from a small sample is biased **low** and noisy. A 32-byte window
holds at most 32 of the 256 possible byte values, so measured entropy is capped near
`log2(windowBytes)` — **a 32-byte window can never report above 5 bits/byte even for
perfectly random data**. For short log messages the boundary signal degrades toward
arbitrary.

This is asserted in tests, not just described. Two guards contain it:

- **`minChunkEntries`** stops entropy noise shattering the stream into tiny chunks.
- **`maxChunkEntries`** stops a long low-entropy run (a device repeating one heartbeat for
  an hour) collapsing into one enormous chunk — which would give a deep tree, long proofs
  and an expensive rebuild, distorting every number the benchmark reports.

Larger windows reduce the bias at the cost of responsiveness. That trade-off is itself worth
sweeping in Phase 7.

---

## 9. Complexity summary

`n` = total entries, `c` = chunk size, `k` = chunk count.

| Operation | Cost | Where |
|---|---|---|
| Build one chunk tree | O(c) hashes | `MerkleTree.fromEntries` |
| Build the whole forest | O(n) hashes | `MerkleForest.build` |
| **Generate an inclusion proof** | **O(log c + log k)** | `MerkleForest.generateProof` |
| **Verify an inclusion proof** | **O(log c + log k)** | `MerkleForest.verify` |
| Proof size | `(log c + log k) × 32` bytes | `ForestProof.sizeInBytes` |
| Locate an entry's chunk | O(log k) binary search | `MerkleForest.chunkIndexOf` |
| **Rebuild after tampering** | **O(c + k)**, not O(n) | `MerkleForest.withEntryReplaced` |
| Detect that *something* changed | O(1) root comparison | `superRoot()` |
| Localise *which* entry changed | O(n log c) worst case, one proof per entry | Phase 5 UI |
| Build the visualisation node tree | O(n) — **never on the proof path** | `MerkleTree.toNodeTree` |

---

## 10. Degenerate cases

Defined up front so behaviour is deliberate rather than accidental. Changing any of these
changes tested behaviour.

| Case | Behaviour | Why |
|---|---|---|
| 0 entries | Super-root is `SHA256("")` (RFC 6962 empty-tree hash), never null. Proof requests throw `EmptyForestException`. | "No logs yet" is an ordinary state, not an error. But there is no honest proof for an entry that does not exist, and returning `false` would let a caller confuse "nothing to prove" with "verification failed". |
| 1 chunk | Super-root **is** that chunk's root, promoted unchanged. | Falls out of existing rules — a single-leaf tree's root is its leaf — rather than being special-cased. |
| 1 entry | Root is the entry's leaf hash; both proof stages are empty. | An empty proof is still checked in both directions: true against the right root, **false** against a wrong one. |

---

## 11. Test coverage

**346 tests, all passing.** `cd backend && mvn test`

| Suite | Covers |
|---|---|
| `HashingTest` | NIST and RFC 6962 published vectors; prefix construction recomputed independently with a raw `MessageDigest`; domain separation; hex codec; **hash-operation counting** |
| `LogEntryTest` | Canonical serialisation; the separator-shifting collision; time-zone independence |
| `MerkleTreeTest` | Shape at n = 1…1000; level sizes; odd-node promotion pinned hash-for-hash; the CVE-2012-2459 forgery |
| `MerkleProofTest` | Every leaf of trees up to 257 verifies; length bounds; the empty-proof trap; verification trace |
| `TamperDetectionTest` | Every single-entry tamper detected; deletion and insertion; localisation; five forged-proof shapes; the second-preimage attack |
| `Chunking*Test` | Per-strategy behaviour plus one shared invariant suite run against all five strategies |
| `ResourceAwareSizerTest`, `MemoryPressureProfileTest` | The paper's Eq. 1–2 pinned to hand-computed values (incl. strict band boundaries); the simulated pressure profile |
| `ResourceAwareChunkingTest` | The paper's batching, its response to the stress profile, and that an insertion shifts every later boundary |
| `ContentAnchoredChunkingTest` | CAAC's size range, cuts on anchors, insertion/edit locality (1–3 chunk roots change), comparison with count-based strategies, adaptivity under stress |
| `PaperPipelineTest` | The paper's Lemma 4 (root independent of batching), Algorithm 1 ingest cost, an edit costs a full rebuild (n hashes) |
| `MerkleForestTest` | Two-stage proofs under every strategy; **counted** localised rebuild cost; all three degenerate cases |
| `MerklePropertyTest` | Randomised sizes (fixed seed); **every single-bit flip** of every payload detected, 100% |
| `BenchmarkRunnerTest` | The whole benchmark end to end on 2k entries: 2n − 1 ingest hashes per forest, the paper pipeline's n / n + 1 rebuild costs, CAAC insertion locality, perfect tamper detection |

---

## 12. Localised rebuild, CAAC and the base-paper baseline

### Localised rebuild (fixed October 2026)

`MerkleForest.withEntryReplaced` used to pass the full chunk list to `fromChunks`, which
re-hashed every chunk, so the real work was O(n) while `RebuildResult` reported only the size of
the edited chunk. It now reuses every untouched chunk tree and the edited chunk's other leaf
hashes. The cost is `1 + (c − 1) + (k − 1)` hashes: one new leaf, that chunk's internal nodes,
and the super-tree. `Hashing` counts every leaf and node hash, and the tests assert that
counted figure, not a reported one.

### The base paper's method (`resource-aware` + `PaperPipeline`)

Yağız et al. 2026 (arXiv:2605.00065), implemented faithfully as the baseline:

- `ResourceAwareSizer` is the paper's Eq. 1 (`C = clamp(⌊M_avail·M_target/K⌋, C_min, C_max)`)
  and Eq. 2 (adjustment factor 0.8 / 0.9 / 1.1 / 1.0 by memory pressure).
- `MemoryPressureProfile` replaces live memory readings with a deterministic per-window
  signal, as the paper itself does for its stress test (§5.3).
- `ResourceAwareChunking` cuts batches of the Eq. 1–2 size.
- `PaperPipeline` is the paper's Algorithm 1: hash each entry once, rebuild the **one global
  tree** after every batch. An edit costs `n` hashes (limitation L4 in the paper).

### CAAC (`caac`, our contribution)

`ContentAnchoredChunking` uses the same `ResourceAwareSizer` to get the paper's target size
`T`, then allows chunks of `T/4 … 2T` entries. Within that range a chunk ends after an entry
whose leaf hash has its low `b = round(log2(T − T/4))` bits all zero; if none appears, it is
cut at `2T`. Whether an entry is an anchor depends only on that entry, so an insertion or edit
moves only nearby boundaries, and with one tree per chunk the rebuild stays O(c + k).

Full benchmark (docs/benchmarks/, 100,000 entries): rebuilding after one **insertion** costs CAAC
1,603 hashes (1.2 chunk roots change), against ~100,800 for fixed-size and the paper's sizing
(every later boundary shifts) and 100,001 for the paper's global tree; time-window and entropy
are also local but cost 7,931 and 5,196 because they cut 3.7–5.6× more, smaller chunks. An **edit** costs
about the same in every forest with similar chunk sizes (~1,500) and 100,000 in the paper's
pipeline. `MerkleForest.build` hashes each entry once and hands the leaf hashes to the strategy,
so CAAC does not hash twice.

---

## 13. API and persistence

Spring is used only in `api/` and `persistence/`; they call the engine as plain Java, so the
visualiser shows exactly what the tested engine computes.

- **`ForestService`** is the API's only door into the engine: it builds strategies from query
  parameters (`?strategy=caac&pressureProfile=0.25,0.85`), builds forests, and runs the tamper
  comparison (`MerkleForest.withEntryReplaced` for edits, `chunksChangedSince` and
  `rebuildCostSince` for insertions). For the paper's pipeline it uses Lemma 4 (its root equals
  one global tree's root) to compute roots in O(n) instead of replaying every batch rebuild.
- **`DatasetService`** stores generated streams (batched JDBC inserts), caches them in memory,
  and re-creates the demo datasets on an empty database (startup, or `POST /api/admin/seed`).
  Entries round-trip exactly: demo-512's super-root through the database equals the console
  demo's `a3072b86…`.
- **Trusted anchor** (`root_anchors`): `POST /datasets/{id}/anchors` records the current
  super-root; `PUT /datasets/{id}/entries/{pos}` overwrites a stored entry as an attacker
  would; `GET /datasets/{id}/anchors/verify` then reports `matches: false`.
- **Errors** are RFC 9457 problem details from `ApiExceptionHandler`: unknown dataset → 404;
  bad strategy, parameter or index → 400.
- **Tests**: `ApiTest` runs the controllers with the database mocked and the engine real
  (`@WebMvcTest`), so `mvn test` needs no PostgreSQL. The database path is verified by running
  the application against PostgreSQL 18.

---

## 14. Visualiser

`frontend/` (React 19 + TypeScript, Vite, Tailwind 4, Recharts) only displays what the API
returns; it never hashes or chunks anything. Four sections, each addressable by URL hash:

| Section | Shows | Engine call behind it |
|---|---|---|
| §1 Chunking `#chunking` | all five strategies over the same window of the log, to scale | `GET /chunks` per strategy |
| §2 Tree & proof `#proof` | the entry's path on its chunk tree and on the super-tree, with every verification step | `GET /proof/{i}`, `/chunks/{c}/tree`, `/supertree` (`MerkleVerifier.verifyWithTrace`) |
| §3 Tamper & insert `#tamper` | one change across all six subjects: changed chunks, counted rebuild cost; then anchor → database rewrite → verify | `POST /tamper`; anchors; `PUT /entries/{pos}` |
| §4 Results `#results` | the committed benchmark as figures and tables | `GET /benchmarks` |

The tree diagrams use the same index arithmetic as `MerkleTree.generateProof` (sibling `i ^ 1`,
parent `i >> 1`), so the drawing is the stored structure, not an illustration of it. The design
system ("The Ledger": typography, colour, motion rules) is documented in `frontend/DESIGN.md`.
Views are checked end to end in a real browser (Playwright driving Edge).
