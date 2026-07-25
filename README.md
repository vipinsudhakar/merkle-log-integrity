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

## Status

🚧 In development — zeroth review stage.

## Team

- Vipin Sudhakar — CB.AI.U4AID25166
- Rithvik Arulprakash — CB.AI.U4AID25148
- Harshith KV — CB.AI.U4AID25119
- Venugopalan G — CB.AI.U4AID25115

## Reference

Yağız, Horasan, Yurttakal. *Lightweight Tamper-Evident Log Integrity
Verification for IoT Edge Environments: A Merkle-Tree Pipeline with
Adaptive Chunking.* 2026.
