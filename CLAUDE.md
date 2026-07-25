# CLAUDE.md

**Read [instructions.md](instructions.md) for full project detail and
[handoff.md](handoff.md) for current progress before starting work.**

## Project

Merkle tree–based tamper-evident log integrity verification system. Advanced DSA course
project (B.Tech AI & DS), graded partly by oral viva.

Log entries are SHA-256 hashed into leaves of a Merkle tree. Any mutation propagates to the
root, so tampering is detected AND localized in O(log n) via inclusion proofs, instead of
O(n) full re-hashing.

**Contribution over the base paper** (Yağız, Horasan, Yurttakal 2026, which validates one
adaptive-chunking approach): we implement and benchmark three chunking strategies —
fixed-size, time-window, entropy-based — comparing proof size, verification latency, and
tree rebuild cost.

## Stack

- Backend: Java 21, Spring Boot 3.x, Maven, JUnit 5 + AssertJ
- Frontend: React (Vite), Tailwind CSS
- DB: PostgreSQL (Flyway migrations)
- Deploy: Render — backend web service + static frontend + free Postgres

## Layout

- `backend/src/main/java/com/merklelog/core/`     — Merkle tree, proofs, verifier, forest
- `backend/src/main/java/com/merklelog/chunking/` — the three strategies
- `backend/src/main/java/com/merklelog/api/`      — controllers, DTOs, services
- `backend/src/main/java/com/merklelog/persistence/` — entities, repos, seeding
- `frontend/src/`                                 — pages, components, api client
- `docs/`                                         — architecture, benchmarks, viva notes

## Hard constraints

1. **Real SHA-256 only.** `java.security.MessageDigest`. Never a mock, stub, or
   `hashCode()`, not even in tests or fixtures.
2. **Genuine O(log n).** Proof generation and verification must use index arithmetic over
   stored levels (`index ^ 1` for sibling, `index >>= 1` to ascend). No linear scan over
   leaves inside a proof path. If a change would introduce one, stop and flag it.
3. **`core/` and `chunking/` have zero Spring imports.** They must be testable with plain
   JUnit and no application context.
4. **Viva-readable over clever.** Clear names, Javadoc explaining *why*. Assume every line
   may be questioned aloud by an examiner.
5. **Tests before/with implementation, never bolted on after.** Correctness is the
   deliverable.

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

## Known limitation to state honestly

Shannon entropy estimated over a short byte window is biased low and noisy, so for short log
payloads the entropy boundary signal degrades toward arbitrary. Guarded with min/max chunk
size. Document it; do not paper over it in the UI, the README, or the benchmark write-up.

## Workflow

- Build phase by phase in the agreed order; do not jump ahead.
- **Max 6 commits per day.** Small, incremental, individually-working commits — not one big
  commit per phase. Track usage in `handoff.md`.
- **Never push without explicit approval.** Local commits are fine; pushing is not.
- `mvn test` must be green before any commit.
- Update `handoff.md` at the end of every working session.
- Render's free Postgres expires every 30 days — the seed path must rebuild all data from an
  empty database. Never assume existing state or a manual restore.
- Deployed benchmarks are served from a committed fixture (`render` profile); live runs are
  local-dev only. Render's free tier would produce misleading numbers against the base-paper
  reference lines.

## Commands

- `cd backend && mvn test`            — unit tests
- `cd backend && mvn spring-boot:run` — API (Phase 2+)
- `cd frontend && npm run dev`        — frontend dev server
- `POST /api/admin/seed`              — rebuild synthetic data from scratch
