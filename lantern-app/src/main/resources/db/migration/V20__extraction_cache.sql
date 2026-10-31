-- The facts of extracted units, by a key covering every input of the unit (ADR 0092). An entry not
-- used for 30 days is deleted after each analysis run.
CREATE TABLE extraction_cache (
    extractor TEXT NOT NULL,
    version   TEXT NOT NULL,
    key       TEXT NOT NULL,
    facts     TEXT NOT NULL,
    used_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (extractor, key)
);

CREATE INDEX extraction_cache_used_at ON extraction_cache (used_at);
