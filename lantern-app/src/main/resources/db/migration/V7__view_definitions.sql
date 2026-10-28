-- View definitions: the persisted spec of each document, its scope, audience, template and sections
-- (see docs/design.md, Composition, layout stability and prose stability, and
-- docs/adr/0012-composer-per-repo-documents.md). The definition is stored as the JSON that
-- io.lantern.compose.ViewDefinitionJson writes and reads.
CREATE TABLE view_definitions (
    id         text        PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'),
    definition jsonb       NOT NULL CHECK (jsonb_typeof(definition) = 'object' AND definition->>'id' = id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
