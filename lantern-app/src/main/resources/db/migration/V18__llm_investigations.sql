-- Cached proposals of the Investigate role that passed Lantern's checks (see docs/design.md, LLM
-- integration, and docs/adr/0082-investigate-role.md). One row per prompt: a deployable unit or the
-- system as a whole, keyed by a hash of the prompt version, provider, model and prompt, so a changed
-- input gives a new key and the old row is simply no longer used.
CREATE TABLE llm_investigations (
    cache_key      text        PRIMARY KEY CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    scope          text        NOT NULL CHECK (scope ~ '\S'),
    proposals      jsonb       NOT NULL CHECK (jsonb_typeof(proposals) = 'array'),
    prompt_version text        NOT NULL CHECK (prompt_version ~ '\S'),
    provider       text        NOT NULL CHECK (provider ~ '\S'),
    model          text        NOT NULL CHECK (model ~ '\S'),
    created_at     timestamptz NOT NULL DEFAULT now()
);
