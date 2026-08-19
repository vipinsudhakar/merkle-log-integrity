# merkle-log-integrity

Tamper-evident log integrity verification using Merkle trees, with a
comparative analysis of chunking strategies.

An Advanced DSA course project (B.Tech AI & Data Science). Detects and
localizes tampering in log data in O(log n) time using Merkle inclusion
proofs, instead of O(n) full-dataset re-hashing.

## What it does

Log entries are hashed (SHA-256) and organized into a Merkle tree. Any
change to a single entry propagates to the root hash, enabling fast,
localized tamper detection via Merkle proofs — without blockchain overhead.

## Contribution

Extends the base paper (Yağız, Horasan, Yurttakal, 2026) by implementing
and benchmarking three chunking strategies — fixed-size, time-window-based,
and entropy-based — comparing their effect on proof size, verification
latency, and tree rebuild cost.

## Stack

- **Frontend:** React (Vite), Tailwind CSS
- **Backend:** Java 21 + Spring Boot
- **Database:** PostgreSQL
- **Deployment:** Render

## Running the console demo

The core integrity engine is complete and can be demonstrated from the command
line while the React front end is still being built:

```bash
cd backend
mvn test                                            # 260 unit tests
mvn -q compile
java -cp target/classes com.merklelog.demo.DemoRunner       # 512 entries
java -cp target/classes com.merklelog.demo.DemoRunner 2048  # any size
```

It walks through hash primitives, forest construction, an inclusion proof with
its full verification trace, tamper detection and O(log n) localization,
rebuild cost, and a side-by-side comparison of the three chunking strategies.
The synthetic log stream uses a fixed RNG seed, so every run prints identical
hashes. A captured run is committed at [`docs/demo-output.txt`](docs/demo-output.txt).

## Status

🚧 In development.

- ✅ **Phase 1** — core DSA: hashing, Merkle tree, inclusion proofs, verifier,
  forest model, three chunking strategies. 260 unit tests, all passing.
- ⏳ **Phase 2–3** — REST API, PostgreSQL persistence.
- ⏳ **Phase 4–8** — React visualization, tamper simulation UI, strategy
  comparison page, benchmark dashboard, deployment.

## Team

- Vipin Sudhakar — CB.AI.U4AID25166
- Rithvik Arulprakash — CB.AI.U4AID25148
- Harshith KV — CB.AI.U4AID25119
- Venugopalan G — CB.AI.U4AID25115

## Reference

Yağız, Horasan, Yurttakal. *Lightweight Tamper-Evident Log Integrity
Verification for IoT Edge Environments: A Merkle-Tree Pipeline with
Adaptive Chunking.* 2026.
