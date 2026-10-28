-- Cached LLM summaries (see docs/design.md, LLM integration and Versioning and incremental
-- computation, and docs/adr/0010-hierarchical-summarizer.md). A summary is keyed by a hash of its
-- inputs, prompt version and model, so a changed input gives a new key and the old row is simply
-- no longer used.
CREATE TABLE summaries (
    cache_key       text        PRIMARY KEY CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    entity          text        NOT NULL CHECK (entity ~ '^[a-z][a-z0-9-]*:\S+$'),
    level           text        NOT NULL CHECK (level IN ('TYPE', 'PACKAGE', 'MODULE', 'REPO')),
    text            text        NOT NULL CHECK (text ~ '\S'),
    cited_facts     jsonb       NOT NULL CHECK (jsonb_typeof(cited_facts) = 'array'),
    cited_summaries jsonb       NOT NULL CHECK (jsonb_typeof(cited_summaries) = 'array'),
    input_facts     jsonb       NOT NULL CHECK (jsonb_typeof(input_facts) = 'array'),
    input_summaries jsonb       NOT NULL CHECK (jsonb_typeof(input_summaries) = 'array'),
    prompt_version  text        NOT NULL CHECK (prompt_version ~ '\S'),
    provider        text        NOT NULL CHECK (provider ~ '\S'),
    model           text        NOT NULL CHECK (model ~ '\S'),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (jsonb_array_length(cited_facts) + jsonb_array_length(cited_summaries) > 0)
);

CREATE INDEX summaries_entity ON summaries (entity);
