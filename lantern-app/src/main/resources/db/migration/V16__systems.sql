-- Named systems (see docs/design.md, Ingestion and build sandbox and Versioning, and
-- docs/adr/0071-named-systems.md). One analysis covers one system: a repository belongs to at most
-- one system, and a snapshot to the system it was taken of. Rows stored before belong to no system:
-- their snapshots are history that no system carries forward, and a repository joins the first
-- system that analyzes it. title is the display name people gave the system, if any.
CREATE TABLE systems (
    name       text        PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$'),
    title      text        CHECK (title ~ '\S'),
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE repositories ADD COLUMN system text REFERENCES systems (name);
ALTER TABLE snapshots ADD COLUMN system text REFERENCES systems (name);
