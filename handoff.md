# handoff.md — current state and next steps

Update at the end of every working session. The spec is in `instructions.md`; the rules are in
`CLAUDE.md`.

_Last updated: 2026-10-05 (end of day 2)_

---

## Where we are

- **Days 1 and 2 are done.** `cd backend && mvn test` → **346 tests, all passing**.
- **Day 1 (engine):** counted hashing (`Hashing.operationCount()`), localised rebuild fix in
  `MerkleForest.withEntryReplaced`, the paper's method (`ResourceAwareSizer`,
  `MemoryPressureProfile`, `ResourceAwareChunking`, `PaperPipeline`), and CAAC
  (`ContentAnchoredChunking`).
- **Day 2:**
  - `MerkleForest.build` hashes each entry exactly once and passes the leaf hashes to the
    strategy (`ChunkingStrategy.chunk(entries, leafHashes)`), so CAAC no longer hashes twice and
    ingest costs are equal (2n − 1) for every forest. Tested for all five strategies.
  - `benchmark/` package (Spring-free): `Subject` (one interface for the 5 forest strategies +
    the paper's pipeline), `BenchmarkRunner`, `Stats`, `Json`. **Full results committed** in
    `docs/benchmarks/` (`results.json`, `results.csv`, `README.md` with the method).
    `BenchmarkRunnerTest` runs it end to end on 2k entries.
  - **Spring Boot 3.3.5 → 3.5.16**; web, validation, data-jpa, Flyway (pinned to 11.20.3 so
    PostgreSQL 18 is supported without warnings), PostgreSQL driver, spring-boot-starter-test.
    `MerkleLogApplication` (root package); `application.yml` reads `DATABASE_URL`,
    `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `PORT`.
  - **Flyway V1**: `datasets`, `log_entries`, `root_anchors`. Verified: the app starts against
    local PostgreSQL 18.6 and migrates an empty database.
  - Local DB `merklelog` owned by user `merklelog` (not the superuser). Its password is in the
    git-ignored `backend/config/application.yml` (auto-loaded when run from `backend/`) and in
    `%APPDATA%\postgresql\pgpass.conf`.

## Headline results (100k entries; full tables in `docs/benchmarks/` and the README)

| Subject | Roots changed per insertion | Hashes to rebuild after insertion | after edit |
|---|---|---|---|
| fixed-size | 782 | 100,839 | 1,624 |
| time-window | 1.0 | 7,931 | 7,909 |
| entropy | 1.7 | 5,196 | 5,149 |
| resource-aware (in forest) | 715 | 100,783 | 1,497 |
| **caac** | **1.2** | **1,603** | 1,491 |
| paper pipeline | — | 100,001 | 100,000 |

- **The claim** (`instructions.md` §3.2): CAAC is the only strategy that keeps insertions local
  **and** keeps chunk sizes at the paper's memory-aware target; ~63× cheaper insertions than
  count-based chunking or the global tree, 3–5× cheaper than time-window or entropy.
- **Don't claim** CAAC makes edits cheaper than fixed-size (it doesn't: edits cost about the same
  in every forest), and don't claim it's the only local strategy. The day-1 single-position
  number (188 vs 220) was misleading and has been removed from the docs.
- Tamper detection P/R/F1 = 1.0 for every subject and ratio. Under the stress profile CAAC's
  chunks go ~70 → ~8 → ~83 entries (the paper's method: 70 → 9 → 69).
- The paper pipeline's ingest is slow at 100k (≈ 72M hashes): Algorithm 1 rebuilds the global
  tree after every 70-entry batch. Stated as a caveat; bigger batches would help its ingest but
  not its edit or insertion cost.

## Known issues / notes

1. The super-tree takes chunk roots without a leaf prefix, so the super-root does not commit to
   chunk boundaries (two 2-entry chunks = one 4-entry chunk over the same data). Fine for tamper
   detection; be ready to explain it in the viva.
2. ~~CAAC hashed each entry twice at ingest~~ — fixed on day 2 (shared leaf hashes).
3. A pressure change between windows changes CAAC's size range, so some boundaries move with it
   (documented limitation).
4. GitHub's sidebar showed "claude" as a contributor even though no commit credits Claude
   (checked via the API and GraphQL); it's a stale GitHub cache. Contact GitHub Support if it
   persists.
5. Edit cost in every forest is dominated by the super-tree rebuild (k − 1 node hashes). A path
   update in the super-tree (O(log k)) would cut it further for all forest strategies equally;
   optional, not planned.
6. `docs/benchmarks/results.json` has machine-specific timings (this Windows laptop). Re-run the
   benchmark on the presentation machine if timings are shown live; counted metrics won't change.

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
| 2026-10-05 | Spring Boot 3.3.5 → 3.5.16; Flyway pinned to 11.20.3 (PostgreSQL 18 support). |
| 2026-10-05 | `instructions.md` / `handoff.md` are tracked in git. |
| 2026-10-05 | Git: ≤ 6 commits/day, each a large bundled commit; ask before every push. |
| 2026-10-05 | Never credit Claude/AI as author or contributor anywhere (commits, PRs, docs). |
| 2026-10-05 | The end-review PPT is built on day 5 from the real results. |
| 2026-10-05 | Headline metric is insertion cost (not edit cost); see the claim in `instructions.md` §3.2. |
| 2026-10-05 | Every strategy gets leaf hashes from `MerkleForest.build` (each entry hashed once). |

## Machine setup (Windows dev machine, set up 2026-10-04/05)

| Tool | Version / location |
|---|---|
| Repo | `C:\Users\SumithaArjunan\projects\merkle-log-integrity` |
| git / GitHub CLI | 2.56.0 / 2.102.0; `gh` logged in as `vipinsudhakar` (HTTPS, credential helper set up) |
| Java | Temurin 21.0.12 (`JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\`) |
| Maven | 3.10.0 at `C:\Users\SumithaArjunan\tools\apache-maven-3.10.0` (on the user PATH) |
| Node.js | 24.19.0 LTS |
| PostgreSQL | 18.6, Windows service `postgresql-x64-18`, port 5432, localhost-only. Superuser `postgres`; app DB `merklelog` owned by user `merklelog`. Passwords in `%APPDATA%\postgresql\pgpass.conf` and (app user) git-ignored `backend/config/application.yml` — never commit them |

Shells opened before these installs need a VS Code restart to see `mvn`, `node`, `psql`.

## Next steps (day 3 — API + frontend start)

1. `persistence/`: JPA entities + repositories for `datasets`, `log_entries`, `root_anchors`;
   a seeding service that rebuilds the demo datasets from an empty DB
   (`SyntheticLogGenerator.generate(n, seed)`).
2. `api/`: REST endpoints per `instructions.md` §5.1 (strategies, datasets, chunks, tree,
   proof + trace, tamper/insert diff, anchors, benchmarks from `docs/benchmarks/results.json`).
   Map `EmptyForestException` → 404 and bad parameters → 400 in one `@RestControllerAdvice`.
3. API tests with `spring-boot-starter-test` (MockMvc). Tests must not need a running Postgres
   unless we add Testcontainers (no Docker on this machine): keep controller/service tests on
   mocked repositories, and verify the DB path manually.
4. Scaffold `frontend/` (Vite + React + TS + Tailwind + Recharts); chunking strip and tree +
   proof views against the local API.
5. `mvn test` green → bundled commit(s) → ask before pushing.

## Commit tally

| Date | Commits | Notes |
|---|---|---|
| 2026-10-05 | 3 | (1) docs overhaul — pushed; (2) day-1 engine — pushed; (3) day-2 benchmarks + Spring Boot 3.5 / Flyway / PostgreSQL skeleton |
