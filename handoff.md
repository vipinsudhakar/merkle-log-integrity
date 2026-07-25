# Handoff

Rolling progress log. **Read [instructions.md](instructions.md) first** for project details,
locked design decisions, and constraints — this file only tracks *state*.

**Last updated:** 2026-07-25
**Current phase:** Phase 1 — backend core DSA logic
**Branch:** `main`

---

## Where we are right now

Planning is complete and approved. **No implementation code exists yet** — the repo is still
a scaffold (README, LICENSE, .gitignore) plus the docs added this session.

Next action: Phase 1, commit 1 — Maven scaffold.

---

## Done

### Session 1 — 2026-07-25 · planning

- Agreed the full 8-phase build plan and monorepo layout.
- Locked five design decisions (forest model, RFC 6962 prefixes, odd-node promotion, Shannon
  entropy chunking, pre-computed benchmark fixtures) — see instructions.md §3.
- Defined degenerate-case semantics up front (instructions.md §5) so they are deliberate and
  testable rather than incidental.
- Agreed workflow rules: max 6 commits/day, no push without approval.
- Created `instructions.md`, `handoff.md`, `CLAUDE.md`.

---

## Next up — Phase 1 commit slicing

Phase 1 is sliced into exactly 6 commits (one day's budget). Each must leave `mvn test`
green.

| # | Commit | Contents | Status |
|---|---|---|---|
| 1 | scaffold | `backend/pom.xml` (Java 21, JUnit 5, AssertJ), extended `.gitignore`, `instructions.md`, `handoff.md`, `CLAUDE.md` | ☐ |
| 2 | hashing | `Hashing.java` — SHA-256, RFC 6962 leaf/node prefixes, hex codec + `HashingTest` (known-answer vectors, prefix separation) | ☐ |
| 3 | tree | `LogEntry`, `MerkleNode`, `MerkleTree` (bottom-up, retains levels) + `MerkleTreeTest` (n = 1, 2, 3, 7, 8, 1000; odd-promotion path) | ☐ |
| 4 | proofs | `MerkleProof`, `ProofStep`, `MerkleVerifier` + `MerkleProofTest` (every leaf verifies; depth == `ceil(log2 n)`) and `TamperDetectionTest` (mutation detected; forged proof rejected) | ☐ |
| 5 | chunking | `ChunkingStrategy`, `Chunk`, fixed-size / time-window / entropy strategies, factory + their tests and `ChunkingInvariantTest` | ☐ |
| 6 | forest | `MerkleForest` (super-root, localized rebuild, degenerate cases) + `MerkleForestTest`, `MerklePropertyTest`, `docs/architecture.md` | ☐ |

---

## Commit budget

**Max 6 commits per day. Never push without explicit approval.**

| Date | Commits used | Pushed? |
|---|---|---|
| 2026-07-25 | 0 / 6 | — |

---

## Open questions / decisions still needed

Non-blocking — flagged now, answer when the phase arrives.

- **Phase 2:** confirm `/api/verify` returns every intermediate hash (needed by the Phase 5
  stepper). Default assumption: **yes**.
- **Phase 3:** seed volume for the deployed demo (assumption: **10k entries** — comfortable
  on free-tier Postgres, still shows meaningful tree depth); whether tampering in the
  deployed demo writes to the DB or stays session-scoped in memory.
- **Phase 4:** tree rendering approach — hand-rolled SVG (recommended, best control for the
  Phase 5 path highlight) vs a library like `react-d3-tree`. Also the max node count before
  switching to a summarized view.
- **Phase 6:** which single metric headlines the comparison page (proof size vs rebuild cost).
- **Phase 8:** whether the README carries a live public URL; whether the seed endpoint stays
  enabled in production behind a token or is disabled after initial seeding.

---

## Gotchas worth remembering

- Verifying an **empty proof** (single-entry tree) must return `true` against the correct
  root and `false` against a wrong one. An implementation that short-circuits to `true` will
  pass a naive test while leaving a real verification hole.
- The entropy chunker needs a **max** chunk-size guard, not just a min — a long low-entropy
  run would otherwise collapse into one giant chunk and distort the proof-size comparison.
- `core/` and `chunking/` must stay Spring-free. If a Spring import appears there, something
  has been put in the wrong layer.

---

## How to resume in a new session

1. Read `instructions.md` (project, decisions, constraints).
2. Read this file (state, next commit, budget).
3. `cd backend && mvn test` — confirm green before changing anything.
4. `git log --oneline -10` — confirm the last commit matches the table above.
5. Continue at the first unchecked commit row.
