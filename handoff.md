# handoff.md — current state and next steps

Update at the end of every working session. The spec is in `instructions.md`; the rules are in
`CLAUDE.md`.

_Last updated: 2026-10-05_

---

## Where we are

- **Phase 1 (core engine) is done**: hashing (RFC 6962), canonical `LogEntry`, level-array
  `MerkleTree`, proofs + verifier (with trace), `MerkleForest` (per-chunk trees under a
  super-root), three chunking strategies, synthetic log generator, console demo.
- `cd backend && mvn test` → **260 tests, all passing** (verified 2026-10-05 on the new
  machine). `docs/architecture.md` previously said 242; corrected.
- The console demo output matches `docs/demo-output.txt` line for line (deterministic seed).
- **The objective changed on 2026-10-05** to CAAC (see `instructions.md` §1). Nothing of the new
  work is implemented yet; this session only rewrote the docs.

## Known issues

1. **`MerkleForest.withEntryReplaced` rebuilds every chunk.** It calls `fromChunks(...)` on the
   full chunk list, so the real work is O(n), while `RebuildResult.entriesRehashed` reports only
   the edited chunk's size. Tests only assert the *reported* number. **Fix first thing on day 1**
   (reuse untouched chunk trees + count hash operations), or every rebuild-cost number —
   including the headline CAAC result — is wrong.
2. The super-tree takes chunk roots without a leaf prefix, so the super-root does not commit to
   chunk boundaries (two 2-entry chunks = one 4-entry chunk over the same data). Fine for tamper
   detection; be ready to explain it in the viva.

## Decisions log

| Date | Decision |
|---|---|
| 2026-10-05 | New objective: CAAC, extending Yağız et al.'s resource-aware sizing; the paper is credited. |
| 2026-10-05 | The paper baseline is implemented faithfully (sizing rule + global-tree, rebuild-per-batch pipeline). |
| 2026-10-05 | Memory pressure comes from a simulated deterministic profile (paper §5.3), not live JVM memory. |
| 2026-10-05 | CAAC cut rule: low bits of the entry's leaf hash, inside the Eq. 1–2 size range. |
| 2026-10-05 | Synthetic data only, at the paper's sizes (1k–100k), 5 runs each. |
| 2026-10-05 | Visualiser: Spring Boot REST API + React (Vite, TS, Tailwind, Recharts). |
| 2026-10-05 | Deploy to Render; PostgreSQL 18 kept for datasets/log entries + the trusted root anchor history. |
| 2026-10-05 | Spring Boot 3.3.5 → latest 3.x. |
| 2026-10-05 | `instructions.md` / `handoff.md` are now tracked in git (the old copies were lost with a laptop). |
| 2026-10-05 | Git: ≤ 6 commits/day, each a large bundled commit; ask before every push. |
| 2026-10-05 | The end-review PPT is built on day 5 from the real results. |

## Machine setup (Windows dev machine, set up 2026-10-04/05)

| Tool | Version / location |
|---|---|
| Repo | `C:\Users\SumithaArjunan\projects\merkle-log-integrity` |
| git / GitHub CLI | 2.56.0 / 2.102.0; `gh` logged in as `vipinsudhakar` (HTTPS, credential helper set up) |
| Java | Temurin 21.0.12 (`JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\`) |
| Maven | 3.10.0 at `C:\Users\SumithaArjunan\tools\apache-maven-3.10.0` (on the user PATH) |
| Node.js | 24.19.0 LTS |
| PostgreSQL | 18.6, Windows service `postgresql-x64-18`, port 5432, localhost-only, superuser `postgres`; password in `%APPDATA%\postgresql\pgpass.conf` (never commit it) |

Shells opened before these installs need a VS Code restart to see `mvn`, `node`, `psql`.

## Next steps (day 1 — engine)

1. Fix `withEntryReplaced` to reuse untouched chunk trees (a package-private factory taking the
   existing `chunkTrees`); add hash-operation counting to `Hashing`; make `MerkleForestTest`
   assert counted work.
2. `ResourceAwareChunking` (Eq. 1–2 + simulated pressure profile) and `PaperPipeline` (global
   tree, rebuild per batch).
3. `ContentAnchoredChunking` (`caac`) per `instructions.md` §3.2; register both new strategies
   in `ChunkingStrategyFactory` so `ChunkingInvariantTest` covers them.
4. Tests: insertion locality (CAAC changes ≤ a few chunk roots; fixed-size changes all later
   ones), determinism under a profile, adaptive shrink under stress.
5. `mvn test` green → one bundled commit → ask before pushing.

## Commit tally

| Date | Commits | Notes |
|---|---|---|
| 2026-10-05 | 1 | Docs overhaul: CAAC objective, notes now tracked |
