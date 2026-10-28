-- A fact keeps its ID across snapshots while its validity and confidence change (#23,
-- docs/adr/0017-fact-validity-across-snapshots.md). Each row is one version of a fact: one
-- contiguous validity range with one confidence. Versions of the same fact never overlap, and at
-- most one of them is still open.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE facts DROP CONSTRAINT facts_pkey;
ALTER TABLE facts
    ADD CONSTRAINT facts_versions_do_not_overlap EXCLUDE USING gist (id WITH =, validity WITH &&);
CREATE INDEX facts_id_idx ON facts (id);
