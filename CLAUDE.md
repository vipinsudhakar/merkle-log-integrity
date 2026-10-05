# CLAUDE.md

**Before starting work, read `instructions.md` (full project spec, locked design decisions,
roadmap) and `handoff.md` (current progress, known issues, next steps).**

Both are tracked in git. They used to be git-ignored local notes and were lost with a laptop
in October 2026 — keep them committed. Update `handoff.md` at the end of every session.

## Project

Merkle tree–based tamper-evident log integrity verification. Advanced DSA course project
(B.Tech AI & DS), graded partly by oral viva.

Log entries are SHA-256 hashed into leaves of Merkle trees. Any mutation propagates to the
root, so tampering is detected AND localised in O(log n) via inclusion proofs, instead of
O(n) full re-hashing.

**Contribution: Content-Anchored Adaptive Chunking (CAAC).** It extends the resource-aware
batch sizing of our base paper (Yağız, Horasan, Yurttakal 2026, arXiv:2605.00065, Eq. 1–2)
with content-defined cut points (low bits of each entry's leaf hash) and one Merkle tree per
chunk, so an edit or insertion touches only nearby chunks instead of forcing the paper's full
per-batch rebuild (their limitation L4). It is benchmarked against fixed-size, time-window,
entropy, and the paper's own method (implemented faithfully). Always credit the paper.

## Stack

- Backend: Java 21, Spring Boot 3.5.16 (Flyway pinned to 11.20.3 for PostgreSQL 18), Maven, JUnit 5 + AssertJ
- Frontend: React (Vite, TypeScript), Tailwind CSS, Recharts
- DB: PostgreSQL 18 (Flyway migrations) — datasets/log entries + trusted root anchor history
- Deploy: Render — backend web service (Docker) + static frontend + Postgres

## Layout

- `backend/src/main/java/com/merklelog/core/`        — Merkle tree, proofs, verifier, forest
- `backend/src/main/java/com/merklelog/chunking/`    — the five strategies + factory
- `backend/src/main/java/com/merklelog/benchmark/`   — benchmark runner (writes `docs/benchmarks/`)
- `backend/src/main/java/com/merklelog/demo/`        — synthetic log generator, console demo
- `backend/src/main/java/com/merklelog/api/`         — controllers, DTOs, services
- `backend/src/main/java/com/merklelog/persistence/` — entities, repos, seeding
- `frontend/src/`                                    — pages, components, api client
- `docs/`                                            — architecture, benchmarks, demo output

## Hard constraints

1. **Real SHA-256 only.** `java.security.MessageDigest`. Never a mock, stub, or
   `hashCode()`, not even in tests or fixtures.
2. **Genuine O(log n).** Proof generation and verification must use index arithmetic over
   stored levels (`index ^ 1` for sibling, `index >>= 1` to ascend). No linear scan over
   leaves inside a proof path. If a change would introduce one, stop and flag it.
3. **`core/`, `chunking/` and `benchmark/` have zero Spring imports.** They must be testable
   with plain JUnit and no application context.
4. **Viva-readable over clever.** Clear names, Javadoc explaining *why*. Assume every line
   may be questioned aloud by an examiner.
5. **Tests before/with implementation, never bolted on after.** Correctness is the
   deliverable.
6. **Honest results.** Every number comes from the real engine. The paper baseline is
   implemented faithfully, never weakened. State caveats (Java vs Python timings, hex vs raw
   proof bytes, the entropy limitation).

## Tree rules (deliberate, and asked about in viva)

- RFC 6962 domain separation: `leafHash(d) = SHA256(0x00 || d)`,
  `nodeHash(L,R) = SHA256(0x01 || L || R)`. Prevents leaf/node confusion attacks.
- Odd node at a level is **promoted unchanged**, never duplicated — avoids the Bitcoin
  CVE-2012-2459 duplicate-node forgery.
- Chunk = one independent Merkle tree (forest model); chunk roots are sealed under a
  super-root. This is why chunking strategy changes proof size and rebuild cost.

## Degenerate cases (defined, not incidental)

- 0 entries → empty forest, root == `SHA256("")` (RFC 6962 empty-tree hash). Never null.
  Proof requests throw a named exception.
- 1 chunk → super-root **is** that chunk root, promoted unchanged.
- 1 entry → root == `leafHash(entry)`, proof is an empty step list. An empty proof must
  verify correctly against the right root and **fail** against a wrong one — never
  vacuously true.

Changing any of these changes tested behavior. Don't "fix" them silently.

## Known limitations to state honestly

- Shannon entropy estimated over a short byte window is biased low and noisy, so for short log
  payloads the entropy boundary signal degrades toward arbitrary. Guarded with min/max chunk
  size. Document it; do not paper over it in the UI, the README, or the benchmark write-up.
- CAAC boundaries are content-anchored *within* a memory-pressure regime; when the simulated
  pressure changes, the size range (and therefore some boundaries) changes too.

## Workflow

- Build in the order of the roadmap in `instructions.md` §7; do not jump ahead.
- **Max 6 commits per day, each a large bundled commit** of working, tested changes (not
  many tiny commits). Track usage in the `handoff.md` commit tally.
- **Never push without explicit approval — ask before every push.** Local commits are fine.
- **Never credit Claude or any AI as an author or contributor.** No `Co-Authored-By` trailers,
  no "Generated with Claude Code" lines, in commits, PR descriptions, docs or code comments.
  Commits are authored by the team member only.
- `mvn test` must be green before any commit.
- Update `handoff.md` at the end of every working session.
- Render's free Postgres expires every 30 days — the seed path must rebuild all data from an
  empty database. Never assume existing state or a manual restore.
- **Frontend:** follow `frontend/DESIGN.md` ("The Ledger"): use the `src/ui/` primitives and the tokens in
  `src/index.css`; CAAC is the only saturated colour; motion only where it explains something.
  The browser never computes hashes, it shows what the API returns. Check views in a real browser
  (Playwright with the installed Edge, from a scratch folder, not a project dependency).
- Deployed benchmarks are served from a committed fixture (`render` profile); live runs are
  local-dev only. Render's free tier would produce misleading numbers against the base-paper
  reference lines.

## Commands

- `cd backend && mvn test`                                         — unit tests
- `cd backend && mvn -q compile && java -cp target/classes com.merklelog.demo.DemoRunner` — console demo
- `cd backend && java -cp target/classes com.merklelog.benchmark.BenchmarkRunner [--quick]` — benchmark → `docs/benchmarks/results.json|csv` (full run ≈ 6 min; don't run other heavy work at the same time, it skews timings)
- `cd backend && mvn spring-boot:run`                              — API on :8080 (needs local Postgres; password in git-ignored `backend/config/application.yml`)
- `cd frontend && npm run dev` — visualiser on :5173 (proxies /api to :8080); `npm run build` type-checks and builds; `npm run lint`
- `POST /api/admin/seed`                                           — rebuild demo data from scratch
- `MERKLELOG_DB_TESTS=true mvn test` — also run the database tests (needs the local PostgreSQL)
- Deploy: push to `main`; Render rebuilds from `render.yaml` + `Dockerfile`. Writes on the public site need `X-Admin-Key` (`ADMIN_KEY` in Render)
