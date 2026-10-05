# handoff.md — current state and next steps

Update at the end of every working session. The spec is in `instructions.md`; the rules are in
`CLAUDE.md`.

_Last updated: 2026-10-05 (end of day 1)_

---

## Where we are

- **Day 1 (engine) is done.** `cd backend && mvn test` → **334 tests, all passing**.
  - `Hashing` counts every leaf/node hash (`Hashing.operationCount()`); rebuild costs are now
    measured, not reported.
  - **Rebuild bug fixed**: `MerkleForest.withEntryReplaced` reuses untouched chunk trees and the
    edited chunk's other leaf hashes; cost = `1 + (c − 1) + (k − 1)` hashes, asserted in tests.
    `RebuildResult` gained `hashOperations`.
  - Paper baseline: `ResourceAwareSizer` (Eq. 1–2), `MemoryPressureProfile` (simulated,
    incl. `paperStressTest()`), `ResourceAwareChunking` (`resource-aware`), `PaperPipeline`
    (global tree rebuilt per batch).
  - **CAAC**: `ContentAnchoredChunking` (`caac`), per `instructions.md` §3.2.
  - Both registered in `ChunkingStrategyFactory`; `ChunkingInvariantTest` now runs on all five.
  - Console demo shows counted rebuild cost vs the paper's pipeline and all five strategies;
    `docs/demo-output.txt` regenerated (the super-root is unchanged, so hashes are still
    reproducible).
- README, `docs/architecture.md` and `instructions.md` updated with the day-1 results.

## First measurements (10,000 entries, one run — not yet the benchmark)

| Strategy | Chunks | Roots changed per insertion (avg / worst of 20) | Hashes to rebuild one edit |
|---|---|---|---|
| fixed-size | 157 | 82.7 / 157 | 220 |
| time-window | 797 | 1.0 / 1 | 825 |
| entropy | 513 | 1.2 / 3 | 530 |
| resource-aware (in forest) | 143 | 75.1 / 143 | 212 |
| caac | 149 | 1.2 / 2 | 188 |
| paper pipeline | — | — | 10,000 |

**Story correction:** time-window and entropy are also local. CAAC's claim is locality **plus**
memory-aware chunk size (see `instructions.md` §3.2, "How to frame the claim"). Keep the slides
and the viva consistent with this; don't claim CAAC is the only local strategy.

## Known issues / notes

1. The super-tree takes chunk roots without a leaf prefix, so the super-root does not commit to
   chunk boundaries (two 2-entry chunks = one 4-entry chunk over the same data). Fine for tamper
   detection; be ready to explain it in the viva.
2. CAAC hashes each entry once to find anchors, and `MerkleForest.build` hashes it again for the
   tree. That doubles leaf hashing at ingest, which will show in the throughput benchmark. Either
   state it, or pass precomputed leaf hashes into the forest (day 2 decision).
3. A pressure change between windows changes CAAC's size range, so some boundaries move with it
   (documented limitation).
4. GitHub's sidebar showed "claude" as a contributor even though no commit credits Claude
   (checked via the API and GraphQL); it's a stale GitHub cache. Contact GitHub Support if it
   persists.

## Decisions log

| Date | Decision |
|---|---|
| 2026-10-05 | New objective: CAAC, extending Yağız et al.'s resource-aware sizing; the paper is credited. |
| 2026-10-05 | The paper baseline is implemented faithfully (sizing rule + global-tree, rebuild-per-batch pipeline). |
| 2026-10-05 | Memory pressure comes from a simulated deterministic profile (paper §5.3), not live JVM memory. |
| 2026-10-05 | CAAC cut rule: low bits of the entry's leaf hash, inside the Eq. 1–2 size range. |
| 2026-10-05 | Sizer defaults M_total 1024, M_target 0.5, K 6, C_min 8, C_max 256 → T = 70 at P 0.25, 9 at P 0.85. |
| 2026-10-05 | Synthetic data only, at the paper's sizes (1k–100k), 5 runs each. |
| 2026-10-05 | Visualiser: Spring Boot REST API + React (Vite, TS, Tailwind, Recharts). |
| 2026-10-05 | Deploy to Render; PostgreSQL 18 kept for datasets/log entries + the trusted root anchor history. |
| 2026-10-05 | Spring Boot 3.3.5 → latest 3.x. |
| 2026-10-05 | `instructions.md` / `handoff.md` are tracked in git. |
| 2026-10-05 | Git: ≤ 6 commits/day, each a large bundled commit; ask before every push. |
| 2026-10-05 | Never credit Claude/AI as author or contributor anywhere (commits, PRs, docs). |
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

## Next steps (day 2 — benchmarks + backend skeleton)

1. `benchmark/BenchmarkRunner` (Spring-free): 5 strategies + the paper pipeline × sizes
   1k/5k/10k/50k/100k × 5 runs. Metrics per `instructions.md` §4. Write
   `docs/benchmarks/results.json` (+ CSV).
2. Decide on issue 2 (double leaf hashing) before measuring throughput.
3. Upgrade Spring Boot to the latest 3.x; add web, JPA, Flyway, PostgreSQL dependencies
   (keep `core/`, `chunking/`, `benchmark/` Spring-free).
4. Flyway V1: `datasets`, `log_entries`, `root_anchors`. Create a local DB + user `merklelog`
   (not the `postgres` superuser).
5. `mvn test` green → bundled commit(s) → ask before pushing.

## Commit tally

| Date | Commits | Notes |
|---|---|---|
| 2026-10-05 | 2 | (1) docs overhaul — pushed; (2) day-1 engine: rebuild fix, hash counting, paper baseline, CAAC, tests, docs |
