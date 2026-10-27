-- Facts in the core envelope (see docs/design.md, Knowledge model, and
-- lantern-core/src/main/resources/schema/fact-envelope.schema.json).
-- Validity is a range of snapshot numbers: lower bound inclusive, upper bound exclusive,
-- no upper bound while the fact still holds.
CREATE TABLE facts (
    id          char(64)         PRIMARY KEY,
    subject     text             NOT NULL,
    predicate   text             NOT NULL,
    object_kind text             NOT NULL
        CHECK (object_kind IN ('entity', 'string', 'integer', 'decimal', 'boolean')),
    object      text             NOT NULL,
    attributes  jsonb            NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(attributes) = 'object'),
    origin      jsonb            NOT NULL
        CHECK (jsonb_typeof(origin) = 'object' AND origin ? 'kind' AND origin ? 'name'),
    evidence    jsonb            NOT NULL
        CHECK (jsonb_typeof(evidence) = 'object' AND evidence ? 'repo' AND evidence ? 'commit'),
    confidence  double precision NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    validity    int8range        NOT NULL
        CHECK (NOT isempty(validity) AND NOT lower_inf(validity) AND lower(validity) >= 0)
);

CREATE INDEX facts_subject_idx ON facts (subject);
CREATE INDEX facts_predicate_idx ON facts (predicate);
CREATE INDEX facts_object_idx ON facts (object, object_kind);
CREATE INDEX facts_validity_idx ON facts USING gist (validity);
