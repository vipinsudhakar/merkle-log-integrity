-- V1: datasets, their log entries, and the trusted root anchor history.
--
-- The database is disposable: Render's free Postgres expires every 30 days, so everything here
-- must be rebuildable from an empty database by the seed endpoint. Datasets are generated from a
-- fixed seed, so storing them is a convenience for the API, not the source of truth.

-- A generated log stream: SyntheticLogGenerator.generate(size, seed).
CREATE TABLE datasets (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    seed        BIGINT      NOT NULL,
    size        INTEGER     NOT NULL CHECK (size >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per LogEntry, in stream order. position is the 0-based index in the stream; entry_id is
-- LogEntry.id, which is part of the hashed bytes and so must be stored exactly.
CREATE TABLE log_entries (
    dataset_id  BIGINT      NOT NULL REFERENCES datasets (id) ON DELETE CASCADE,
    position    INTEGER     NOT NULL CHECK (position >= 0),
    entry_id    BIGINT      NOT NULL,
    logged_at   TIMESTAMPTZ NOT NULL,
    level       TEXT        NOT NULL,
    source      TEXT        NOT NULL,
    message     TEXT        NOT NULL,
    PRIMARY KEY (dataset_id, position)
);

-- The base paper's "trusted anchor" (Yagiz et al. 2026, section 3.1), made concrete: each published
-- super-root with the entry count it covers. A verifier compares a recomputed root against the
-- anchored one; a mismatch means the log was rewritten after the root was published.
CREATE TABLE root_anchors (
    id           BIGSERIAL PRIMARY KEY,
    dataset_id   BIGINT      NOT NULL REFERENCES datasets (id) ON DELETE CASCADE,
    strategy     TEXT        NOT NULL,
    parameters   TEXT        NOT NULL,
    entry_count  INTEGER     NOT NULL CHECK (entry_count >= 0),
    chunk_count  INTEGER     NOT NULL CHECK (chunk_count >= 0),
    super_root   CHAR(64)    NOT NULL CHECK (super_root ~ '^[0-9a-f]{64}$'),
    anchored_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX root_anchors_dataset_idx ON root_anchors (dataset_id, anchored_at);
