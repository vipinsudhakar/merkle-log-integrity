# instructions.md — project spec

The single source of truth for **what** we are building and **why**. `handoff.md` tracks
**where we are**. `CLAUDE.md` holds the rules for working in this repo. Read all three before
starting a session.

> These notes used to be git-ignored and were lost with a laptop in October 2026. They are
> now tracked in git. Keep them committed.

---

## 1. Objective

**Content-Anchored Adaptive Chunking (CAAC)** for tamper-evident log integrity.

We extend the adaptive chunking of our base paper with **content-defined cut points** and
**one Merkle tree per chunk**, then show, with real measurements, that this keeps the cost of
an edit local. The paper's pipeline rebuilds the whole tree after every batch; CAAC rebuilds
one chunk plus a small super-tree.

Deliverables for the end review:

1. ✅ CAAC implemented in the Java engine, with tests.
2. ✅ The paper's method implemented **faithfully** as the baseline (`resource-aware` +
   `PaperPipeline`), alongside the three classic strategies (fixed-size, time-window, entropy).
3. ✅ A benchmark comparing all five plus the paper's pipeline, at the paper's dataset sizes
   (`docs/benchmarks/`).
4. ✅ A visualiser (React + Spring API): chunking, tree & proof, tamper & insert with the
   trusted-anchor demo, results.
5. ⏳ Deployed on Render, plus an end-review PPT built from the real results (day 5).

### One-line pitch (for slides and the viva)

> *Yağız et al. decide **how big** a chunk may be from memory pressure. CAAC also decides
> **exactly where** to cut, based on content, and gives each chunk its own tree, so an edit or
> insertion only touches the chunks around it instead of forcing a full rebuild.*

---

## 2. Base paper — what it actually does

Yağız, Horasan, Yurttakal. *Lightweight Tamper-Evident Log Integrity Verification for IoT Edge
Environments: A Merkle-Tree Pipeline with Adaptive Chunking.* arXiv:2605.00065, 2026.
Code: github.com/anilyagiz/iot-tamper-evident-log-integrity (Python).

- **Pipeline:** adaptive ingestion → Merkle construction → single-entry verification against a
  trusted root anchor.
- **Adaptive chunking (§3.4)** is purely *resource-aware batch sizing*:
  - Eq. 1: `C_new = clamp(⌊M_avail · M_target / K⌋, C_min, C_max)`
  - Eq. 2: adjustment factor from memory pressure `P = 1 − M_avail/M_total`:
    `A = 0.8 if P > 0.8; 0.9 if P > 0.6; 1.1 if P < 0.3; else 1.0`
  - It never looks at log content or timestamps.
- **Chunks do not affect the tree.** Lemma 4: the final tree and root are identical whatever
  the chunking. Chunks only decide how many entries are appended before a rebuild.
- **One global tree, rebuilt after every batch** — O(n) per batch. They list this as
  limitation **L4** ("a fully incremental tree … would eliminate the O(n) rebuild step").
- **Cut points depend on runtime memory**, so they are not reproducible; for their stress test
  (§5.3) they override the pressure signal with a fixed profile (0.25 baseline, 0.85 stress
  for three 2000-entry windows, then recovery).
- **Tree:** `H(left‖right)`, odd node promoted (same as ours, but no RFC 6962 domain
  separation — ours has it).
- **Reported numbers** (Python, i7 workstation): >130k logs/s at 100k entries, ~22 ms verify,
  ~22 ms proof generation, proof **1006 B = 14 hashes serialised as hex text (64–79 B each)**,
  <5 MB peak memory, tamper P/R/F1 = 1.0 at 1–50 % corruption. When comparing, compare
  **hash counts**, not bytes — our hashes are 32 raw bytes.
- **Other stated limitations:** L1 synthetic data only, L2 single workstation, L3 no
  adversarial testing, L5 no multi-device root sync.

---

## 3. The five strategies

| Strategy | Cuts when | Tree model | Source |
|---|---|---|---|
| `fixed-size` | every N entries | per-chunk forest | done (Phase 1) |
| `time-window` | entry falls outside `[start, start+window)` | per-chunk forest | done |
| `entropy` | rolling Shannon entropy ≥ threshold (min/max guarded) | per-chunk forest | done |
| `resource-aware` | **paper's method**: count from Eq. 1–2 under a pressure profile | paper's pipeline: one global tree, rebuilt per batch | done (day 1) |
| `caac` | **ours**: content-anchored cut inside an Eq. 1–2 size range | per-chunk forest | done (day 1) |

Implemented defaults: `M_total = 1024`, `M_target = 0.5`, `K = 6`, `C_min = 8`, `C_max = 256`,
so the paper's baseline pressure 0.25 gives T = 70 entries and its stress pressure 0.85 gives
T = 9. CAAC at T = 70: min 17, max 140, anchor mask 63 (expected ≈ 81 entries per chunk).

### 3.1 Paper baseline (implement faithfully — do not weaken it)

- `ResourceAwareChunking` (in `chunking/`): batch size from Eq. 1–2. Parameters `M_total`,
  `M_target`, `K`, `C_min`, `C_max`, plus a **simulated pressure profile** (pressure per window
  of entries, default mirroring §5.3). Count-based cuts.
- `PaperPipeline` (in `core/` or `benchmark/`): appends each batch to a **single global
  `MerkleTree`** and rebuilds it once per batch (`MerkleTree.fromEntries` over all entries so
  far). Any edit costs a full rebuild: n leaf hashes + n−1 node hashes.
- Its weaknesses show up honestly in the numbers (O(n) rebuild, insertion shifts every later
  boundary). That is the point — we do **not** need to sabotage it, and an examiner comparing
  against the paper would spot it if we did.

### 3.2 CAAC algorithm

For each window of entries (default 2000, as in the paper), with pressure `P` from the profile:

1. **Target size (the paper's part):**
   `T = clamp(⌊M_avail · M_target / K⌋ · A(P), C_min, C_max)`, then `min = max(1, T/4)`,
   `max = 2T`.
2. **Content anchor (our part):** walking the entries, once the current chunk has at least
   `min` entries, cut **after** any entry whose leaf hash satisfies
   `(lowBits(leafHash) & mask) == 0`, where `mask = 2^b − 1` and `b = round(log2(T − min))`,
   so the expected chunk length ≈ T.
3. **Hard ceiling:** force a cut at `max` entries.
4. Each chunk becomes its own `MerkleTree`; chunk roots are sealed under the super-root
   (existing `MerkleForest`).

Properties to prove by tests and benchmarks:

- **Deterministic**: same content + same profile ⇒ same boundaries (unlike live-memory sizing).
- **Edit/insert locality**: inserting or editing one entry changes only the boundaries near it;
  boundaries resync afterwards, so ~1–2 chunk roots change. Fixed-size and `resource-aware`
  change every chunk after the insertion point.
- **Localised rebuild**: one edit re-hashes one chunk + the super-tree, O(c + k), vs. O(n) for
  the paper's pipeline.
- **Still adaptive**: under the stress profile, chunk sizes shrink and recover like the paper's
  Fig. 4.
- **Satisfies the chunking contract** in `ChunkingInvariantTest` (partition, no empty chunks,
  deterministic, empty input → empty list).

**How to frame the claim (from the full benchmark, 2026-10-05; `docs/benchmarks/`).** At 100k
entries, rebuilding after one **insertion** costs: CAAC 1,603 hashes; fixed-size 100,839 and
the paper's sizing in a forest 100,783 (an insertion shifts every later boundary); the paper's
global-tree pipeline 100,001; time-window 7,931 and entropy 5,196 (local, but 3.7–5.6× more and
smaller chunks, so a bigger super-tree). After one **edit**, every forest with similar chunk
sizes costs about the same (CAAC 1,491, fixed-size 1,624), against 100,000 for the paper's
pipeline. Do **not** claim CAAC makes edits cheaper than fixed-size, or that it is the only
local strategy. The claim is:

> CAAC is the only strategy that keeps insertions local **and** keeps chunk sizes at the
> paper's memory-aware target: one insertion costs ~1.6k hashes at 100k entries, about 63× less
> than count-based chunking or the paper's global tree, and 3–5× less than time-window or
> entropy chunking.

Why leaf hashes: they are already computed, uniformly distributed (SHA-256), and depend only
on the entry itself (id, timestamp, level, source, message), so an insertion elsewhere does
not change them.

Known limitation to state: if pressure changes between windows, sizes change too — boundaries
are content-anchored *within* a pressure regime. Under a constant profile they are fully
content-defined.

---

## 4. Metrics and target graphs

All numbers come from the real engine. Sizes **1k, 5k, 10k, 50k, 100k**; **5 runs**, mean ± σ
(as in the paper). Synthetic data from `SyntheticLogGenerator` (fixed seed).

| Metric | Shown as | Expected story |
|---|---|---|
| **Rebuild cost after one insertion** (hashes to rebuild only the changed chunks + super-tree) | line/bar vs n, log scale | CAAC ≈ 1.6k at 100k; fixed-size, resource-aware and paper pipeline ≈ 100k; time-window/entropy 5–8k — **headline result** |
| Rebuild cost after one edit (counted hash operations) | line/bar vs n | every forest ≈ chunk + super-tree (~1.5k at 100k); paper pipeline = n |
| **Chunk roots changed after one insertion** | bar per strategy | CAAC ≈ 1–2; fixed-size & resource-aware ≈ all later chunks |
| Proof length (hashes) and size (bytes) | bar per strategy; compare to paper's 14 hashes @ 10k | similar O(log n) for all |
| Verification time (µs) | bar/line | all O(log n); far below paper's 22 ms (Java vs Python — say so) |
| Ingestion throughput (logs/s) | line vs n (mirrors paper Fig. 3 / Table 2) | comparable |
| Peak memory | table | comparable |
| Chunk-size distribution | box/histogram | fixed uniform, time/entropy uneven, CAAC centred on T |
| Response to the pressure profile | chunk size per window (mirrors paper Fig. 4) | resource-aware & CAAC shrink under stress |
| Tamper detection P/R/F1 at 1–50 % | table (mirrors paper Table 6) | 1.0 everywhere (sanity check) |

Honesty rules for results: no invented or hand-edited numbers; state the Java-vs-Python caveat
for timings; state that byte sizes differ because of the hex encoding; keep the entropy
limitation visible.

---

## 5. Architecture (as built)

```
backend/src/main/java/com/merklelog/
  core/         Hashing (counted), LogEntry, MerkleTree, proofs, verifier, MerkleForest, PaperPipeline
  chunking/     ChunkingStrategy + fixed-size, time-window, entropy, resource-aware, caac;
                ResourceAwareSizer (Eq. 1–2), MemoryPressureProfile, factory
  benchmark/    BenchmarkRunner, Subject, Stats, Json → docs/benchmarks/results.{json,csv}
  demo/         SyntheticLogGenerator, DemoRunner (console demo)
  api/          ForestService (the only door into the engine), controllers, Dto, error handler, CORS
  persistence/  JPA entities, repositories, DatasetService (batched inserts, cache), DemoDataSeeder
frontend/src/   React 19 + TypeScript (Vite), Tailwind 4, Recharts
  ui/           design-system primitives (frontend/DESIGN.md)
  components/   ChunkingStrip, TreeProof, Tamper, Results
docs/           architecture.md, benchmarks/, images/ (README screenshots), demo-output.txt
```

- `core/`, `chunking/`, `benchmark/` stay **Spring-free** (plain JUnit-testable).
- **Spring Boot 3.5.16**, Flyway pinned to 11.20.3 (PostgreSQL 18 support).

### 5.1 REST API

All under `/api`. Strategy-dependent endpoints take `?strategy=<name>` plus optional strategy
parameters (unknown keys ignored, malformed values → 400).

| Method | Path | Purpose |
|---|---|---|
| GET | `/strategies` | names, descriptions, default parameters |
| GET / POST | `/datasets` | list; generate (name, size ≤ 100k, seed) and store |
| GET | `/datasets/{id}` | one dataset |
| GET | `/datasets/{id}/entries?offset=&limit=` | page of entries with leaf hashes (limit ≤ 500) |
| PUT | `/datasets/{id}/entries/{pos}` | rewrite a stored entry's message (the attacker, for the anchor demo) |
| GET | `/datasets/{id}/chunks` | boundaries, sizes, depths, chunk roots, super-root |
| GET | `/datasets/{id}/chunks/{c}/tree` | one chunk's tree as levels of hex hashes (≤ 4,096 leaves) |
| GET | `/datasets/{id}/supertree` | the super-tree as levels |
| GET | `/datasets/{id}/proof/{i}` | two-stage proof with `verifyWithTrace` steps for both stages |
| POST | `/datasets/{id}/tamper` | `{operation: edit\|insert, position, message, parameters}` → per-subject changed chunks, counted rebuild cost, roots before/after |
| POST | `/datasets/{id}/anchors` | anchor the current super-root under a strategy |
| GET | `/datasets/{id}/anchors` | anchor history |
| GET | `/datasets/{id}/anchors/verify` | recompute and compare with the latest anchor for that strategy |
| GET | `/benchmarks` | `docs/benchmarks/results.json`, copied onto the classpath at build |
| POST | `/admin/seed` | delete everything, recreate demo-512 / demo-2k / demo-10k |

Errors are RFC 9457 problem details: unknown dataset/anchor → 404; bad strategy, parameter or
index → 400.

### 5.2 PostgreSQL 18 (Flyway V1)

- `datasets` — id, name, seed, size, created_at
- `log_entries` — (dataset_id, position) primary key; entry_id, logged_at, level, source, message
- `root_anchors` — dataset_id, strategy, parameters, entry_count, chunk_count, super_root
  (CHAR(64) hex), anchored_at — the paper's "trusted anchor" (§3.1) made real.

Render's free Postgres expires every 30 days: the demo data is seeded on startup when the
database is empty (`app.seed-on-startup`), and `POST /api/admin/seed` rebuilds it on demand.

### 5.3 Visualiser views (as built)

1. **§1 Chunking** (`#chunking`) — the same log cut by all five strategies, to scale, over a
   movable window; fixed chunk size and memory-pressure profile adjustable; click a chunk to
   prove one of its entries.
2. **§2 Tree & proof** (`#proof`) — the entry's path drawn on its chunk tree and the
   super-tree, climbing one level per step beside the verification trace; verdict stamped.
3. **§3 Tamper & insert** (`#tamper`) — one edit or insertion across all six subjects
   (changed chunks, counted rebuild cost, detection); **§3.1** anchor → rewrite in PostgreSQL
   → verify fails → undo.
4. **§4 Results** (`#results`) — headline figures, Figs. 5–9, Tables 1–2, caveats; legend hover
   follows one strategy through every figure.

Design system: `frontend/DESIGN.md` ("The Ledger"). New views must use `src/ui/` primitives and
the tokens in `src/index.css`.

### 5.4 Deployment (Render) — as built

- **One web service `caac`** (`runtime: docker`, free, Singapore) built from the root
  `Dockerfile`: Node builds the frontend → Maven packages the jar with the frontend inside as
  `static/` → JRE 21 runtime, non-root, `JAVA_OPTS` sized for 512 MB. Same origin, so no CORS.
- **Database `caac-db`**: Render Postgres 18, free (1 GB, expires after 30 days), `ipAllowList: []`
  (only Render services can connect). `render.yaml` passes `DB_HOST`, `DB_PORT`, `DB_NAME`,
  `DATABASE_USERNAME`, `DATABASE_PASSWORD` via `fromDatabase`; `application.yml` assembles the JDBC URL.
- **Presenter key**: `ADMIN_KEY` (`generateValue: true`). `AdminGuard` requires `X-Admin-Key` on
  every database write: `POST /datasets`, `POST /admin/**`, `PUT /datasets/*/entries/*`,
  `POST /datasets/*/entries/*/restore`, `POST /datasets/*/anchors`. Reads, proofs and the in-memory
  `/tamper` stay public. No key configured (local) = no lock. The UI asks for the key in §3.1.
- **Health check** `GET /api/health` (no database work); `GET /api/config` tells the UI whether a
  key is required.
- Demo data is seeded on startup when the database is empty. Benchmarks are served from the
  committed results; live benchmark runs are local only.
- Free tier: sleeps after 15 min idle, ~1–2 min to wake. Open it before presenting.

---

## 6. Hard constraints (unchanged from Phase 1)

1. Real SHA-256 only (`MessageDigest`) — never mocked, not even in tests.
2. Genuine O(log n) proofs — index arithmetic over stored levels, no linear scans on the proof
   path.
3. `core/`, `chunking/`, `benchmark/` have zero Spring imports.
4. Viva-readable over clever; Javadoc explains *why*.
5. Tests written with the implementation, never after.
6. RFC 6962 domain separation; odd node promoted, never duplicated; degenerate cases
   (0 entries, 1 chunk, 1 entry) keep their defined behaviour.

---

## 7. Roadmap (≈5 days)

| Day | Work |
|---|---|
| 1 — engine ✅ (2026-10-05) | Fix `MerkleForest.withEntryReplaced` (rebuilt every chunk) to reuse untouched trees; add hash-operation counting in `Hashing` and assert counted work in tests; implement `ResourceAwareChunking`, `PaperPipeline`, `ContentAnchoredChunking`; register in the factory; tests incl. insertion locality |
| 2 — benchmarks + backend ✅ (2026-10-05) | `BenchmarkRunner` → `docs/benchmarks/*.json`; Spring Boot upgrade; web/JPA/Flyway/Postgres deps; Flyway V1; local DB + user `merklelog` |
| 3 — API + frontend ✅ (2026-10-05) | REST endpoints (§5.1); scaffold `frontend/`; chunking strip + tree/proof views |
| 4 — frontend ✅ (2026-10-05) | Tamper/insert view, results dashboard, anchor history |
| 5 — ship | Render deployment; final README/architecture; end-review PPT from the real graphs; demo rehearsal |

Git rules for this push: at most **6 commits per day**, each a **large bundled commit** of
working, tested changes; **ask before every push**.
