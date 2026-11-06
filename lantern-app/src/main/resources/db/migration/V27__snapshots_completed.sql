-- When a snapshot's analysis run finished, its documents written (#926), so `render` takes the
-- latest finished run, not one stopped part way. Earlier snapshots that stored facts count as
-- finished at their creation, as `render` would have taken them before; new rows start unfinished.
ALTER TABLE snapshots ADD COLUMN completed_at timestamp with time zone;
UPDATE snapshots n SET completed_at = n.created_at
WHERE EXISTS (SELECT 1 FROM snapshot_repositories s WHERE s.snapshot = n.number AND s.analyzed);
