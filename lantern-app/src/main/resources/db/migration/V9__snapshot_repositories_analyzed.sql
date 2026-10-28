-- Marks the repositories a snapshot's analysis run analyzed, once their facts are stored, so later
-- snapshots carry a repository forward only at a commit whose facts are stored (see
-- docs/adr/0018-snapshots-carry-repositories-forward.md). Rows of earlier snapshots count as
-- analyzed, as they did before; new rows start as not analyzed.
ALTER TABLE snapshot_repositories ADD COLUMN analyzed boolean NOT NULL DEFAULT true;
ALTER TABLE snapshot_repositories ALTER COLUMN analyzed SET DEFAULT false;
