# Project Instructions

Durable reference for this project. Read this first in any new session, then read
[handoff.md](handoff.md) for current progress.

---

## 1. Project

**Merkle tree–based tamper-evident log integrity verification system.**
Advanced DSA course project, B.Tech AI & Data Science. Graded partly by **oral viva**.

Log entries are SHA-256 hashed into the leaves of a Merkle tree. Any mutation to a single
entry propagates to the root hash, so tampering is both **detected and localized in
O(log n)** via Merkle inclusion proofs — instead of O(n) full-dataset re-hashing.

### Contribution over the base paper

Base paper: Yağız, Horasan, Yurttakal — *Lightweight Tamper-Evident Log Integrity
Verification for IoT Edge Environments: A Merkle-Tree Pipeline with Adaptive Chunking*
(2026). It validates **one** adaptive-chunking approach.

We extend it by implementing and benchmarking **three** chunking strategies —
**fixed-size**, **time-window**, and **entropy-based** — comparing their effect on
**proof size**, **verification latency**, and **tree rebuild cost**.

### Base-paper reference numbers (Phase 7 chart reference lines)

| Metric | Paper value |
|---|---|
| Throughput | 130,000 logs/sec |
| Verification latency | ~22 ms |
| Proof generation | ~22 ms |
| Proof size | ~1006 bytes |

Our hardware and JVM differ from the paper's edge devices. These are **context lines, not a
pass/fail bar**, and the dashboard must say so.

### Team

- Vipin Sudhakar — CB.AI.U4AID25166
- Rithvik Arulprakash — CB.AI.U4AID25148
- Harshith KV — CB.AI.U4AID25119
- Venugopalan G — CB.AI.U4AID25115

---

## 2. Stack

- **Backend:** Java 21, Spring Boot 3.x, Maven, JUnit 5 + AssertJ
- **Frontend:** React (Vite), Tailwind CSS
- **Database:** PostgreSQL (Flyway migrations)
- **Deploy:** Render — backend web service + static frontend + free Postgres

---

## 3. Locked design decisions

These were decided deliberately and are **viva talking points**. Do not change them without
an explicit decision — tests depend on them.

1. **Chunk = one independent Merkle tree (forest model).** Each chunk builds its own tree
   with its own root; chunk roots are sealed under a super-root. This is *why* chunking
   strategy changes proof size and rebuild cost — a single global tree would make the
   comparison far weaker.
2. **RFC 6962 domain separation.**
   `leafHash(d) = SHA256(0x00 || d)`, `nodeHash(L,R) = SHA256(0x01 || L || R)`.
   Prevents leaf/node confusion attacks.
3. **Odd-node promotion, never duplication.** An odd node at a level is carried up
   unchanged. Avoids the Bitcoin CVE-2012-2459 duplicate-node forgery.
4. **Entropy chunking = rolling Shannon entropy** over a configurable byte window with a
   configurable threshold. Not a Rabin/Gear rolling hash — Shannon is literal to the name
   and far easier to defend orally.
5. **Deployed benchmarks are pre-computed fixtures.** Generated locally, committed as JSON,
   served under the `render` profile. Live runs are local-dev only — Render's free tier
   would produce misleadingly slow numbers against the paper's reference lines.

### Default parameters

Fixed-size 64 entries · time-window 60 s · entropy window 32 bytes, min 16 / max 256
entries. These affect demo feel only; Phases 6 and 7 sweep them.

---

## 4. Hard constraints (every phase)

1. **Real SHA-256 only.** `java.security.MessageDigest`. Never a mock, stub, or
   `hashCode()` — not even in tests or fixtures.
2. **Genuine O(log n).** Proof generation and verification use index arithmetic over stored
   levels (`index ^ 1` for sibling, `index >>= 1` to ascend). No linear scan over leaves
   inside a proof path. If a change would introduce one, stop and flag it.
3. **`core/` and `chunking/` have zero Spring imports.** They must be testable with plain
   JUnit and no application context.
4. **Viva-readable over clever.** Clear names, Javadoc explaining *why*. Assume every line
   may be questioned aloud by an examiner.
5. **Tests written before/with implementation, never bolted on after.** Correctness is the
   deliverable.

---

## 5. Degenerate cases (defined, not incidental)

Specified up front so behavior is deliberate. **Tested in Phase 1 — do not "fix" silently.**

- **0 entries** → 0 chunks → empty forest. Root is the sentinel `SHA256("")` (RFC 6962
  empty-tree hash). Never null, never an exception on construction. Proof requests against
  an empty forest throw a named exception.
- **1 chunk** → the super-root **is** that chunk's root, promoted unchanged (same rule as
  odd-node promotion, applied consistently rather than as a special case).
- **1 entry** → tree root == `leafHash(entry)`; its proof is an **empty step list**.
  Verifying an empty proof must yield `leafHash(entry)` and compare equal — and must return
  **false** against a wrong root. It must never short-circuit to `true`.
  Note `ceil(log2 1) == 0`, so the proof-depth assertion holds without a carve-out.

---

## 6. Known limitation (state honestly, do not paper over)

Shannon entropy estimated over a **short** byte window is biased low and noisy — a small
sample cannot exhibit the full byte-value distribution. For short log payloads the entropy
boundary signal therefore degrades toward arbitrary. Mitigated with min/max chunk-size
guards (max matters: a long low-entropy run would otherwise produce one giant chunk and
distort the proof-size comparison).

Must be documented in the `EntropyChunking` Javadoc, `docs/benchmarks.md`, and the viva
notes. Do not hide it in the UI or README.

---

## 7. Phase plan

Build **in order**. Do not jump ahead.

| Phase | Scope |
|---|---|
| **1** | Backend core DSA **only** — Merkle tree, proof gen, verification, 3 chunking strategies, forest. Plain Java, **no API**. JUnit tests proving proofs verify and tampering is detected. |
| **2** | REST API layer wrapping the core logic. |
| **3** | PostgreSQL persistence + entities + seed/regenerate mechanism (disposable-DB design). |
| **4** | React frontend — live Merkle tree visualization consuming the API. |
| **5** | Tamper simulation UI — click leaf → highlight leaf-to-root path → step-through proof verification. |
| **6** | Chunking strategy comparison UI. |
| **7** | Benchmark dashboard with base-paper reference lines. |
| **8** | Render deployment config + README documenting the DB-refresh workflow. |

Full per-phase deliverables live in the approved plan; this table is the ordering contract.

### Phase 3 note — disposable databases

Render's free Postgres **expires every 30 days**. The seed path must rebuild *everything*
from an empty database: never assume prior state, never require a manual dump restore.
Synthetic data uses a **fixed RNG seed** so runs are reproducible and benchmarks comparable.

---

## 8. Workflow rules

- **Max 6 commits per day.** Batch work into small, incremental, individually-working
  commits — not one big commit per phase.
- **`mvn test` must be green before any commit.**
- **Never push without explicit approval.** Committing locally is fine; pushing is not.
- Update [handoff.md](handoff.md) at the end of every working session.
- Each commit message states what was added and why, in plain language.

---

## 9. Repo layout

```
merkle-log-integrity/
├── instructions.md                 # this file — durable project reference
├── handoff.md                      # rolling progress log for session portability
├── CLAUDE.md                       # agent working agreement
├── README.md                       # public-facing; expanded in Phase 8
├── render.yaml                     # Phase 8 deployment blueprint
├── docs/                           # architecture.md, benchmarks.md, viva-notes.md
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/merklelog/
│       │   ├── core/               # Hashing, MerkleTree, proofs, verifier, forest
│       │   ├── chunking/           # 3 strategies + factory   (Phase 1)
│       │   ├── bench/              # BenchmarkRunner          (Phase 7)
│       │   ├── api/                # controllers, DTOs, svc   (Phase 2)
│       │   ├── persistence/        # entities, repos, seeding (Phase 3)
│       │   └── config/
│       └── test/java/com/merklelog/
└── frontend/                       # Vite + React + Tailwind  (Phase 4+)
    └── src/{api,hooks,components,pages,lib}
```

---

## 10. Commands

| Command | Purpose |
|---|---|
| `cd backend && mvn test` | Unit tests |
| `cd backend && mvn spring-boot:run` | Run API (Phase 2+) |
| `cd frontend && npm run dev` | Frontend dev server (Phase 4+) |
| `POST /api/admin/seed` | Rebuild synthetic data from scratch (Phase 3+) |
