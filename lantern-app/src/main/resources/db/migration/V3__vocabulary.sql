-- Vocabulary registry (see docs/design.md, Knowledge model, and
-- docs/adr/0002-vocabulary-registry.md). Every change is a new revision; a revision stores the
-- new definition of each type it changed, so the vocabulary as of any revision is, per name,
-- the definition with the highest revision up to it. Revision 0 is the empty vocabulary.
CREATE TABLE vocabulary_revisions (
    revision   bigint      PRIMARY KEY CHECK (revision >= 1),
    source     text        NOT NULL CHECK (source ~ '\S'),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- definition is the JSON form in lantern-core/src/main/resources/schema/vocabulary-type.schema.json.
CREATE TABLE vocabulary_types (
    name       text   NOT NULL,
    revision   bigint NOT NULL REFERENCES vocabulary_revisions (revision),
    definition jsonb  NOT NULL
        CHECK (jsonb_typeof(definition) = 'object'
               AND definition ->> 'name' = name
               AND definition ? 'kind'
               AND definition ? 'attributeSchema'),
    PRIMARY KEY (name, revision)
);
