# Handoff

Rolling progress log. **Read [instructions.md](instructions.md) first** for project details,
locked design decisions, and constraints — this file only tracks *state*.

**Last updated:** 2026-07-25
**Current phase:** Phase 1 complete → Phase 2 (REST API) next
**Branch:** `main` · **Unpushed:** 6 commits

---

## Where we are right now

**Phase 1 is done.** The core DSA logic is complete and tested: Merkle tree construction,
inclusion proofs, verification, the forest model, and all three chunking strategies.
**242 tests passing**, zero Spring on the classpath.

Six commits sit on local `main`, **not yet pushed** — push needs approval.

Next action: Phase 2, the REST API layer. See the open decision below before starting.

---

## Done

### Session 1 — 2026-07-25 · planning + Phase 1

**Planning**

- Agreed the 8-phase build plan and monorepo layout.
- Locked five design decisions (forest model, RFC 6962 prefixes, odd-node promotion, Shannon
  entropy chunking, pre-computed benchmark fixtures) — instructions.md §3.
- Defined degenerate-case semantics up front — instructions.md §5.
- Agreed workflow: max 6 commits/day, no push without approval.

**Toolchain** (was missing entirely on this machine)

- Installed Temurin **JDK 21.0.11 LTS** via winget →
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
- Installed **Maven 3.9.9** from the Apache archive (not in winget's source), SHA-512
  verified → `%LOCALAPPDATA%\Programs\apache-maven-3.9.9`
- `JAVA_HOME` and `PATH` persisted to the **User** environment. A *new* shell picks them up
  automatically; an already-open one does not.

**Phase 1 — all six commits landed, `mvn test` green at each**

| # | Commit | Contents | Status |
|---|---|---|---|
| 1 | `2c26a7e` scaffold | `backend/pom.xml` (Java 21, JUnit 5, AssertJ), `.gitignore`, `instructions.md`, `handoff.md`, `CLAUDE.md` | ✅ |
| 2 | `d5c7b4d` hashing | `Hashing` — SHA-256, RFC 6962 prefixes, hex codec, constant-time compare (15 tests) | ✅ |
| 3 | `90e03fa` tree | `LogEntry`, `MerkleNode`, `MerkleTree` construction (65 tests) | ✅ |
| 4 | `d698044` proofs | `MerkleProof`, `ProofStep`, `MerkleVerifier`, `generateProof` (133 tests) | ✅ |
| 5 | `1178519` chunking | 3 strategies + factory + shared invariant suite (205 tests) | ✅ |
| 6 | *(this one)* forest | `MerkleForest`, `ForestProof`, `EmptyForestException`, property tests, `docs/architecture.md` (242 tests) | ✅ |

---

## Next up — Phase 2: REST API layer

Not yet sliced into commits. Rough shape (see the approved plan for detail):

- Spring Boot web starter, `MerkleLogApplication`, CORS for the Vite dev origin
- `POST /api/logs`, `GET /api/tree`, `GET /api/tree/root`, `GET /api/proof/{id}`,
  `POST /api/verify`, `POST /api/tamper/{id}`, `POST /api/tamper/reset`,
  `GET /api/chunking/strategies`
- DTO records mapping core types → JSON, hashes as hex
- `@ControllerAdvice` error handling; springdoc OpenAPI
- `@WebMvcTest` slice tests per controller

**Keep `core` and `chunking` Spring-free.** The API wraps them; it does not reach into them.

`MerkleVerifier.verifyWithTrace` already returns every intermediate hash, so `/api/verify`
can serve the Phase 5 stepper directly rather than recomputing.

---

## Commit budget

**Max 6 commits per day. Never push without explicit approval.**

| Date | Commits used | Pushed? |
|---|---|---|
| 2026-07-25 | **6 / 6 — budget exhausted** | ❌ awaiting approval |

---

## Open questions / decisions still needed

- **Phase 2 (needed before starting):** should `/api/verify` return every intermediate hash?
  Assumption: **yes** — `verifyWithTrace` already produces them and Phase 5 needs them.
- **Phase 3:** seed volume for the deployed demo (assumption: **10k entries**); whether
  tampering in the deployed demo writes to the DB or stays session-scoped in memory.
- **Phase 4:** tree rendering — hand-rolled SVG (recommended) vs `react-d3-tree`; max node
  count before switching to a summarised view.
- **Phase 6:** which single metric headlines the comparison page (proof size vs rebuild cost).
- **Phase 8:** live public URL in the README?; does the seed endpoint stay enabled in
  production behind a token, or get disabled after initial seeding?

---

## Deviations from the original plan (accepted, worth knowing)

1. **Proof length is `≤ ceil(log2 n)`, not `==`.** The plan said assert equality. Because a
   promoted odd node contributes no proof step, right-edge leaves get *shorter* proofs. The
   tests assert the bound, plus exact equality when n is a power of two (where nothing is
   ever promoted). Still O(log n) — the claim is intact, the assertion is just honest.
2. **`EmptyForestException` is unchecked, not checked.** The plan said checked; a checked
   exception would force try/catch at every call site including the API layer. It extends
   `IllegalStateException` and is clearly named. instructions.md §5 says only "named
   exception", which this satisfies.

---

## Gotchas worth remembering

- **Running Maven from a tool shell:** `mvn` is on the *User* PATH, which an already-running
  shell does not see. Prefix commands with:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
  $env:Path = "$env:JAVA_HOME\bin;$env:LOCALAPPDATA\Programs\apache-maven-3.9.9\bin;$env:Path"
  ```
- **Do not pipe `mvn` into `Select-Object -First N`.** Closing the pipe early kills Maven and
  reports exit 255, which looks exactly like a test failure but is not. Capture with
  `Out-String` first, then filter.
- Verifying an **empty proof** (single-entry tree) must return `true` against the correct
  root and `false` against a wrong one. A verifier that short-circuits on zero steps passes a
  naive test while leaving a real hole. Covered in `MerkleProofTest.SingleLeafTree`.
- The entropy chunker needs a **max** chunk-size guard, not just a min — a long low-entropy
  run would otherwise collapse into one giant chunk and distort every benchmark number.
- `core` and `chunking` must stay Spring-free. A Spring import there means something landed
  in the wrong layer.
- `MerkleTree.toNodeTree()` is O(n) and exists only for the Phase 4 visualisation. Never call
  it from a proof path.

---

## How to resume in a new session

1. Read `instructions.md` (project, decisions, constraints).
2. Read this file (state, next phase, budget).
3. `cd backend && mvn test` — expect **242 passing**.
4. `git log --oneline -8` — expect the six commits above on top of `53830c3`.
5. Start Phase 2, answering the `/api/verify` question first.
