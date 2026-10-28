-- Repository registry and snapshots (see docs/design.md, Ingestion and build sandbox and
-- Versioning, and docs/adr/0003-repository-registry-and-snapshots.md).

-- name is the repository name used in fact evidence. settings is
-- {"jdk"?: int, "mavenProfiles"?: [text], "skipModules"?: [text]}.
CREATE TABLE repositories (
    name          text        PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$'),
    path          text        NOT NULL CHECK (path ~ '^/'),
    settings      jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
    registered_at timestamptz NOT NULL DEFAULT now()
);

-- Snapshots are numbered 1, 2, 3, ... in creation order, because fact validity is a range of
-- snapshot numbers. vocabulary_revision 0 is the empty vocabulary, which has no row.
CREATE TABLE snapshots (
    number              bigint      PRIMARY KEY CHECK (number >= 1),
    vocabulary_revision bigint      NOT NULL CHECK (vocabulary_revision >= 0),
    tool_version        text        NOT NULL CHECK (tool_version ~ '\S'),
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE snapshot_repositories (
    snapshot   bigint NOT NULL REFERENCES snapshots (number),
    repository text   NOT NULL REFERENCES repositories (name),
    commit     text   NOT NULL CHECK (commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    PRIMARY KEY (snapshot, repository)
);
